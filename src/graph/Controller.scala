package pir.graph

import scala.collection.mutable.Set
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map
import scala.math.max
import pir.Design
import pir.graph._
import pir.graph.enums._
import pir.graph.mapper.PIRException
import scala.reflect.runtime.universe._
import pir.graph.traversal.ForwardRef

abstract class Controller(implicit design:Design) extends Node { self =>
  implicit val ctrler = self 
  val sinMap = Map[Scalar, ScalarIn]()
  val soutMap = Map[Scalar, ScalarOut]()
  def souts = soutMap.values.toList
  val vinMap = Map[Vector, VecIn]()
  val voutMap = Map[Vector, VecOut]()
  def vouts = voutMap.values.toList
  def newSout(s:Scalar):ScalarOut = soutMap.getOrElseUpdate(s,ScalarOut(s))
  def newVout(v:Vector):VecOut = {
    v match {
      case v:DummyVector => voutMap.getOrElseUpdate(v, new DummyVecOut(None, v))
      case _ => voutMap.getOrElseUpdate(v, VecOut(v))
    }
  }
  def ctrlIns:List[CtrlInPort] = ctrlBox.ctrlIns
  def ctrlOuts:List[CtrlOutPort] = ctrlBox.ctrlOuts 
  // No need to consider scalar after bundling
  def readers:List[Controller] = voutMap.keys.flatMap {
    _.readers.map{ _.ctrler }
  }.toList
  def writers:List[Controller] = vinMap.keys.map(_.writer.ctrler).toList
  def ctrlReaders:List[Controller] = ctrlOuts.flatMap {_.to }.map { _.asInstanceOf[CtrlInPort].ctrler }.filter { _ != this }

  def sins = sinMap.values.toList
  def vins = vinMap.values.toList 
  def newSin(s:Scalar):ScalarIn = sinMap.getOrElseUpdate(s, ScalarIn(s))
  def newVin(v:Vector):VecIn = {
    v match {
      case v:DummyVector => vinMap.getOrElseUpdate(v, new DummyVecIn(None, v))
      case _ => vinMap.getOrElseUpdate(v,VecIn(v))
    }
  }

  def ctrlBox:CtrlBox

  val _children = ListBuffer[ComputeUnit]()
  def children:List[ComputeUnit] = _children.toList
  def removeChildren(c:ComputeUnit) = { _children -= c }
  def addChildren(c:ComputeUnit) = { if (!_children.contains(c)) _children += c }

  private val _consumed = ListBuffer[MultiBuffering]()
  private val _produced = ListBuffer[MultiBuffering]()
  def consume(mem:MultiBuffering) = _consumed += mem
  def produce(mem:MultiBuffering) = _produced += mem
  def consumed = _consumed.toList
  def produced = _produced.toList

  def isHead = (consumed.filterNot{_.producer == design.top}.size==0)
  def isLast = (produced.filterNot{_.consumer == design.top}.size==0)
  def isUnitStage = isHead && isLast

  /* Number of children stages on the critical path */
  def length:Int = {
    var count = 1
    var heads = children.filter{!_.isHead}
    while(heads.size!=0) {
      // Collect consumers that are not Top
      heads = heads.flatMap { _.produced.map { _.consumer } }.collect{case cu:ComputeUnit => cu }.toSet.toList
      count +=1
    }
    count
  }

  // Including current CU. From current to top
  def ancestors: List[ComputeUnit] = {
    val list = ListBuffer[ComputeUnit]()
    var child:Controller = this 
    while (!child.isInstanceOf[Top]) {
      val temp = child.asInstanceOf[ComputeUnit]
      list += temp 
      child = temp.parent
    }
    list.toList
  }

}

abstract class ComputeUnit(override val name: Option[String])(implicit design: Design) extends Controller with OuterRegBlock { self => 
  implicit val cu:ComputeUnit = self 
  override val typeStr = "CU"

  private var _parent:Controller = _
  def parent:Controller = { _parent }
  def parent(p:Controller) = { _parent = p }
  def removeParent:Unit = _parent = null

  /* Fields */
  /* CounterChains */
  val cchainMap = Map[CounterChain, CounterChain]() // map between original and copied cchains
  def cchains = cchainMap.values.toList
  def addCChain(cc:CounterChain):Unit = {
    if (!cc.isDefined) return // If cc is a copy but haven't been updated, addCChain during update 
    if (cchainMap.contains(cc.original))
      throw PIRException(s"Already have copy/original copy of ${cc.original} but adding duplicated copy ${cc}")
    else cchainMap += (cc.original -> cc)
  }
  def removeCChain(cc:CounterChain):Unit = {
    cchainMap.get(cc.original).foreach { cp => if (cp== cc) cchainMap -= cc.original }
  }
  def removeCChainCopy(cc:CounterChain):Unit = { 
    assert(!cc.isCopy)
    cchainMap -= cc
  }

  def getCopy(cchain:CounterChain):CounterChain = {
    assert(cchain.isDefined)
    cchainMap.getOrElseUpdate(cchain.original, CounterChain.copy(cchain.original)(this, design))
  }

  def containsCopy(cchain:CounterChain):Boolean = {
    cchainMap.contains(cchain.original)
  }
  //  sins:List[ScalarIn] = _
  //  souts:List[ScalarOut] = _
  
  def inner:InnerController

  lazy val localCChain:CounterChain = {
    this match {
      case cu:StreamPipeline =>
        if (cu.isHead) {
          cu.getCopy(cu.parent.localCChain)
        } else if (cu.isLast) {
          cu match {
            case mc:MemoryController => throw PIRException(s"MemoryController doesn't have localCChain")
            case sp:StreamPipeline => cu.getCopy(cu.parent.localCChain)
          }
        } else { // middle stages
          if (cu.containsCopy(cu.parent.localCChain)) {
            cu.getCopy(cu.parent.localCChain)
          } else if (cchains.size==0) {
            val dc = CounterChain.dummy(cu, design)
            cu.addCChain(dc)
            dc
          } else {
            val dcs = cchains.filter{_.isDummy}
            assert(dcs.size==1)
            dcs.head
          }
        }
      case cu:MemoryPipeline =>
        throw PIRException(s"MemoryPipeline doesn't have local counter chain")
      case cu =>
        val locals = cchains.filter{_.isLocal}
        assert(locals.size==1, 
          s"Currently assume each ComputeUnit only have a single local Counterchain ${this} [${locals.mkString(",")}]")
        locals.head
    }
  }

  def writtenMem:List[OnChipMem] = {
    (soutMap ++ voutMap).values.flatMap{_.readers.flatMap{ _.out.to }}.map{_.src}.collect{ case ocm:OnChipMem => ocm }.toList
  }

  override def toUpdate = { super.toUpdate }

  def updateBlock(block: this.type => Any)(implicit design: Design):this.type = {
    val (cchains, mems) = design.addBlock[CounterChain, OnChipMem](block(this), 
                            (n:Node) => n.isInstanceOf[CounterChain],
                            (n:Node) => n.isInstanceOf[OnChipMem] 
                            ) 
    cchains.foreach { cc => addCChain(cc) }
    this.mems(mems)
    this
  }

  def updateParent[T](parent:T):this.type = {
    parent match {
      case p:String =>
        design.updateLater(p, (n:Node) => updateParent(n.asInstanceOf[Controller]))
      case p:Controller =>
        this.parent(p)
        p.addChildren(this)
    }
    this
  }

  var index = -1
  def nextIndex = { val temp = index; index +=1 ; temp}

  val emptyStage = EmptyStage(); indexOf(emptyStage) = nextIndex 
  def stages:List[Stage] = emptyStage :: Nil 

  /* Memories */
  val _mems = ListBuffer[OnChipMem]()
  def mems(ms:List[OnChipMem]) = { ms.foreach { m => _mems += m } }
  def mems:List[OnChipMem] = _mems.toList

  def fifos:List[FIFO] = mems.collect {case fifo:FIFO => fifo }
}

class OuterController(name:Option[String])(implicit design:Design) extends ComputeUnit(name) { self =>
  override implicit val ctrler:OuterController = self 

  var inner:InnerController = _

  override def toUpdate = super.toUpdate || inner == null 

  override def addCChain(cc:CounterChain):Unit = {
    assert(!cc.isCopy, "Outer controller cannot make copy of other CounterChain")
    super.addCChain(cc)
  }
  override def getCopy(cchain:CounterChain):CounterChain = {
    if (cchain.ctrler!=ctrler)
      throw PIRException(s"OuterController cannot make copy of other CounterChain")
    else cchain
  }

  val ctrlBox:OuterCtrlBox = OuterCtrlBox()
}

class Sequential(name:Option[String])(implicit design:Design) extends OuterController(name) {
  override val typeStr = "SeqCU"
}
object Sequential {
  def apply[P](name: Option[String], parent:P) (block: Sequential => Any)
                (implicit design: Design):Sequential = {
    new Sequential(name).updateParent(parent).updateBlock(block)
  }
  /* Sugar API */
  def apply[P](parent:P) (block: Sequential => Any)
                 (implicit design:Design):Sequential =
    Sequential(None, parent)(block)
  def apply[P](name:String, parent:P) (block:Sequential => Any)
                 (implicit design:Design):Sequential =
    Sequential(Some(name), parent)(block)
}

class MetaPipeline(name:Option[String])(implicit design:Design) extends OuterController(name) {
  override val typeStr = "MetaPipeCU"
}
object MetaPipeline {
  def apply[P](name: Option[String], parent:P) (block: MetaPipeline => Any)
                (implicit design: Design):MetaPipeline = {
    new MetaPipeline(name).updateParent(parent).updateBlock(block)
  }
  /* Sugar API */
  def apply [P](parent:P) (block: MetaPipeline => Any)
                 (implicit design:Design):MetaPipeline =
    MetaPipeline(None, parent)(block)
  def apply[P](name:String, parent:P) (block:MetaPipeline => Any)
                (implicit design:Design):MetaPipeline =
    MetaPipeline(Some(name), parent)(block)
}

class StreamController(name:Option[String])(implicit design:Design) extends OuterController(name) {
  override val typeStr = "StreamCtrler"
  override def children:List[InnerController] = {
    super.children.asInstanceOf[List[InnerController]]
  }
}
object StreamController {
  def apply[P](name: Option[String], parent:P) (block: StreamController => Any)
                (implicit design: Design):StreamController = {
    new StreamController(name).updateParent(parent).updateBlock(block)
  }
  /* Sugar API */
  def apply [P](parent:P) (block: StreamController => Any)
                 (implicit design:Design):StreamController =
    StreamController(None, parent)(block)
  def apply[P](name:String, parent:P) (block:StreamController => Any)
                 (implicit design:Design):StreamController =
    StreamController(Some(name), parent)(block)
}

abstract class InnerController(name:Option[String])(implicit design:Design) extends ComputeUnit(name)
 with InnerRegBlock {
  implicit val icu:InnerController = this 

  def srams:List[SRAM] = mems.collect{ case sm:SRAM => sm }
  def fows:List[FIFOOnWrite] = mems.collect{ case sm:FIFOOnWrite => sm }

  /* Stages */
  val wtAddrStages = ListBuffer[List[WAStage]]()
  val localStages = ListBuffer[LocalStage]()

  override def stages = (emptyStage :: wtAddrStages.flatMap(l => l).toList ++ localStages).toList

  def addWAStages(was:List[WAStage]) = {
    wtAddrStages += was
    was.foreach { wa => indexOf(wa) = nextIndex }
  }

  def addStage(s:Stage):Unit = { s match {
      case ss:LocalStage =>
        localStages += ss
        indexOf(ss) = nextIndex
      case ss:WAStage => // WAstages are added in addWAStages 
    }
  }

  /* Controller Hierarchy */
  def locals = this :: outers
  /* List of outer controllers reside in current inner*/
  var outers:List[OuterController] = Nil
  def inner:InnerController = this

  /* Control Signals */
  val ctrlBox:InnerCtrlBox = InnerCtrlBox()
  
  def udcounters = locals.flatMap{ _.ctrlBox.udcounters }
  def enLUTs:List[EnLUT] = locals.flatMap(_.ctrlBox.enLUTs)
  def tokDownLUTs = locals.flatMap(_.ctrlBox.tokDownLUTs)
  def tokOutLUTs = locals.flatMap(_.ctrlBox.tokOutLUTs)

  /* Block updates */
  override def reset =  { super.reset; localStages.clear; wtAddrStages.clear }

}

class Pipeline(name:Option[String])(implicit design:Design) extends InnerController(name) { self =>
  override val typeStr = "PipeCU"

}
object Pipeline {
  def apply[P](name: Option[String], parent:P)(block: Pipeline => Any)(implicit design: Design):Pipeline = {
    new Pipeline(name).updateParent(parent).updateBlock(block)
  }
  /* Sugar API */
  def apply [P](parent:P) (block: Pipeline => Any) (implicit design:Design):Pipeline =
    apply(None, parent)(block)
  def apply[P](name:String, parent:P) (block:Pipeline => Any) (implicit design:Design):Pipeline =
    apply(Some(name), parent)(block)
}

/* Inner Unit Pipe */
class UnitPipeline(override val name: Option[String])(implicit design: Design) extends Pipeline(name) { self =>
  override val typeStr = "UnitPipe"
}
object UnitPipeline {
  def apply[P](name: Option[String], parent:P)(implicit design: Design):UnitPipeline =
    new UnitPipeline(name).updateParent(parent)
  /* Sugar API */
  def apply[P](parent:P) (block: UnitPipeline => Any) (implicit design:Design):UnitPipeline =
    UnitPipeline(None, parent).updateBlock(block)
  def apply[P](name:String, parent:P) (block:UnitPipeline => Any) (implicit design:Design):UnitPipeline =
    UnitPipeline(Some(name), parent).updateBlock(block)
}

/* Memory Pipeline */
class MemoryPipeline(override val name: Option[String])(implicit design: Design) extends Pipeline(name) { self =>
  override implicit val ctrler:MemoryPipeline = self 

  override val typeStr = "MemPipe"
  override val ctrlBox:MemCtrlBox = MemCtrlBox()
  override def isHead = false
  override def isLast = false

  val data = Vector()
  val dataOut = VecOut(data)
  lazy val mem:MultiBuffering = {
    val rms = mems.collect{ case m:SemiFIFO => m; case m:SRAM => m}
    assert(rms.size==1)
    val m = rms.head
    dataOut.in.connect(m.load)
    m
  }
}
object MemoryPipeline {
  def apply[P](name: Option[String], parent:P)(implicit design: Design):MemoryPipeline =
    new MemoryPipeline(name).updateParent(parent)
  /* Sugar API */
  def apply[P](parent:P) (block: MemoryPipeline => Any) (implicit design:Design):MemoryPipeline =
    MemoryPipeline(None, parent).updateBlock(block)
  def apply[P](name:String, parent:P) (block:MemoryPipeline => Any) (implicit design:Design):MemoryPipeline =
    MemoryPipeline(Some(name), parent).updateBlock(block)
}

case class TileTransfer(override val name:Option[String], memctrl:MemoryController, mctpe:MCType, vec:Vector)
  (implicit design:Design) extends MemoryPipeline(name)  {
  override val typeStr = s"${mctpe}"
} 
object TileTransfer extends {
  /* Sugar API */
  def apply[P](name:Option[String], parent:P, memctrl:MemoryController, mctpe:MCType, vec:Vector)(block:TileTransfer => Any)
                (implicit design:Design):TileTransfer =
    TileTransfer(name, memctrl, mctpe, vec).updateParent(parent).updateBlock(block)
  def apply[P](name:String, parent:P, memctrl:MemoryController, mctpe:MCType, vec:Vector) (block:TileTransfer => Any)             
                (implicit design:Design):TileTransfer =
    TileTransfer(Some(name), memctrl, mctpe, vec:Vector).updateParent(parent).updateBlock(block)
}

class StreamPipeline(name:Option[String])(implicit design:Design) extends InnerController(name) { self =>
  override val typeStr = "StreamPipe"
  private var _parent:StreamController = _
  override def parent:StreamController = _parent
  override def parent(p:Controller) = { 
    p match {
      case p:StreamController => _parent = p
      case _ => throw PIRException(s"StreamPipeline's parent must be StreamController $this.parent=$p")
    }
  }
  override def removeParent:Unit = _parent = null

  def writtenFIFO:List[FIFO] = writtenMem.collect { case fifo:FIFO => fifo }

  override def isHead = mems.collect { case fifo:FIFO => fifo }.size==0
  override def isLast = writtenFIFO.filter{_.ctrler.parent==parent}.size==0
}
object StreamPipeline {
  def apply[P](name: Option[String], parent:P) (block: StreamPipeline => Any)
                (implicit design: Design):StreamPipeline = {
    new StreamPipeline(name).updateParent(parent).updateBlock(block)
  }
  /* Sugar API */
  def apply [P](parent:P) (block: StreamPipeline => Any)
                 (implicit design:Design):StreamPipeline =
    StreamPipeline(None, parent)(block)
  def apply[P](name:String, parent:P) (block:StreamPipeline => Any)
                (implicit design:Design):StreamPipeline =
    StreamPipeline(Some(name), parent)(block)
}

class MemoryController(name: Option[String], val mctpe:MCType, val offchip:OffChip)(implicit design: Design) extends StreamPipeline(name) { self =>
  override val typeStr = "MemoryController"

  val _ofs = if (mctpe==TileLoad || mctpe==TileStore) Some(Scalar("ofs")) else None
  def ofs:Scalar = _ofs.get
  val siofs = { _ofs.map { ofs => newSin(ofs) } }
  val ofsFIFO = _ofs.map { ofs => ScalarFIFO(100).wtPort(ofs) }

  val _len = if (mctpe==TileLoad || mctpe==TileStore) Some(Scalar("len")) else None
  def len:Scalar = _len.get
  val silen = { _len.map { len => newSin(len) } }
  val lenFIFO = _len.map { len => ScalarFIFO(100).wtPort(len) }

  val data = Vector()
  private val _dataIn  = if (mctpe==TileStore || mctpe==Scatter) { Some(newVin(data)) } else None
  private val _dataOut = if (mctpe==TileLoad || mctpe==Gather) { Some(newVout(data)) } else None
  def dataIn = _dataIn.get
  def dataOut = _dataOut.get
  val dataFIFO = mctpe match {
    case TileStore | Scatter => 
      Some(VectorFIFO(s"${this}DataFIFO", 100).wtPort(data))
    case _ => None
  }

  val _addrs = if (mctpe==Gather || mctpe==Scatter) Some(Vector()) else None
  def addrs = _addrs.get
  val viaddrs = { _addrs.map { addrs => newVin(addrs) } }
  val addrFIFO = _addrs.map { addrs => VectorFIFO(s"${this}AddrFIFO", 100) }

  val dataValid = CtrlOutPort(this, s"${this}.dataValid")
  val done = CtrlOutPort(this, s"${this}.done")
  val dummyCtrl = CtrlOutPort(this, s"${this}.dummy")

  mems((ofsFIFO ++ lenFIFO ++ addrFIFO ++ dataFIFO).toList)
}
object MemoryController {
  def apply(mctpe:MCType, offchip:OffChip)(implicit design: Design): MemoryController 
    = new MemoryController(None, mctpe, offchip)
  def apply(name:String, mctpe:MCType, offchip:OffChip)(implicit design: Design): MemoryController 
    = new MemoryController(Some(name), mctpe, offchip)
}

case class Top()(implicit design: Design) extends Controller { self =>
  implicit val top:Controller = self

  override val name = Some("Top")
  override val typeStr = "Top"

  /* Fields */
  private var _innerCUs:List[InnerController] = Nil
  def innerCUs(innerCUs:List[InnerController]) = _innerCUs = innerCUs
  def innerCUs = _innerCUs

  private var _outerCUs:List[OuterController] = Nil
  def outerCUs(outerCUs:List[OuterController]) = _outerCUs = outerCUs 
  def outerCUs = _outerCUs

  private var _memCUs:List[MemoryPipeline] = Nil
  def memCUs(memCUs:List[MemoryPipeline]) = _memCUs = memCUs
  def memCUs = _memCUs

  def compUnits:List[ComputeUnit] = innerCUs ++ outerCUs
  def spadeCtrlers:List[Controller] = this :: innerCUs
  def ctrlers = this :: compUnits

  def removeCtrler(ctrler:Controller) = {
    ctrler match {
      case _:InnerController => 
        _innerCUs = _innerCUs.filterNot(_==ctrler)
      case _:OuterController => 
        _outerCUs = _outerCUs.filterNot(_==ctrler)
    }
  }
  val command = CtrlOutPort(this, s"${this}.command")
  val status = CtrlInPort(this, s"${this}.status")

  private var _scalars:List[Scalar] = Nil
  def scalars:List[Scalar] = _scalars
  def scalars(scalars:List[Scalar]) = _scalars = scalars

  private var _vectors:List[Vector] = Nil
  def vectors:List[Vector] = _vectors
  def vectors(vectors:List[Vector]) = _vectors = vectors

  override val ctrlBox:OuterCtrlBox = OuterCtrlBox()(this, design)

  //  sins:List[ScalarIn] = _
  //  souts:List[ScalarOut] = _
  //  vins:List[VecIn] = _
  //  vouts:List[VecOut] = _
  
  override def toUpdate = super.toUpdate || innerCUs == null || outerCUs == null

  def updateBlock(block:Top => Any)(implicit design: Design):Top = {
    val (inners, outers, memcus, scalars, vectors) = 
      design.addBlock[InnerController, OuterController, MemoryPipeline, Scalar, Vector](block(this), 
                      (n:Node) => n.isInstanceOf[InnerController],
                      (n:Node) => n.isInstanceOf[OuterController],
                      (n:Node) => n.isInstanceOf[MemoryPipeline],
                      (n:Node) => n.isInstanceOf[Scalar], 
                      (n:Node) => n.isInstanceOf[Vector] 
                      )
    this.innerCUs(inners)
    this.outerCUs(outers)
    this.memCUs(memcus)
    this.scalars(scalars)
    scalars.foreach { s => s match {
        case a:ArgIn => 
          super.newSout(a)
        case a:ArgOut => 
          super.newSin(a)
        case _ => 
      }
    }
    this.vectors(vectors)
    this
  }
}

