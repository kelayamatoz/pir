package pir
package mapper

import prism.collection.immutable._

trait Cost[C] extends Ordered[C] {
  val isSplittable:Boolean
  def compareAsC(x:Any) = compare(x.asInstanceOf[C])
  def fit(x:Any):(Boolean, Boolean) // (fit, splittable)
}
trait CostConstrain[C<:Cost[C]] extends Constrain {
  def getKeyCost(cuP:K):C
  def getValueCost(cuS:V):C
  val keyCost = memorize(getKeyCost)
  val valueCost = memorize(getValueCost)
  def fit(key:K, value:V):(Boolean, Boolean)
  def prune(fg:FG):EOption[FG] = {
    flatFold(fg.freeKeys, fg) { case (fg, key) =>
      val values:Set[(V, Boolean, Boolean)] = fg.freeValues(key).map { value =>
        val (fits, splitable) = fit(key, value)
        (value, fits, splitable)
      }
      val (fits, nonFits) = values.partition { _._2 }
      if (fits.isEmpty) { // not fit
        val (splitables, nonSplitables) = nonFits.partition { _._3 }
        val nonSplitableValues = nonSplitables.map { _._1 }
        dbg(s"${quote(key)} not fit. Cost:${keyCost(key)}")
        fg.filterNotAt(key) { v => nonSplitableValues.contains(v) } match {
          case Left(InvalidFactorGraph(fg:FG, key)) => Left(CostConstrainFailure(fg , key, false))
          case Right(fg) => Left(CostConstrainFailure(fg , key, splitables.nonEmpty))
        }
      } else {
        val nonFitValues = nonFits.map { _._1 }
        fg.filterNotAt(key) { v => nonFitValues.contains(v) }
      }
    }
  }
}
case class CostConstrainFailure[FG<:FactorGraphLike[_,_,FG]](@transient fg:FG, key:Any, isSplittable:Boolean) extends MappingFailure