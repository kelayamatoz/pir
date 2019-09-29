package pir
package pass

import pir.node._
import prism.graph._
import scala.collection.mutable

class BlackBoxLowering(implicit compiler:PIR) extends PIRTraversal with SiblingFirstTraversal with PIRTransformer with UnitTraversal with DependencyAnalyzer {

  override def visitNode(n:N) = n match {
    case n:BlackBox => moveToContext(n)
    case _ => super.visitNode(n)
  }

  override def finPass = {
    super.finPass
  }

  def moveToContext(n:BlackBox) = {
    val ctrl = n.ctrl.get
    val ctx = within(pirTop, ctrl) { 
      Context().streaming(true)
    }
    swapParent(n, ctx)
    (n.localDeps ++ n.localDepeds).foreach { neighbor =>
      neighbor.to[Access].foreach { access =>
        swapParent(access, ctx)
      }
    }
    bufferInput(ctx)
  }

}
