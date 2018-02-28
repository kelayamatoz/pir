package pir.node

import pir._

import pirc._
import pirc.util._
import prism.node._

trait Container extends PIRNode with ProductSubGraph[PIRNode] { self =>
  override def ins:List[Input] = super.ins.asInstanceOf[List[Input]]
  override def outs:List[Output] = super.outs.asInstanceOf[List[Output]]
}
