package pir
package mapper

import pir.node._
import prism.graph._
import spade.param._
import prism.collection.immutable._

class ComputePruner(implicit compiler:PIR) extends ConstrainPruner with CUCostUtil with ComputePartitioner {

  override def prune[T](x:T):EOption[T] = super.prune[T](x).flatMap {
    case x:CUMap if !spadeParam.isAsic =>
      flatFold(x.freeKeys, x) { case (x, k) =>
        val kc = getCosts(k)
        recover(x.filterNotAtKey(k) { v => !kc.fit(getCosts(v)) })
      }.asInstanceOf[EOption[T]]
    case x => super.prune(x)
  }

  override def fail(f:Any) = {
    super.fail(f)
    f match {
      case e@InvalidFactorGraph(fg, k:CUMap.K) =>
        err(s"Constrain failed on $k", exception=false)
        err(s"$k costs:", exception=false)
        val kc = getCosts(k)
        kc.foreach { kc =>
          err(s"${kc}:", exception=false)
        }
      case _ =>
    }
  }

}
