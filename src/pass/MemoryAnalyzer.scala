package pir.pass
import pir.graph._
import pir._
import pir.util._
import pir.util.misc._
import pir.exceptions._
import pir.codegen.Logger

import scala.collection.mutable._

class MemoryAnalyzer(implicit design: Design) extends Pass with Logger {
  def shouldRun = true 
  import pirmeta._

  override lazy val stream = newStream(s"MemoryAnalyzer.log")

  def analyzeStageOperands(cu:MemoryPipeline) = {
    (cu.wtAddrStages ++ cu.rdAddrStages).foreach { st =>
      st match {
        case st:WAStage => forWrite(st) = true
        case st:RAStage => forRead(st) = true
      }
      st.fu.foreach { fu =>
        fu.operands.foreach { oprd =>
          (oprd.from.src, st) match {
            case (p:Counter, st:WAStage) => forWrite(p) = true
            case (p:Counter, st:RAStage) => forRead(p) = true
            case (p:ScalarMem, st:WAStage) => forWrite(p) = true
            case (p:ScalarMem, st:RAStage) => forRead(p) = true
            case (p, st) =>
          }
        }
      }
    }
    if (cu.mem.writePort.isConnected) {
      cu.mem.writePort.from.src match {
        case fifo:VectorFIFO => forWrite(fifo) = true
      }
    } else {
      warn(s"${cu.mem} in $cu's writePort is not connected!")
    }
  }

  def analyzeCounters(cu:MemoryPipeline) = {
    cu.rdAddrStages.foreach { stage =>
      stage.fu.foreach { fu =>
        fu.operands.foreach { oprd => 
          oprd.from.src match {
            case ctr:Counter => forRead(ctr) = true
            case PipeReg(_, CtrPR(ctr)) => forRead(ctr) = true
            case _ =>
          }
        }
      }
    }
    if (cu.mem.readAddr.isConnected) {
      cu.mem.readAddr.from.src match {
        case ctr:Counter => forRead(ctr) = true
        case fu:FuncUnit =>
      }
    } else {
      warn(s"${cu.mem} in $cu's readAddr is not connected!")
    }
    cu.wtAddrStages.foreach { stage =>
      stage.fu.foreach { fu =>
        fu.operands.foreach { oprd => 
          oprd.from.src match {
            case ctr:Counter => forWrite(ctr) = true
            case PipeReg(_, CtrPR(ctr)) => forWrite(ctr) = true
            case _ =>
          }
        }
      }
    }
    if (cu.mem.writeAddr.isConnected) {
      cu.mem.writeAddr.from.src match {
        case ctr:Counter => forWrite(ctr) = true
        case fu:FuncUnit =>
      }
    } else {
      warn(s"${cu.mem} in $cu's writeAddr is not connected!")
    }
  }

  def analyzeCChain(cu:MemoryPipeline) = {
    cu.cchains.foreach { cc =>
      if (cc.counters.exists(cc => forWrite(cc))) {
        forWrite(cc) = true
        cc.counters.foreach(ctr => forWrite(ctr) = true)
      } else if (cc.counters.exists(cc => forRead(cc))) {
        forRead(cc) = true
        cc.counters.foreach(ctr => forRead(ctr) = true)
      }
    }
  }

  def analyzeScalarBufs(cu:MemoryPipeline) = {
    cu.cchains.foreach { cc =>
      cc.counters.foreach { ctr =>
        List(ctr.min, ctr.max, ctr.step).map(_.from.src).foreach {
          case mem:ScalarMem => 
            if (forRead(ctr)) {
              forRead(mem) = true
            }
            if (forWrite(ctr)) {
              forWrite(mem) = true
            }
          case _ =>
        }
      }
    }
  }

  def analyzeAddrCalc(cu:ComputeUnit) = {
    val readCCs = cu.cchains.filter { cc => forRead(cc) }
    dprintln(s"readCCs:$readCCs")
    readCChainsOf(cu) = fillChain(cu, sortCChains(readCCs))
    val writeCCs = cu.cchains.filter { cc => forWrite(cc) }
    dprintln(s"writeCCs:$writeCCs")
    writeCChainsOf(cu) = fillChain(cu, sortCChains(writeCCs))
    val compCCs = cu.cchains.filter { cc => !forRead(cc) && !forWrite(cc) }
    dprintln(s"compCCs:$compCCs")
    compCChainsOf(cu) = fillChain(cu, sortCChains(compCCs))
  }

  addPass {
    design.top.memCUs.foreach { cu =>
      analyzeStageOperands(cu)
      analyzeCounters(cu)
      analyzeCChain(cu)
      analyzeScalarBufs(cu)

      emitBlock(s"$cu") {
        cu.stages.foreach { st =>
          dprintln(s"$st forRead=${forRead(st)} forWrite=${forWrite(st)}")
        }
        cu.mems.foreach { mem => 
          dprintln(s"$mem forRead=${forRead(mem)} forWrite=${forWrite(mem)}")
        }
        cu.cchains.foreach { cchain =>
          cchain.counters.foreach { ctr =>
            dprintln(s"$ctr forRead=${forRead(ctr)} forWrite=${forWrite(ctr)}")
          }
          dprintln(s"$cchain forRead=${forRead(cchain)} forWrite=${forWrite(cchain)}")
        }
      }
    }
  }

  addPass(canRun=design.multiBufferAnalyzer.hasRun, 1) {
    design.top.compUnits.foreach { cu =>
      analyzeAddrCalc(cu)
      dprintln(s"readCChains:[${readCChainsOf(cu).mkString(",")}]")
      dprintln(s"writeCChains:[${writeCChainsOf(cu).mkString(",")}]")
      dprintln(s"compCCs:[${compCChainsOf(cu).mkString(",")}]")
    }
  }

}
