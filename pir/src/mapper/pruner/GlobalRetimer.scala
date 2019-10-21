package pir
package mapper

import pir.node._
import pir.pass._
import prism.graph._
import spade.param._

trait GlobalRetimer extends PIRTransformer {
  private val traversal = new PIRTraversal with BFSTopologicalTraversal with UnitTraversal { 
    val forward = false // Reverse to schedule nodes as late as possible. Assume small fanins from external
    override def visitIn(n:N) = visitLocalIn(n)
    override def visitOut(n:N) = visitLocalOut(n)
    override def visitNode(n:N, prev:Unit) = {
      val depeds = visitOut(n).filter(withinScope)
      val depedDelays = depeds.map { deped => deped.delay.get }
      val delay = depedDelays.minOptionBy { i => i }.map { min => min - numStage }.getOrElse(0)
      n.delay := delay
    }
    var numStage:Int = 0
  }

  def retimeGlobal (scope:List[GlobalContainer], numStage:Int):List[GlobalContainer] = 
    if (!config.enableGlobalRetiming) Nil else dbgblk(s"retimeGlobal") {
      traversal.resetTraversal
      traversal.numStage = numStage
      traversal.traverseScope(scope, ())
      val externDelay = scope.toStream.map { _.delay.get }.min - numStage
      val fifoDepth = assertUnify(spadeParam.traceIn[FIFOParam], "fifoParam") { _.depth }.get
      val sramParam = assertIdentical(spadeParam.traceIn[PMUParam], "PMUParam").get.sramParam
      within(pirTop) {
        // A map between outputs (internal and external) to internal inputs
        val outIns = scope.map { n => 
          n.depsFrom.filter { case (out, ins) => 
            needRetime(out, n, externDelay, fifoDepth, numStage)
          }
        }.reduce { sumMap(_,_) { _ ++ _ } }
        // Inserting retiming ops for each output
        val delayOps = outIns.map { case (out, ins) =>
          out -> retimeOutput(out, ins, externDelay, fifoDepth, numStage, sramParam)
        }.toMap
        // Put retiming ops into retiming containers. Share container for outputs from the same context
        delayOps.groupBy { case (out, delayOps) => out.src.global.get
        }.flatMap { case (dep, group) =>
          val delayOps = group.flatMap { case (out, delayOps) => delayOps }
          val layers = delayOps.groupBy { _.delay.get }.toSeq.sortBy { _._1 }
          val retimeGlobs = layers.map { case (delay, ops) =>
            assertUnify(ops, s"DelayOp type") { op => op.getClass }
            val retimeGlob = ops.head match {
              case op:Delay => stage(ComputeContainer())
              case op:ScratchpadDelay => stage(MemoryContainer())
            }
            val retimeCtx = within(retimeGlob,dep.collectFirstChild[Context].get.getCtrl) { stage(Context().streaming(true)) }
            ops.foreach { op =>
              swapParent(op, retimeCtx)
              op.in.singleConnected.get.src match {
                case write:BufferWrite =>
                  swapConnection(op.in, write.out, write.data.singleConnected.get)
                  bufferInput(op.in).foreach { buffer =>
                    transferLocalAccess(write, buffer.inAccess)
                  }
                case delay:DelayOp => bufferInput(op.in)
              }
            }
            //breakPoint(s"add $retimeGlob")
            retimeGlob
          }.toList
          delayOps.foreach { op =>
            op.out.connected.foreach {
              case in@InputField(read:BufferRead, _) =>
                val ins = read.out.connected
                swapOutput(read.out, op.out)
                ins.foreach { in =>
                  bufferInput(in).foreach { bufferRead =>
                    transferLocalAccess(read, bufferRead)
                  }
                }
              case _ =>
            }
            op.delay.reset
          }
          retimeGlobs
        }.toList
      }
    }

  // Do a filter on ins to filter out inputs that requires retiming
  def needRetime(out:Output[PIRNode], deped:GlobalContainer, externDelay:Int, fifoDepth:Int, numStage:Int):Boolean = {
    out.src match {
      case write:BufferWrite if !write.isFIFO => return false
      case write:TokenWrite => return false
      case _ =>
    }
    val depDelay = out.src match {
      case src:DelayOp => src.delay.get
      case src => src.global.get.delay.v.getOrElse(externDelay)
    }
    val diff = deped.delay.get - depDelay + numStage
    diff > fifoDepth
  }

  def retimeOutput(out:Output[PIRNode], ins:Vector[Input[PIRNode]], externDelay:Int, fifoDepth:Int, numStage:Int, sramParam:SRAMParam):List[DelayOp] = {
    val depDelay = out.src match {
      case src:DelayOp => src.delay.get
      case src => src.global.get.delay.v.getOrElse(externDelay)
    }
    val toretime = ins.filter { in => 
      val deped = in.src.global.get
      needRetime(out, deped, externDelay, fifoDepth, numStage)
    }
    if (toretime.isEmpty) return Nil
    val depedDelay = toretime.toStream.map { _.src.global.get.delay.get }.min
    val delayDiff = depedDelay - depDelay
    dbg(s"delayDiff=$delayDiff")
    val sramDepth = sramParam.capacity / out.getVec
    val retimeDepth = if (delayDiff > fifoDepth) sramDepth else fifoDepth
    // The earliest time to schedule this retime node is after its previous node and before any used
    // node
    val opdelay = math.min(depDelay + retimeDepth, depedDelay - 1)
    val d = within(out.src.getCtrl) { 
      if (retimeDepth == fifoDepth) {
        stage(Delay(retimeDepth).in(out).delay(opdelay))
      } else {
        stage(ScratchpadDelay(retimeDepth).in(out).delay(opdelay))
      }
    }
    toretime.foreach { in =>
      swapConnection(in, out, d.out)
    }
    d :: retimeOutput(d.out, toretime, externDelay, fifoDepth, numStage, sramParam)
  }

}
