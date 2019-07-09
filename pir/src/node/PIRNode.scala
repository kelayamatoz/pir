package pir
package node

import prism.graph._
import prism.util._

import scala.collection.mutable

abstract class PIRNode(implicit env:BuildEnvironment) 
  extends EnvNode[PIRNode] 
  with FieldNode[PIRNode] { self =>
  lazy val Nct = classTag[PIRNode]

  val name = new Metadata[String]("name") {
    override def mirror(frommeta:MetadataLike[_]) = {
      if (v.isEmpty) super.mirror(frommeta)
      else self
    }
  }
  val sname = new Metadata[String]("sname") {
    override def check(v:String) = {}
  }
  val srcCtx = new Metadata[String]("srcCtx") {
    override def check(v:String) = {}
  }

  val ctrl = new Metadata[ControlTree]("ctrl") {
    override def apply(value:ControlTree, reset:Boolean=false) = self match {
      case self:GlobalContainer => getSelf
      case self => super.apply(value, reset)
    }
    override def reset = {
      self match {
        case _:Controller => value.foreach { v => v.ctrler.reset }
        case _ =>
      }
      super.reset
    }
  }
  val tp = new Metadata[BitType]("tp")

  // Scale is relative rate of a node active to ctx enable
  val scale = new Metadata[Value[Long]]("scale") {
    override def mirror(frommeta:MetadataLike[_]) = self
  }
  // Count is total number of time a node is active
  val count = new Metadata[Value[Long]]("count") {
    override def check(v:Value[Long]) = {
      (value, v) match {
        case (Some(Infinite), Unknown) => // Unknown is more specific (data-dependent) than Infinite
        case (Some(Infinite), Finite(s)) => // Finite is more specific (data-dependent) than Infinite
        case _ => super.check(v)
      }
    }
  }
  // Iter is how many iteration a node run between enabled and done. Independent of what it stacks on
  val iter = new Metadata[Value[Long]]("iter")
  val vec = new Metadata[Int]("vec") {
    override def mirror(frommeta:MetadataLike[_]) = self
  }
  val presetVec = new Metadata[Int]("presetVec")

  // Marker for whether the operation is reduction operation across lane
  val isInnerReduceOp = new Metadata[Boolean]("isInnerReduceOp", default=Some(false))

  // Is external node when modularize app
  val isExtern = new Metadata[Boolean]("isExtern", default=Some(false))
  val externAlias = new Metadata[String]("externAlias")

  env.initNode(this)
}
object PIRNode extends MemoryUtil with AccessUtil {
  implicit class PIRNodeOp(n:PIRNode) {
    def ctx = n.collectUp[Context]().headOption
    def global = n.collectUp[GlobalContainer]().headOption
    def isUnder[T:ClassTag] = n.ancestors.exists { _.to[T].nonEmpty }
  }
}

sealed abstract class CtrlSchedule
case object Sequenced extends CtrlSchedule
case object Pipelined extends CtrlSchedule
case object Streaming extends CtrlSchedule
case object ForkJoin extends CtrlSchedule
case object Fork extends CtrlSchedule
case class ControlTree(schedule:CtrlSchedule)(implicit env:Env) extends EnvNode[ControlTree] with FieldNode[ControlTree] with Ordered[ControlTree] { self =>
  lazy val Nct = classTag[ControlTree]

  val sname = new Metadata[String]("sname")
  val ctrler = new Metadata[Controller]("ctrler")
  val par = new Metadata[Int]("par")
  val srcCtx = new Metadata[String]("srcCtx")

  def compare(that:ControlTree) = {
    if (this == that) 0
    else if (this.isAncestorOf(that)) 1
    else if (that.isAncestorOf(this)) -1
    else throw PIRException(s"Cannot compare $this with $that")
  }

  def isLeaf = children.isEmpty

  env.initNode(this)
}

