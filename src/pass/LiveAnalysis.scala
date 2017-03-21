package pir.pass
import pir.graph._
import pir._
import pir.util._
import pir.exceptions._
import pir.util.misc._

import scala.collection.mutable.Set
import scala.collection.immutable.{Set => ISet}
import scala.collection.mutable.Map

class LiveAnalysis(implicit val design: Design) extends Pass {
  def shouldRun = true 

  val pirmeta:PIRMetadata = design
  import pirmeta._

  override def traverse = {
    design.top.innerCUs.foreach { implicit cu =>
      // Uses in sram and counter
      updatesPrim(cu)
      // EmptyStage
      //val empty = 
        //if (cu.wtAddrStages.size > 0) { stageAnalysis(List(cu.emptyStage)); cu.wtAddrStages.last.last}
        //else { cu.emptyStage }
      val empty = cu.emptyStage
      // Write Addr Stages
      cu.wtAddrStages.foreach { was => stageAnalysis(was)(cu) }
      val locals = empty::cu.localStages.toList
      stageAnalysis(locals)(cu)
      // Interference Graph
      infAnalysis(cu)
    }
  } 

  private def stageAnalysis(stages:List[Stage])(implicit cu:ComputeUnit) = {
    updateStages(stages)
    liveness(stages) 
    checkLiveness(stages)
    connectPRs(stages)
  }

  private def updatesPrim(implicit cu:ComputeUnit) = {
    cu match {
      case icu:InnerController =>
        icu.mems.foreach { mem =>
          mem match { case mem:SRAMOnRead => if (mem.readAddr.isConnected) addLiveOut(mem.readAddr); case _ => }
          mem match { case mem:SRAMOnWrite => if (mem.writeAddr.isConnected) addLiveOut(mem.writeAddr); case _ => }
          if (mem.writePort.isConnected) addLiveOut(mem.writePort)
        }
      case _ =>
    }
    cu.cchains.foreach { cc => 
      cc.counters.foreach { ctr =>
        addLiveOut(ctr.min)
        addLiveOut(ctr.max)
        addLiveOut(ctr.step)
      }
    }
  }
  
  private def updateStages(stages:List[Stage])(implicit cu:ComputeUnit) = {
    stages.zipWithIndex.foreach { case (s, i) =>
      s.fu.foreach(_.operands.foreach ( opd => addOpd(opd, s, stages) ))
      s.fu.foreach(f => addRes(f.out, i, stages))
    }
  }

  private def addLiveOut(port:InPort) (implicit cu:ComputeUnit):Unit = {
    port.from.src match {
      case p@PipeReg(stage, reg) => stage.addLiveOut(reg)
      case _ => 
    }
  }

  private def addOpd(port:InPort, stage:Stage, stages:List[Stage]) (implicit cu:ComputeUnit) = {
    port.from.src match {
      case pr@PipeReg(s, reg) => 
        if (stage == s) s.addUse(reg) // If is current stage, is a usage
        else s.addLiveOut(reg) // Forwarding path, a branch out, essentially or next liveIns to get current liveOut
      case pm => {
        if (stage.isInstanceOf[LocalStage] && stage==stages.head && !pm.isInstanceOf[SRAM]) {
          throw PIRException(s"Local stage ${stage} of ${stage.ctrler} excepts the first stage cannot directly reference ${pm} as operand")
        }
        pm match {
          case n:ScalarIn => stage.addDef(cu.scalarInPR(n))
          case _ =>
        }
        cu match {
          case cu:InnerController => pm match {
            case n:VecIn => stage.addDef(cu.vecInPR(n))
            case n:SRAM => stage.addDef(cu.loadPR(n)) 
            case n:Counter => stage.addDef(cu.ctrPR(n))
            case _ =>
          }
        }
      }
    } 
  }


  private def addRes(res:OutPort, i:Int, stages:List[Stage])(implicit cu:ComputeUnit) = {
    val stage = stages(i)
    res.to.foreach { _ match {
        case p:InPort if p.src.isInstanceOf[PipeReg] =>
          val PipeReg(s, reg) = p.src
          s.addDef(reg)
          reg match {
            case (_:WtAddrPR | _:StorePR | _:VecOutPR | _:ScalarOutPR) => stages.last.addLiveOut(reg)
            case _ => 
          }
        case p:RdAddrInPort =>
          val sram = p.src
          val icu = cu.asInstanceOf[InnerController]
          // Loaded value are forwarded one stage after readAddr calc
          if (stage!=stages.last) {
            val next = stages(i+1)
            next.addDef(icu.loadPR(sram))
            if (next.liveOuts.exists{ 
              case LoadPR(_,sm) if sm==sram => true
              case _ => false
            }) {
            }
          }
        case _ =>
      }
    }
  }

  private def compLiveIn(liveOuts:ISet[Reg], defs:ISet[Reg], uses:ISet[Reg]):ISet[Reg] = 
    (liveOuts -- defs ++ uses)

  private def liveness(stages:List[Stage]) = {
    for (i <- stages.size-1 to 0 by -1){
      val s = stages(i)
      s.liveOuts = if (s==stages.last) s.liveOuts else s.liveOuts ++ stages(i+1).liveIns
      s.liveIns = compLiveIn(s.liveOuts, s.defs.toSet, s.uses.toSet)
      if (s == stages.head) { // Empty stage. Add forwarding path to liveIn variables 
        s.liveIns.foreach{ r => 
          r match {
            case (_:CtrPR | _:VecInPR | _:ScalarInPR ) => s.addDef(r)
            case (_:LoadPR ) => 
              throw PIRException(s"Cannot load value in empty stage. ${r} in ${r.ctrler}") 
            case _ => throw PIRException(s"Register ${r} in ${r.ctrler} has no definition!")
          }
        }
        s.liveIns = compLiveIn(s.liveOuts, s.defs.toSet, s.uses.toSet)
      }
      s.liveIns.foreach{ r => 
        // If there's no def on loaded value, check if sram's readAddr is directly connected to 
        // the counter. If it is, forward loaded value to the first local stage
        r match {
          case r@LoadPR(_,mem) =>
            mem match {
              case mem:SRAMOnRead =>
                mem.readAddr.from.src match {
                  case _:Counter => stages(1).addDef(r)
                  case fu:FuncUnit if (indexOf(fu.stage) == indexOf(s) - 1) =>
                    throw PIRException(s"The stage right after address calculation stage should load from sram directly ${s} in ${s.ctrler}")
                  case fu:FuncUnit if (indexOf(fu.stage) >= indexOf(s)) =>
                    throw PIRException(s"Loading stage is after address calculation stage loading:${s}, address calculation stage:${fu.stage} in ${s.ctrler}")
                  case fu:FuncUnit => fu.stage.addDef(r)
                  case r => throw PIRException(s"Unknown producer of ${mem} readAddr $r")
                }
              case mem:FIFOOnRead => if (s == stages(1)) s.addDef(r)
              case mem:ScalarMem => if (s == stages(1)) s.addDef(r)
            }
          case _ =>
        }
      }
      s.liveIns = compLiveIn(s.liveOuts, s.defs.toSet, s.uses.toSet)
      // Kill live in of accum due to initial value
      var accums = s.uses.collect {case r:AccumPR => r}
      s.liveIns = s.liveIns -- accums
      // Hack: Force accum to live out but not live in in the next stage when there are multiple
      // defs (assume the parallel def might use accum). Works because liveness is backward
      accums = s.defs.collect {case r:AccumPR => r}
      if (s.defs.size > 1)
        accums.foreach { accum => if (!s.liveOuts.contains(accum)) s.liveOuts += accum }
    }
  }

  private def checkLiveness(stages:List[Stage]) = {
    stages.foreach { s =>
      if ((s.liveIns -- s.liveOuts).size!=0) {
        throw PIRException(s"ctrler: ${s.ctrler}, liveIn is not contained by liveOut! stage:${s} liveIns:${s.liveIns} liveOuts:${s.liveOuts}")
      }
    }
  }

  // If reg:
  // if liveOut, register is enabled
  // if liveIn, register is passed through from previous reg
  // if defs but not defined by ALU, forwarding value to pipereg
  // assert liveOut but not liveIn and no def
  private def connectPRs(stages:List[Stage])(implicit cu:ComputeUnit) = {
    for (i <- 0 until stages.size) {
      val stage = stages(i)
      stage.liveOuts.foreach { reg =>
        val pr = cu.pipeReg(stage, reg)
        if (!pr.in.isConnected) {
          if (stage.defs.contains(reg)) {
            if (!stage.fu.isDefined || !stage.fu.get.defines(reg)) {
              reg match {
                case CtrPR(_, ctr) => pr.in.connect(ctr.out) 
                case LoadPR(_, sram) => pr.in.connect(sram.readPort) 
                case VecInPR(_, vecIn) => 
                  val head = cu.pipeReg(stages.head, reg)
                  if (stage!=stages.head) {
                    pr.in.connect(head)
                  }
                  if (!head.in.isConnected) head.in.connect(vecIn.out)
                case ScalarInPR(_, scalarIn) =>
                  val head = cu.pipeReg(stages.head, reg)
                  if (stage!=stages.head) {
                    pr.in.connect(head)
                  }
                  if (!head.in.isConnected) head.in.connect(scalarIn.out) 
                case _ => throw PIRException(s"Cannot forward reg type: ${reg}")
              }
            }
          } else if (stage.liveIns.contains(reg)) {
            val pre = stages(i-1)
            val prePr = cu.pipeReg(pre, reg)
            pr.in.connect(prePr)
          } else {
            throw PIRException(s"what's going on")
          } 
        }
        if (stage==stages.last) { // Last stage
          if (!pr.out.isConnected) {
            reg match {
              case StorePR(_, sram) => sram.wtPort(pr.out)
              case p:VecOutPR => p.vecOut.in.connect(pr.out)
              case ScalarOutPR(_, scalarOut) => scalarOut.in.connect(pr.out)
              case r:WtAddrPR => r.waPort.connect(pr.out)
              case _ => throw PIRException(s"Unknown live out variable ${reg} in last stage ${stage}!")
            }
          }
        }
      }
    }
  }

  private def infAnalysis(cu:ComputeUnit):Unit = {
    val stages = cu.stages
    stages.foreach { s =>
      s.liveOuts.foreach { r =>
        if (!cu.infGraph.contains(r)) cu.infGraph += (r -> Set.empty)
        // register doesn't interfere with co-def from the same source
        // e.g. FU writes to 2 registers
        val sameSrcDefs = s.liveOuts.filter { lo =>
          if (s.get(r).in.src == s.get(lo).in.src) true
          else false
        }
        cu.infGraph(r) ++= (s.liveOuts -- sameSrcDefs)
      }
    }
  }

  override def finPass = {
    endInfo("Finishing Liveness Analysis")
  }

}
