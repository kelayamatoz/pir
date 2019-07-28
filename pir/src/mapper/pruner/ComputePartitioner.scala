package pir
package mapper

import pir.node._
import pir.pass._
import prism.graph._
import prism.collection.immutable._

trait ComputePartitioner extends CUPruner {

  lazy val schedular = config.splitAlgo match {
    case "BFS" => new PIRTraversal with BFSTopologicalTraversal with Schedular { val forward = false }
    case "DFS" => new PIRTraversal with DFSTopologicalTraversal with Schedular { val forward = false }
  }

  def split[T](k:T, vcost:List[Cost[_]]):List[T] = dbgblk(s"split($k)") {
    val kcost = getCosts(k)
    dbg(s"kcost=$kcost")
    (k match {
      case k:ComputeContainer =>
        val ctxs:List[Context] = k.collectDown[Context]().flatMap { ctx => split(ctx, vcost) }
        val globals = ctxs.map { ctx =>
          within(pirTop) {
            val global = ComputeContainer()
            val gouts = ctx.depeds().collect { case gout:GlobalOutput => gout }
            swapParent(ctx, global)
            gouts.foreach { gout => swapParent(gout, global) }
            global
          }
        }
        globals.foreach { insertGlobalIO }
        removeNodes(k.descendentTree)
        globals
      case k:Context =>
        val scope = k.children.filter { include }
        val part = new Partition(scope)
        val parts = split(part, vcost)
        dbg(s"partitions=${parts.size}")
        val ctxs = within(k.global.get, k.ctrl.get) {
          parts.map { case part@Partition(scope) =>
            val ctx = Context()
            dbg(s"Create $ctx for $part")
            scope.foreach { n => swapParent(n, ctx) }
            ctx
          }
        }
         // need to run in two pass to avoid duplicated allocation
        alias ++= ctxs.map { ctx => (ctx, k) }
        ctxs.foreach { ctx => bufferInput(ctx) }
        alias.clear
        dupDeps(ctxs, from=Some(k))
        (part::parts).foreach { removeCache }
        removeNodes(k.descendentTree)
        ctxs.foreach { ctx => if (ctx.ctrlers.isEmpty) ctx.streaming := true }
        ctxs
      case k:Partition =>
        if (fit(kcost, vcost)) List(k)
        else {
          val nodes = schedular.scheduleScope(k.scope)
          //dbg(s"schedule:")
          //nodes.foreach { n => dbg(s"$n") }
          val (head, tail) = nodes.splitAt(nodes.size/2)
          split(new Partition(head), vcost) ++ split(new Partition(tail),vcost)
        }
    }).as[List[T]]
  }

  val alias = scala.collection.mutable.Map[Context,Context]()

  override def valid(ctrl:ControlTree, ctx:Context):Output[PIRNode] = {
    super.valid(ctrl, alias.get(ctx).getOrElse(ctx))
  }

  def include(n:PIRNode) = n match {
    case n:OpNode => true
    case n:LocalInAccess => true
    //case n:LocalOutAccess => true // Not include read so they can be duplicated at each partition
    case n => false
  }

  override protected def compCost(x:Any, ct:ClassTag[_]) = {
    switch[InputCost](x,ct) { 
      case x: Partition => 
        val deps = x.deps.filter { d =>
          include(d) || d.isInstanceOf[LocalOutAccess]
        }.distinct.toList
        dbg(s"deps=$deps")
        val ins = deps
        val (vins, sins) = ins.partition { _.getVec > 1 }
        InputCost(sins.size, vins.size).scheduledBy(x.scope.size)
    } orElse switch[OutputCost](x,ct) { 
      case x: Partition => 
        val depedFroms = x.depedsTo.keys.toSeq
        dbg(s"depedFroms=${depedFroms.toList}")
        val outs = depedFroms
        val (vouts, souts) = outs.partition { _.getVec > 1 }
        OutputCost(souts.size, vouts.size).scheduledBy(x.scope.size)
    } orElse switch[StageCost](x,ct) { 
      case x: Partition => 
        StageCost(x.scope.collect{ case op:OpNode => op }.size)
    } getOrElse super.compCost(x, ct)
  }

}

class Partition(val scope:List[PIRNode]) {
  override def toString = s"${super.toString}(${scope.size})"
  def deps:Seq[PIRNode] = {
    val descendents = scope.flatMap { n => n.descendentTree }
    val edges = descendents.toIterator.flatMap { _.localEdges }
    val ins = edges.collect { case i:Input[PIRNode] => i }
    ins.flatMap { in =>
      in.connected.map { _.src }
      .filterNot { descendents.contains } 
    }.toSeq.distinct
  }

  def depedsTo:Map[PIRNode, Seq[PIRNode]] = {
    val descendents = scope.flatMap { n => n.descendentTree }
    val edges = descendents.toIterator.flatMap { _.localEdges }
    val outs = edges.collect { case i:Output[PIRNode] => i }
    outs.flatMap { out =>
      out.connected.map { _.src }
      .filterNot { descendents.contains } 
      .map { deped => (deped, out.src) } 
    }.toSeq.groupBy { _._2 }.mapValues { _.map { _._1 } }
  }

}
object Partition {
  def unapply(x:Partition):Option[List[PIRNode]] = Some(x.scope)
}

