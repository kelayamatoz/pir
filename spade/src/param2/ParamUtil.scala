package spade
package param

trait ParamUtil {
  implicit class ParamOp(p:Parameter) {
    def isAsic:Boolean = p match {
      case p:TopParam => p.pattern.isAsic
      case p:AsicPattern => true
      case p:Pattern => false
      case _ => bug(s"Don't know how to eval isAsic for $p")
    }
    def isP2P:Boolean = p match {
      case p:NetworkParam => p.topology == "p2p"
      case p => p.networkParams.forall(_.isP2P)
    }
    def isInf:Boolean = p match {
      case p:TopParam => p.pattern.isInf
      case p:InfinatePattern => true
      case p:Pattern => false
      case _ => bug(s"Don't know how to eval isInf for $p")
    }
    def networkParams:List[NetworkParam] = p match {
      case p:TopParam => p.pattern.networkParams
      case p:Checkerboard => p.networkParams
      case p:AsicPattern => List(p.networkParam)
      case _ => Nil
    }
    def vecNetParam:Option[NetworkParam] = {
      p.networkParams.filter { _.granularity == "vec" }.headOption
    }
  }
}
