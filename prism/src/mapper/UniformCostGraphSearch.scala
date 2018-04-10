package prism.mapper

import scala.collection.mutable

trait UniformCostGraphSearch[N,A,C] extends Logging {

  def routingVerbosity:Int
  private lazy val dpfx = debug && routingVerbosity > 2

  // (N, A, C): (Node, Action, Cost)
  type BackPointer = mutable.Map[N, (N,A,C)]
  type Explored = mutable.ListBuffer[N]
  type Route = List[(N,A)]

  implicit val cnu:Numeric[C]

  case class State(n:N, var cost:C) extends Ordered[State] {
    override def toString = s"State(${quote(n)}, $cost)" 
    def compare(that:State):Int = -cnu.compare(cost, that.cost)
  }

  /* Find the minimum path from start to end
   * @return mapping 
   * */
  def uniformCostSearch(
    start:N, 
    isEnd:(N, BackPointer) => Boolean,
    advance:(N, BackPointer, C) => Seq[(N,A,C)]
  ):EOption[Route] = {

    def terminate(minNode:N, backPointers:BackPointer):Option[Route] = {
      if (minNode != start && isEnd(minNode, backPointers)) {
        val (route, cost) = extractHistory(start, minNode, backPointers)
        Some(route)
      } else {
        None
      }
    }

    def cleanUp(explored:Explored, backPointers:BackPointer):EOption[Route] = {
      return Left(SearchFailure(s"No route from ${quote(start)}"))
    }

    uniformCostTraverse[Route](
      start=start,
      advance=advance,
      terminate=terminate,
      cleanUp=cleanUp
    )
  }

  /*
   * Find list of nodes reachable from start
   * */
  def uniformCostSpan(
    start:N, 
    advance:(N, BackPointer, C) => Seq[(N,A,C)]
  ):Seq[(N,C)] = {

    def terminate(minNode:N, backPointers:BackPointer):Option[Seq[(N,C)]] = { 
      return None
    }

    def cleanUp(explored:Explored, backPointers:BackPointer):EOption[Seq[(N,C)]] = {
      Right(explored.map { n => (n, extractHistory(start, n, backPointers)._2) }.toList)
    }

    uniformCostTraverse(
      start=start,
      advance=advance,
      terminate=terminate,
      cleanUp=cleanUp
    ).right.get
  }

  def uniformCostTraverse[M](
    start:N, 
    advance:(N, BackPointer, C) => Seq[(N,A,C)],
    terminate:(N, BackPointer) => Option[M],
    cleanUp:(Explored, BackPointer) => EOption[M]
  ):EOption[M] = {

    val explored:Explored = mutable.ListBuffer[N]()

    val backPointers:BackPointer = mutable.Map[N, (N,A,C)]()

    var frontier = mutable.PriorityQueue[State]()

    frontier += State(start, cnu.zero)

    while (!frontier.isEmpty) {
      dbg(dpfx, s"frontier: ${frontier}")

      val State(minNode, pastCost) = frontier.dequeue()

      explored += minNode
      terminate(minNode, backPointers).foreach { res => 
        return Right(res)
      }

      var neighbors:Seq[(N, A, C)] = advance(minNode, backPointers, pastCost)

      neighbors = neighbors.filterNot { case (n, a, c) => explored.contains(n) }

      neighbors = neighbors.groupBy { case (n, a, c) => n }.map { case (n, groups) =>
        groups.minBy { case (n, a, c) => c }
      }.toSeq

      dbg(dpfx, s"neighbors minBy:")
      dbg(dpfx, s" - ${neighbors.map { case (n, a, c) => s"(${quote(n)}, $c)" }.mkString(",")}")

      neighbors.foreach { case (neighbor, action, cost) =>
        val newCost = cnu.plus(pastCost, cost)
        frontier.filter { case State(n,c) => n == neighbor }.headOption.fold [Unit]{
          frontier += State(neighbor, newCost)
          backPointers += neighbor -> ((minNode, action, cost))
        } { node =>
          if (cnu.lt(newCost, node.cost)) {
            node.cost = newCost
            backPointers += neighbor -> ((minNode, action, cost))
            frontier = frontier.clone() // Create a new copy to force reordering
          }
        }
      }
    }

    cleanUp(explored, backPointers)
  }

  def extractHistory(
    start:N, 
    end:N, 
    backPointers:BackPointer, 
  ):(Route, C) = {
    var totalCost = cnu.zero
    val history = mutable.ListBuffer[(N, A)]()
    var current = end
    while (current != start) {
      val (prevNode, action, cost) = backPointers(current)
      totalCost = cnu.plus(totalCost, cost)
      history += ((current, action))
      current = prevNode
    }
    return (history.toList.reverse, totalCost)
  }

}
