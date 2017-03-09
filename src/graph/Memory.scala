package pir.graph

import scala.collection.mutable.Set
import scala.collection.immutable.{Set => ISet}
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.Map
import scala.math.max
import scala.reflect.runtime.universe._
import pir.{Design, Config}
import pir.graph._
import pir.graph.enums._
import pir.graph.mapper.PIRException
import pir.graph.traversal.ForwardRef

abstract class OnChipMem(implicit override val ctrler:ComputeUnit, design:Design) extends Primitive {
  val size:Int
  val banking:Banking

  val readPort: ReadOutPort = ReadOutPort(this, s"${this}.rp") 
  val writePort: WriteInPort = WriteInPort(this, s"${this}.wp")

  def isRemoteWrite = this match {
    case _:CommandFIFO => writePort.from.src.isInstanceOf[ScalarIn] 
    case _ => writePort.from.src.isInstanceOf[VecIn]
  } 

  def wtPort(wp:OutPort):this.type = { writePort.connect(wp); this } 
  def load = readPort

  def writer:Controller = {
    writePort.from.src match {
      case fifo:VectorFIFO => fifo.writer
      case VecIn(_, vector) => vector.writer.ctrler
      case ScalarIn(_, scalar) => scalar.writer.ctrler
      case p => throw PIRException(s"Unknown OnChipMem write port ${p}")
    }
  }

  def reader:Controller = {
    assert(readPort.to.size>=1, s"$this's readPort is connected to ${readPort.to}")
    readPort.to.head.src match {
      case vo:VecOut => 
        assert(vo.vector.readers.size==1, s"Currently assume each OnChipMem=$this in ${this.ctrler} can only have 1 remote reader ${vo.vector.readers}")
        vo.vector.readers.head.ctrler
      case so:ScalarOut =>
        assert(so.scalar.readers.size==1, s"Currently assume each OnChipMem=$this in ${this.ctrler} can only have 1 remote reader ${so.scalar.readers}")
        so.scalar.readers.head.ctrler
      case cu:Controller => cu
      case p:Primitive => p.ctrler
      case p => throw new Exception(s"Unknown OnChipMem read port ${p}")
    }
  }
}

trait SRAMOnRead extends MultiBuffering {
  val readAddr: RdAddrInPort = RdAddrInPort(this, s"${this}.ra")
  def rdAddr(ra:OutPort):this.type = { 
    readAddr.connect(ra); 
    ra.src match {
      case PipeReg(stage,r) =>
        throw PIRException(s"Currently don't support register to readAddr! sram:${this}")
      case _ =>
    }
    this
  } 
}
trait FIFOOnRead extends OnChipMem {
  /* Control Signals */
  val notEmpty = CtrlOutPort(this, s"$this.notEmpty")
  val dequeueEnable = CtrlInPort(this, s"$this.deqEn")
}

trait SRAMOnWrite extends MultiBuffering {
  val writeAddr: WtAddrInPort = WtAddrInPort(this, s"${this}.wa")
  def wtAddr(wa:OutPort):this.type = { 
    writeAddr.connect(wa)
    this 
  }
  def writeCtr = writeAddr.from.src.asInstanceOf[Counter].cchain.inner
}
trait FIFOOnWrite extends OnChipMem { ocm:OnChipMem =>
  var _wtStart:Option[OutPort] = None
  var _wtEnd:Option[OutPort] = None 
  def wtStart(op:OutPort):this.type = { _wtStart = Some(op); this }
  def wtEnd(op:OutPort):this.type = { _wtEnd = Some(op); this }
  def wtStart:Option[OutPort] = _wtStart
  def wtEnd:Option[OutPort] = _wtEnd 

  /* Control Signals */
  val notFull = CtrlOutPort(this, s"$this.notFull")
  val enqueueEnable = CtrlInPort(this, s"$this.enqueueEnable")
  override def toUpdate = super.toUpdate

  def isOfsFIFO:Boolean = {
    ocm.ctrler match {
      case mc:MemoryController =>
        mc.siofs.fold(false) { siofs =>
          writePort.isConnectedTo(siofs.out)
        }
      case _ => false
    }
  }
}

trait MultiBuffering extends OnChipMem {
  val design:Design
  var _producer:Controller = _
  var _consumer:Controller = _
  def producer:Controller = _producer
  def consumer:Controller = _consumer
  var trueDep:Boolean = _ // Whether the consumer is a true dependency

  def producer[T](pd:T):this.type = {
    pd match {
      case pd:String =>
        design.updateLater(pd, (n:Node) => producer(n.asInstanceOf[Controller]))
      case pd:Controller =>
        this._producer = pd
        pd.produce(this)
    }
    this
  }
  def consumer[T](cs:T, trueDep:Boolean):this.type = {
    cs match {
      case cs:String =>
        design.updateLater(cs, (n:Node) => consumer(n.asInstanceOf[Controller], trueDep))
      case cs:Controller =>
        this._consumer = cs
        this.trueDep = trueDep
        cs.consume(this)
    }
    this
  }

  var _buffering:Int = _
  def buffering = _buffering
  def buffering(buf:Int):this.type = { _buffering = buf; this }
}
trait FIFO extends OnChipMem with FIFOOnRead with FIFOOnWrite {
  override val typeStr = "FIFO"
  override val banking = Strided(1)
}

trait LocalMem extends OnChipMem {
  override def reader:Controller = {
    val reader = super.reader
    assert(reader == this.ctrler)
    reader
  }
}
trait RemoteMem extends OnChipMem { self:VectorMem =>
  def rdPort(vec:Vector):this.type = { rdPort(ctrler.newVout(vec)) }
  def rdPort(vecOut:VecOut):this.type = { vecOut.in.connect(readPort); this }
  override def wtPort(vecIn:VecIn):this.type = { 
    val fifo = ctrler.getRetimingFIFO(vecIn.vector)
    fifo.wtPort(vecIn.out)
    wtPort(fifo.load)
  }
}

trait VectorMem extends OnChipMem {
  def wtPort(vecIn:VecIn):this.type = { wtPort(vecIn.out) }
  def wtPort(vec:Vector):this.type = { wtPort(ctrler.newVin(vec)) }
  def wtPort(vecOut:VecOut):this.type = { wtPort(vecOut.vector) }
}

/** SRAM 
 *  @param name: user defined optional name of SRAM 
 *  @param size: size of SRAM in all dimensions 
 *  @param banking: Banking mode of SRAM
 *  @param writeCtr: TODO what was this again? counter that controls the write enable and used to
 *  calculate write address?
 */
case class SRAM(name: Option[String], size: Int, banking:Banking)(implicit ctrler:MemoryPipeline, design: Design) 
  extends VectorMem with RemoteMem with SRAMOnRead with SRAMOnWrite {
  override val typeStr = "SRAM"
}
object SRAM {
  def apply(size:Int, banking:Banking)(implicit ctrler:MemoryPipeline, design: Design): SRAM
    = SRAM(None, size, banking)
  def apply(name:String, size:Int, banking:Banking)(implicit ctrler:MemoryPipeline, design: Design): SRAM
    = SRAM(Some(name), size, banking)
}
case class SemiFIFO(name: Option[String], size: Int, banking:Banking)(implicit ctrler:MemoryPipeline, design: Design) 
  extends VectorMem with RemoteMem with SRAMOnRead with FIFOOnWrite {
  override val typeStr = "SemiFIFO"
}
object SemiFIFO {
  def apply(size:Int, banking:Banking)(implicit ctrler:MemoryPipeline, design: Design): SemiFIFO
    = SemiFIFO(None, size, banking)
  def apply(name:String, size:Int, banking:Banking)(implicit ctrler:MemoryPipeline, design: Design): SemiFIFO
    = SemiFIFO(Some(name), size, banking)
}

class VectorFIFO(val name: Option[String], val size: Int)(implicit ctrler:ComputeUnit, design: Design) 
  extends VectorMem with LocalMem with FIFO {
  override val typeStr = "FIFO"
}
object VectorFIFO {
  def apply(size:Int)(implicit ctrler:ComputeUnit, design: Design): VectorFIFO
    = new VectorFIFO(None, size)
  def apply(name:String, size:Int)(implicit ctrler:ComputeUnit, design: Design): VectorFIFO
    = new VectorFIFO(Some(name), size)
}

trait ScalarMem extends OnChipMem with LocalMem {
  def wtPort(s:Scalar):this.type = { wtPort(ctrler.newSin(s).out) }
}

case class ScalarBuffer(name:Option[String])(implicit ctrler:ComputeUnit, design: Design) 
  extends ScalarMem with MultiBuffering {
  override val typeStr = "ScalBuf"
  override val size = 1
  override val banking = NoBanking()
}
object ScalarBuffer {
  def apply()(implicit ctrler:ComputeUnit, design: Design):ScalarBuffer
    = ScalarBuffer(None)
  def apply(name:String)(implicit ctrler:ComputeUnit, design: Design):ScalarBuffer
    = ScalarBuffer(Some(name))
}

class ScalarFIFO(val name: Option[String], val size: Int)(implicit ctrler:ComputeUnit, design: Design) 
  extends ScalarMem with FIFO {
  override val typeStr = "ScalarFIFO"
}
object ScalarFIFO {
  def apply(size:Int)(implicit ctrler:ComputeUnit, design: Design): ScalarFIFO
    = new ScalarFIFO(None, size)
  def apply(name:String, size:Int)(implicit ctrler:ComputeUnit, design: Design): ScalarFIFO
    = new ScalarFIFO(Some(name), size)
}

case class CommandFIFO(mc:MemoryController)(implicit ctrler:InnerController, design: Design) 
  extends ScalarFIFO(Some(s"${mc}CommandFIFO"), 1000)