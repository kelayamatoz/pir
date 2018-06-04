package pir
package pass

import pir.node._
import pir.mapper._
import pir.codegen._

import spade.node._

trait GlobalPartioner extends PIRTransformer with CUPruner {
  import pirmeta._

  override def runPass =  {
    pirMap = initCUMap.flatMap { pmap =>
      pmap.flatMap[CUMap] { cumap => pruneAndSplit(cumap) }
    }
    log(pirMap)
  }

  override def finPass = {
    super.finPass
    pirMap.fold (
      { failure => fail(failure) },
      { pmap => pmap.cumap.freeKeys.foreach(retimeGlobal) }
    )
  }

  def retimeGlobal(cu:GlobalContainer) = dbgblk(s"retimeGlobal") {
    cu.ins.filter { in => 
      in.src match {
        case src:LocalStore => false
        case src:LocalReset => false
        case src => true
      }
    }.groupBy { in =>
      in.from.src.asInstanceOf[Def]
    }.foreach { case (fromsrc, ins) =>
      dbg(s"retiming ${ins.map{ in => s"${in.src}.$in"}} from $fromsrc")
      val load = retime(fromsrc, cu)
      ins.foreach { in =>
        swapConnection(in, fromsrc.out, load.out)
      }
    }
  }

  def pruneAndSplit(cumap:CUMap):EOption[CUMap] = {
    log(prune(cumap)) match {
      case Left(f@CostConstrainFailure(constrain, fg, key:CUMap.K)) if isSplitable(constrain, key) =>
        val vs = cumap(key)
        val ks = split(key)
        val newCUMap = (cumap - key) ++ (ks -> vs)
        pruneAndSplit(newCUMap)
      case Left(f) => Left(f)
      case Right(map) => Right(map)
    }
  }

  def isSplitable(constrain:Constrain, key:CUMap.K) = (constrain, key) match {
    case (c, g) if isFringe(g) => false
    case (c:CUPrefixConstrain, _) => false
    case (c:SramConstrain,_) => false
    case (c:LaneConstrain,_) => false
    case (c:CUQuantityConstrain,_) => true
    case (c,_) => false
  }

  def split(cu:GlobalContainer):Set[GlobalContainer]

}
