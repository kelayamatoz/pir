import pir.graph._
import pir.graph.{MemoryController => MemCtrl, MetaPipeline => MetaPipe}
import pir.graph
import pir.codegen._
import pir.plasticine.config._
import pir.Design
import pir.PIRMisc._

/* Example PIR without using block (Spatial to PIR generation)*/
object DotProductNoSugar extends Design {
  override val arch = Config0 

  val tileSize = Const(4l)
  val dataSize = ArgIn()

  // Pipe.fold(dataSize by tileSize par outerPar)(out){ i =>
  val outer = {
    val ds = ScalarIn(dataSize)
    ComputeUnit(name=None, parent="Top", tpe=MetaPipeline,
      cchains=List(CounterChain(name="i", ds.out by tileSize)),
      srams=Nil,
      sins=List(ds),
      souts=Nil,
      pipeline=Pipeline(None)
    )
  }
  // b1 := v1(i::i+tileSize)
  val tileLoadA =  {
    val ic = CounterChain.copy(outer, "i")
    val it = CounterChain(name="it", Const(0) until tileSize by Const(1))
    val PL = Pipeline(None)
    val s0::_ = Stages(1)(PL, this)
    Stage(s0, opds=List(it(0), ic(0)), o=FixAdd, r=PL.vecOut(s0), prm=PL) (this)
    MemCtrl (name=Some("tileLoadA"), parent=outer, dram="A",
      cchains=List(ic, it),
      srams=Nil,
      sins=Nil,
      souts=Nil,
      pipeline = PL 
    )
  }
  // b2 := v2(i::i+tileSize)
  val tileLoadB =  {
    val ic = CounterChain.copy(outer, "i")
    val it = CounterChain(name="it", Const(0) until tileSize by Const(1))
    val PL = Pipeline(None)
    val s0::_ = Stages(1)(PL, this)
    Stage(s0, opds=List(it(0), ic(0)), o=FixAdd, r=PL.vecOut(s0), prm=PL) (this)
    MemCtrl (name=Some("tileLoadB"), parent=outer, dram="B",
      cchains=List(ic, it),
      srams=Nil,
      sins=Nil,
      souts=Nil,
      pipeline = PL 
    )
  }
  //Pipe.reduce(tileSize par innerPar)(Reg[T]){ii => b1(ii) * b2(ii) }{_+_}
  val inner = {
    // StateMachines / CounterChain
    val ii = CounterChain(tileSize by Const(1l)) //Local
    val itA = CounterChain.copy(tileLoadA, "it")
    val itB = CounterChain.copy(tileLoadB, "it")
    val PL = Pipeline(None)
    val s0::s1::s2::_ = Stages(3)(PL, this)
    // SRAMs
    val A = SRAM(size=32, write=tileLoadA, readAddr=ii(0), writeAddr=itA(0))
    val B = SRAM(size=32, write=tileLoadB, readAddr=ii(0), writeAddr=itB(0))
    // Pipeline Stages 
    Stage(s0, opds=List(A.load,B.load), o=FixMul, r=PL.reduce(s0), prm=PL) (this)
    Stage.reduce(s1, op=FixAdd) (PL, this) 
    Stage(s2, opds=List(PL.reduce(s1)), o=Bypass, r=PL.vecOut(s0), prm=PL) (this)

    ComputeUnit(name=Some("inner"), parent=outer, tpe=Pipe,
      cchains=List(ii, itA, itB),
      srams=Nil,
      sins=Nil,
      souts=Nil,
      pipeline=Pipeline(None)
    )
  }

  top = Top(List(outer, tileLoadA, tileLoadB, inner))

  def main(args: Array[String]): Unit = {
    run
  }
}
