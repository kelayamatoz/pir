package pir.pass
import pir.graph._
import pir.Design
import pir.Config
import pir.exceptions._

import scala.collection.mutable.Set

trait Traversal extends Pass {
  implicit def design: Design

  val visited = Set[Node]()
  override def reset {
    super.reset
    visited.clear()
  }
  
  def traverse(node: Node) = {
    visitNode(node)
  }
  
  override def traverse = traverse(design.top)

  def visitNode(node: Node) : Unit = {
    assert(!visited.contains(node), s"Revisiting visited node ${node}! visitedNodes:${visited}")
    visitNodeNoCheck(node)
  }
  /* Depth first search traversal on node and their fields */
  def visitNodeNoCheck(node: Node) : Unit = {
    node match {
      case n:Controller => 
        n.sins.foreach { si => visitNode(si) }
        n.vinMap.foreach { case (vector, vi) => visitNode(vi) }
        n.souts.foreach { so => visitNode(so) }
        n.vouts.foreach { vo => visitNode(vo) }
        n match {
        case c:Top => 
          c.compUnits.foreach(n => visitNode(n))
        case c:ComputeUnit => {
          c.cchains.foreach { cc => visitNode(cc) }
          c.mems.foreach { s => visitNode(s) }
          c.stages.foreach { s => visitNode(s) }
          c.ctrlBox.tokenBuffers.foreach { case (dep, t) => visitNode(t) }
          c.ctrlBox.creditBuffers.foreach { case (deped, c) => visitNode(c) }
        }
      } 
      case n:Primitive => n match {
        case p:CounterChain => p.counters.foreach(c => visitNode(c))
        case p:OnChipMem => 
          //p match {
            //case f:FIFOOnWrite => visitNode(f.dummyCtr)
            //case _ =>
          //}
        case p:ScalarIn =>
        case p:ScalarOut =>
        case p:VecIn =>
        case p:VecOut =>
        case p:Stage =>
        case p:UDCounter =>
        case r:PipeReg =>
        case r:ArgIn =>
        case r:ArgOut =>
        case p:Reg => 
      }
      case r:Const[_] =>
      case _ => throw new Exception(s"Don't know how to visit $node")
    }
    visited += node
  }
}