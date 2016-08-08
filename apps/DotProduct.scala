import pir.graph._
import pir.graph.{MetaPipeline => MetaPipe}
import pir.graph
import pir.codegen._
import pir.Design
import pir.PIRApp
import pir.PIRMisc._

/* Example PIR using block (User facing PIR)*/
object DotProduct extends PIRApp {
  def main(args: String*)(top:Top) = {
    val tileSize = Const(4l)
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
      val it = CounterChain(name="it", Const(0) until tileSize by Const(1))
      val s0::_ = Stages(1)
      val es = CU.emptyStage 
      val output = CU.scalarOut(s0, A.saddr)
      Stage(s0, op1=CU.ctr(es, it(0)), op2=CU.ctr(es, ic(0)), op=FixAdd, result=output)
    }
    // b2 := v2(i::i+tileSize)
    val tileLoadB = TileTransfer(name="tileLoadB", parent=outer, memctrl=B, mctpe=TileLoad,
      deps=Nil, vec=tlBVec){ implicit CU =>
      val ic = CounterChain.copy(outer, "i")
      val it = CounterChain(name="it", Const(0) until tileSize by Const(1))
      val s0::_ = Stages(1)
      val es = CU.emptyStage
      val output = CU.scalarOut(s0, B.saddr)
      Stage(s0, op1=CU.ctr(es, it(0)), op2=CU.ctr(es, ic(0)), op=FixAdd, result=output)
    }
    //Pipe.reduce(tileSize par innerPar)(Reg[T]){ii => b1(ii) * b2(ii) }{_+_}
    val inner = ComputeUnit (name="inner", parent=outer, tpe=Pipe, deps=List(tileLoadA, tileLoadB)) { implicit CU =>
      // StateMachines / CounterChain
      val ii = CounterChain(tileSize by Const(1l)) //Local
      val itA = CounterChain.copy(tileLoadA, "it")
      val itB = CounterChain.copy(tileLoadB, "it")

      val s0::s1::_ = Stages(2)
      // SRAMs
      val sA = SRAM(size=32, vec=tlAVec, readAddr=ii(0), writeAddr=itA(0))
      val sB = SRAM(size=32, vec=tlBVec, readAddr=ii(0), writeAddr=itB(0))
      // Pipeline Stages 
      Stage(s0, op1=sA.load, op2=sB.load, op=FixMul, result=CU.reduce(s0))
      val (sr, acc) = Stage.reduce(op=FixAdd, init=Const(0l))
      Stage(s1, op1=acc, op=Bypass, result=CU.scalarOut(s1, innerScalar))
      // Testing -----
      val s2::s3::_ = Stages(2)
      val t1 = CU.temp(s2) 
      Stage(s2, op1=CU.load(s1, sA), op2=CU.load(s1, sB), op=FixAdd, result=t1)
      // Testing------
      Stage(s3, op1=t1, op2=CU.load(s2, sA), op=FixAdd, result=CU.vecOut(s3, Vector()))
      //Last stage can be removed if CU.reduce and CU.scalarOut map to the same register
    }
    UnitComputeUnit (name="accum", parent=outer, deps=List(inner)) { implicit CU =>
      val es = CU.emptyStage
      val s0::s1::_ = Stages(2)
      val accum = CU.accum(s0, init=Const(0))
      Stage(s0, op1=CU.scalarIn(es, innerScalar), op2=accum, op=FixAdd, accum)
      Stage(s1, op1=accum, op=Bypass, result=CU.scalarOut(s1, output))
    }
  }

}
