package pir
package pass

import pir.node._
import prism.graph._

trait BufferAnalyzer extends MemoryAnalyzer {
  def escape(dep:N, scope:N) = dep match {
    case dep:Memory => false 
    case dep:BufferWrite => false
    case dep:GlobalInput => false
    case dep:GlobalOutput => false
    case dep if dep.isDescendentOf(scope) => false
    case dep:Const => false
    case dep => true
  }

  def bufferInput(ctx:Context):Unit = dbgblk(s"bufferInput($ctx)"){
    ctx.descendents.foreach { deped => bufferInput(deped.as[PIRNode]) }
  }

  def bufferInput(deped:PIRNode):Seq[BufferRead] = {
    deped.localIns.flatMap { in => bufferInput(in) }
  }

  def bufferInput(in:Input, isFIFO:Option[Boolean]=None):Seq[BufferRead] = {
    in.connected.distinct.flatMap { out =>
      bufferInput(out.as[Output], in, isFIFOOpt=isFIFO)
    }
  }

  def bufferOutput(out:Output, isFIFO:Option[Boolean]=None):Seq[BufferRead] = {
    out.connected.distinct.flatMap { in =>
      bufferInput(out, in.as[Input], isFIFOOpt=isFIFO)
    }
  }

  private def bufferInput(depOut:Output, depedIn:Input, isFIFOOpt:Option[Boolean]):Option[BufferRead] = {
    val dep = depOut.src.as[PIRNode]
    val deped = depedIn.src.as[PIRNode]
    val depedCtx = deped.ctx.get
    if (escape(dep, depedCtx)) {
      val read = dbgblk(s"bufferInput(depOut=$dep.$depOut, depedIn=$deped.$depedIn)") {
        val depCtx = dep.ctx.get
        val isFIFO = isFIFOOpt.getOrElse(dep.getCtrl == deped.getCtrl)
        dbg(s"isFIFO=${isFIFO}")
        val (enq, deq) = compEnqDeq(dep.getCtrl, deped.getCtrl, isFIFO, depCtx, depedCtx)
        val write = within(depCtx, dep.getCtrl) {
          allocate[BufferWrite] { write => 
            write.data.traceInTo(depOut) &&
            write.done.traceInTo(enq)
          } {
            BufferWrite().data(depOut).done(enq)
          }
        }
        val read = within(depedCtx, deped.getCtrl) {
          allocate[BufferRead] { read => 
            read.in.traceInTo(write.out) &&
            read.done.traceInTo(deq)
          } {
            BufferRead(isFIFO).in(write.out).done(deq).banks(List(dep.getVec))
          }
        }
        swapConnection(depedIn, depOut, read.out)
        read
      }
      Some(read)
    } else None
  }

  def insertGlobalInput(global:GlobalContainer):Unit = {
    within(global) {
      global.depsFrom.foreach { case (out, ins) => insertGlobalInput(global, out, ins) }
    }
  }

  def insertGlobalInput(
    global:GlobalContainer,
    out:Output, 
    inputs:Seq[Input]
  ):Unit = {
    val ins = inputs.filterNot { _.src.isInstanceOf[GlobalInput] }
    if (ins.isEmpty) return
    dbgblk(s"insertGlobalInput($global, ${out.src}.${out}, $ins)"){
      out.src match {
        case dep:GlobalInput if dep.isDescendentOf(global) => dep
        case dep:GlobalInput => 
          ins.foreach { in => swapConnection(in, out, dep.in.T.out) }
          insertGlobalInput(global, dep.in.T.out, ins)
        case dep =>
          val gin = within(global) { 
            allocate[GlobalInput] { _.in.isConnectedTo(out) } { GlobalInput().in(out) } 
          }
          ins.foreach { in => swapConnection(in, out, gin.out) }
          gin
      }
    }
  }

  def insertGlobalOutput(global:GlobalContainer):Unit = {
    within(global) {
      global.depedsTo.foreach { case (out, ins) => 
        insertGlobalOutput(global, out, ins)
      }
    }
  }

  def insertGlobalOutput(
    global:GlobalContainer,
    out:Output, 
    ins:Seq[Input]
  ):GlobalOutput = dbgblk(s"insertGlobalOutput($global, ${out.src}.${out}, $ins)"){
    out.src match {
      case depedFrom:GlobalOutput if depedFrom.isDescendentOf(global) => depedFrom
      case depedFrom:GlobalOutput => throw PIRException(s"impossible case insertGlobalOutput")
      case depedFrom =>
        val gout = within(global) { 
          allocate[GlobalOutput]{ _.in.isConnectedTo(out) } { GlobalOutput().in(out) }
        }
        ins.foreach { in => swapConnection(in, out, gout.out) }
        gout
    }
  }

  def insertGlobalIO(global:GlobalContainer) = {
    insertGlobalInput(global)
    insertGlobalOutput(global)
  }

  def bound(visitFunc:N => List[N]):N => List[N] = { n:N =>
    visitFunc(n).filter{ 
      case x:Memory => false
      case x:HostWrite => false
      case x:LocalInAccess => false
      //case x:LocalOutAccess => // prevent infinate loop in case of cycle
        //val from = x.in.T
        //from != n && !from.isDescendentOf(n)
      case x:GlobalInput => false
      case x:GlobalOutput => false
      case _ => true
    }
  }

  def getDeps(
    x:PIRNode, 
    visitFunc:N => List[N] = visitGlobalIn _
  ):Seq[PIRNode] = dbgblk(s"getDeps($x)"){
    var deps = x.accum(visitFunc=cover[Controller](bound(visitFunc)))
    deps = deps.filterNot(_ == x)
    if (compiler.hasRun[DependencyDuplication]) {
      val ctrlers = deps.collect { case ctrler:Controller => ctrler }
      val leaf = assertOneOrLess(ctrlers.flatMap { _.leaves }.distinct, 
        s"leaf of ${ctrlers}")
      dbg(s"leaf=$leaf")
      leaf.foreach { leaf =>
        if (leaf != x) {
          deps ++= leaf +: (leaf.descendents++getDeps(leaf, visitFunc))
          deps = deps.distinct
        }
      }
    }
    deps.as[List[PIRNode]]
  }


}

class BufferInsertion(implicit compiler:PIR) extends PIRTraversal with SiblingFirstTraversal with UnitTraversal with BufferAnalyzer {
  val forward = false

  override def visitNode(n:N) = n match {
    case n:Context => bufferInput(n)
    case n => super.visitNode(n)
  }
}

