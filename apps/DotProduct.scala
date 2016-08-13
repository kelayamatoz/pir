import pir.graph._
import pir.graph.{MetaPipeline => MetaPipe}
import pir.graph
import pir.codegen._
import pir.Design
import pir.PIRApp
import pir.PIRMisc._

/* Examp0ile PIR using block (User facing PIR)*/
object DotProduct extends PIRApp {
  def main(args: String*)(top:Top) = {
    val tileSize = Const("4i")
    val dataSize = ArgIn()
    val output = ArgOut()
    val A = MemoryController("A", TileLoad, OffChip())
    val B = MemoryController("B", TileLoad, OffChip())
    val tlAVec = Vector()
    val tlBVec = Vector()
    val innerScalar = Scalar()

    // Pipe.fold(dataSize by tileSize par outerPar)(out){ i =>
    val outer = ComputeUnit(name="outer", parent=top, tpe=MetaPipeline, deps=Nil){ implicit CU =>
      val es = CU.emptyStage
      CounterChain(name="i", CU.scalarIn(es, dataSize) by tileSize)
    }
    // b1 := v1(i::i+tileSize)
    val tileLoadA = TileTransfer(name="tileLoadA", parent=outer, memctrl=A, mctpe=TileLoad,
      deps=Nil, vec=tlAVec){ implicit CU =>
      val ic = CounterChain.copy(outer, "i")
      val it = CounterChain(name="it", Const("0i") until tileSize by Const("1i"))
      val s0::_ = Stages(1)
      val es = CU.emptyStage 
      val output = CU.scalarOut(s0, A.saddr)
      println(s"ic:${ic.counters} it:${it.counters}")
      Stage(s0, op1=CU.ctr(es, it(0)), op2=CU.ctr(es, ic(0)), op=FixAdd, result=output)
    }
    // b2 := v2(i::i+tileSize)
    val tileLoadB = TileTransfer(name="tileLoadB", parent=outer, memctrl=B, mctpe=TileLoad,
      deps=Nil, vec=tlBVec){ implicit CU =>
      val ic = CounterChain.copy(outer, "i")
      val it = CounterChain(name="it", Const("0i") until tileSize by Const("0i"))
      val s0::_ = Stages(1)
      val es = CU.emptyStage
      val output = CU.scalarOut(s0, B.saddr)
      Stage(s0, op1=CU.ctr(es, it(0)), op2=CU.ctr(es, ic(0)), op=FixAdd, result=output)
    }
    //Pipe.reduce(tileSize par innerPar)(Reg[T]){ii => b1(ii) * b2(ii) }{_+_}
    val inner = ComputeUnit (name="inner", parent=outer, tpe=Pipe, deps=List(tileLoadA, tileLoadB)) { implicit CU =>
      // StateMachines / CounterChain
      val ii = CounterChain(tileSize by Const("1i")) //Local
      val itA = CounterChain.copy(tileLoadA, "it")
      val itB = CounterChain.copy(tileLoadB, "it")


      val s0::s2::s1::_ = Stages(3)
      // SRAMs
      val sA = SRAM(name="sA", size=32, vec=tlAVec, readAddr=ii(0), 
                    banking=NoBanking(), doubleBuffer=true, writeCtr=itA(0), swapCtr=itA(0))
      val sB = SRAM(name="sB", size=32, vec=tlBVec, readAddr=ii(0), writeAddr=itB(0),
                    banking=NoBanking(), doubleBuffer=true, writeCtr=itB(0), swapCtr=itB(0))
      // Pipeline Stages 
      Stage(s0, op1=sA.load, op2=sB.load, op=FixMul, result=CU.reduce(s0))
      Stage(s2, op1=ii(0), op=Bypass, result=CU.wtAddr(s2, CU.wtAddr(sA)))
      val (sr, acc) = Stage.reduce(op=FixAdd, init=Const("0i"))
      Stage(s1, op1=acc, op=Bypass, result=CU.scalarOut(s1, innerScalar))
      //Last stage can be removed if CU.reduce and CU.scalarOut map to the same register
    }
    UnitComputeUnit (name="accum", parent=outer, deps=List(inner)) { implicit CU =>
      CounterChain(Const("1i") by Const("1i")) //Local
      val es = CU.emptyStage
      val s0::s1::_ = Stages(2)
      val accum = CU.accum(s0, init=Const("0i"))
      Stage(s0, op1=CU.scalarIn(es, innerScalar), op2=accum, op=FixAdd, accum)
      Stage(s1, op1=accum, op=Bypass, result=CU.scalarOut(s1, output))
    }
  }

}
