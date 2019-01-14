package prism
package graph

/*
 * Traverse the graph until hit node satisfying prefix or depth = 0
 * Accumulate the result based on accumulate function
 * */
class PrefixTraversal[TT](
  prefix:Node[_] => Boolean, 
  vf:Node[_] => List[Node[_]], 
  accumulate:(TT, Node[_]) => TT, 
  val zero:TT,
  logging:Option[Logging]
) extends DFSTraversal {
  type N = (Node[_], Int)
  type T = TT

  override def isVisited(n:N) = {
    val (node, depth) = n
    visited.contains(node)
  }
  // depth = -1 is infinate depth
  def withinDepth(depth:Int) = (depth > 0 || depth < 0)

  override def visitNode(n:N, prev:T):T = prism.dbgblk(logging, s"visitNode($n, depth=${n._2})") {
    val (node, depth) = n
    visited += node
    val pfx = prefix(node)
    node match {
      case _ if withinDepth(depth) & pfx => accumulate(prev, node)
      case _ if withinDepth(depth) => super.visitNode(n, accumulate(prev, node))
      case _ => prev 
    }
  }

  def visitFunc(n:N):List[N] = {
    val (node, depth) = n
    vf(node).map { next => (next, depth-1) }
  }
}

trait CollectorImplicit {
  implicit class NodeCollector(node:N) {
    def filter(prefix:N => Boolean, visitFunc:N => List[N], depth:Int = -1, logger:Option[Logging]=None):List[N] = 
      dbgblk(logger, s"filter($node, depth=$depth)") {
        def accumulate(prev:List[N], n:N) = {
          if (!prev.contains(n) && prefix(n)) (prev :+ n) else prev
        }
        new PrefixTraversal[List[N]](prefix, visitFunc, accumulate _, Nil, logger).traverseNode((node, depth), Nil)
      }
 
    def collect[M<:N:ClassTag](visitFunc:N => List[N], depth:Int = -1, logger:Option[Logging]=None):List[M] = 
      dbgblk(logger, s"collect($node, depth=$depth)") {
        def prefix(n:N) = n match { case `node` => false; case n:M => true; case _ => false }
        def accumulate(prev:List[M], n:N) = {
          if (!prev.contains(n) && prefix(n)) (prev :+ n.asInstanceOf[M]) else prev
        }
        new PrefixTraversal[List[M]](prefix, visitFunc, accumulate _, Nil, logger).traverseNode((node, depth), Nil)
      }

    def collectUp[M<:N:ClassTag](depth:Int= -1, logger:Option[Logging]=None):List[M] =
      node.collect[M](visitUp _, depth, logger)

    def collectDown[M<:N:ClassTag:TypeTag](depth:Int= -1, logger:Option[Logging]=None):List[M] = 
      node.collect[M](visitDown _, depth, logger)

    def collectIn[M<:N:ClassTag](depth:Int= -1, logger:Option[Logging]=None):List[M] = 
      node.collect[M](visitLocalIn _, depth, logger)

    def collectOut[M<:N:ClassTag](depth:Int= -1, logger:Option[Logging]=None):List[M] = 
      node.collect[M](visitLocalOut _, depth, logger)

    def collectPeer[M<:N:ClassTag](depth:Int= -1, logger:Option[Logging]=None):List[M] =
      node.collect[M](visitPeer _, depth, logger)

    def accum(prefix:N => Boolean={n:N => false } , visitFunc:N => List[N], depth:Int= -1, logger:Option[Logging]=None):List[N] = 
      dbgblk(logger, s"accum(depth=$depth)"){
        def accumulate(prev:List[N], n:N) = {
          if (!prev.contains(n)) (prev :+ n) else prev
        }
        new PrefixTraversal[List[N]](prefix, visitFunc, accumulate _, Nil, logger).traverseNode((node, depth), Nil)
      }

    def accumIn(prefix:N => Boolean, depth:Int= -1, logger:Option[Logging]=None):List[N] = 
      node.accum(prefix, visitLocalIn _, depth, logger)

    def accumTill[M<:N:ClassTag](visitFunc:N => List[N]=visitLocalIn _, depth:Int= -1, logger:Option[Logging]=None):List[N] = {
      def prefix(n:N) = n match { case `node` => false; case n:M => true; case _ => false }
      node.accum(prefix _, visitFunc, depth, logger)
    }

    def canReach(target:N, visitFunc:N => List[N], depth:Int= -1, logger:Option[Logging]=None):Boolean = 
      dbgblk(logger, s"canReach($target, depth=$depth)"){
        def prefix(n:N) = n == target
        def accumulate(prev:Boolean, n:N) = prefix(n) || prev
        new PrefixTraversal[Boolean](prefix, visitFunc, accumulate _, false, logger).traverseNode((node, depth), false)
      }

    def areLinealInherited(that:N, logger:Option[Logging]=None):Boolean = 
      dbgblk(logger, s"areLinealInherited($node, $that)") {
        node == that || node.ancestors.contains(that) || that.ancestors.contains(node)
      }
  }

  implicit class EdgeCollector(edge:Edge) {
    def collect[M<:N:ClassTag](visitFunc:N => List[N], depth:Int = -1, logger:Option[Logging]=None):List[M] = 
      dbgblk(logger, s"collect(${edge.src}.${edge}, depth=$depth)") {
        def prefix(n:N) = n match { case n:M => true; case _ => false }
        def accumulate(prev:List[M], n:N) = {
          if (!prev.contains(n) && prefix(n)) (prev :+ n.asInstanceOf[M]) else prev
        }
        val nodes = edge.neighbors.map { n => (n, depth) }
        new PrefixTraversal[List[M]](prefix, visitFunc, accumulate _, Nil, logger).traverseNodes(nodes, Nil)
      }

  }
}
