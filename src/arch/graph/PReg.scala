package pir.plasticine.graph

import pir.graph._
import pir.util.enums._
import pir.plasticine.main._
import pir.plasticine.util._

import scala.language.reflectiveCalls
import scala.collection.mutable.Map
import scala.collection.mutable.Set

/* Logical register (1 row of pipeline registers for all stages) */
case class ArchReg()(implicit spade:Spade) extends Node {
  import spademeta._
  override val typeStr = "reg"
  val _colors = Set[RegColor]()
  def colors = _colors.toList
  def color(c:RegColor) = _colors += c
  def is(c:RegColor) = _colors.contains(c)
}

trait RegColor
case object VecInReg extends RegColor
case object VecOutReg extends RegColor
case object ScalarInReg extends RegColor
case object ScalarOutReg extends RegColor
//case object LoadReg extends RegColor
//case object StoreReg extends RegColor
case object ReadAddrReg extends RegColor
case object WriteAddrReg extends RegColor
case object CounterReg extends RegColor
case object ReduceReg extends RegColor