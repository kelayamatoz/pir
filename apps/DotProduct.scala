import pir.graph._
import pir.graph.{MemoryController => MemCtrl, MetaPipeline => MetaPipe}
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
    val A = OffChip("A")
    val B = OffChip("B")
    val tileA = Vector("tileA")
    val tileB = Vector("tileB")

    // Pipe.fold(dataSize by tileSize par outerPar)(out){ i =>
    val outer = ComputeUnit(name="outer", parent=top, tpe=MetaPipeline){ implicit CU =>
      CounterChain(name="i", CU.scalarIn(dataSize) by tileSize)
    }
    // b1 := v1(i::i+tileSize)
    val tileLoadA = MemCtrl (name="tileLoadA", parent=outer, offchip=A, mctpe=TileLoad){ implicit CU =>
      val ic = CounterChain.copy(outer, "i")
      val it = CounterChain(name="it", Const(0) until tileSize by Const(1))
      val s0::s1::_ = Stages(2)
      Stage(s0, op1=it(0), op2=ic(0), op=FixAdd, result=CU.scalarOut(s0, A.readAddr))
      Stage(s1, op1=CU.vecIn(A.read), op=Bypass, result=CU.vecOut(s1, tileA))
    }
    // b2 := v2(i::i+tileSize)
    val tileLoadB = MemCtrl (name="tileLoadB", parent=outer, offchip=B, mctpe=TileLoad){ implicit CU =>
      val ic = CounterChain.copy(outer, "i")
      val it = CounterChain(name="it", Const(0) until tileSize by Const(1))
      val s0::s1::_ = Stages(2)
      Stage(s0, op1=it(0), op2=ic(0), op=FixAdd, result=CU.scalarOut(s0, B.readAddr))
      Stage(s1, op1=CU.vecIn(B.read), op=Bypass, result=CU.vecOut(s1, tileB))
    }
    //Pipe.reduce(tileSize par innerPar)(Reg[T]){ii => b1(ii) * b2(ii) }{_+_}
    ComputeUnit (name="inner", parent=outer, tpe=Pipe) { implicit CU =>
      
      // StateMachines / CounterChain
      val ii = CounterChain(tileSize by Const(1l)) //Local
      val itA = CounterChain.copy(tileLoadA, "it")
      val itB = CounterChain.copy(tileLoadB, "it")

      val s0::s1::s2::_ = Stages(3)
      // SRAMs
      val A = SRAM(size=32, vec=tileA, readAddr=ii(0), writeAddr=itA(0))
      val B = SRAM(size=32, vec=tileB, readAddr=ii(0), writeAddr=itB(0))
      // ScalarBuffers
      val out = ScalarOut(output)

      // Pipeline Stages 
      Stage(s0, op1=A.load, op2=B.load, op=FixMul, result=CU.reduce(s0))
      Stage.reduce(s1, op=FixAdd) 
      Stage(s2, op1=CU.reduce(s1), op=Bypass, result=CU.scalarOut(s2, out)) 
      //Last stage can be removed if CU.reduce and CU.scalarOut map to the same register
    }
  }

}
