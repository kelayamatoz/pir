package pir.pass
import pir.graph._
import pir._
import pir.util._
import pir.util.misc._
import pir.codegen.Logger

import scala.collection.mutable.Set
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map

class FusionTransform(implicit design: Design) extends Pass with Logger {
  def shouldRun = true 

  override lazy val stream = newStream(s"FusionTransform.log")

  def fuseCUs(parent:OuterController, childCU:ComputeUnit) = {
    implicit val child:ComputeUnit = childCU 
    dprintln(s"cloning cchain=${parent.localCChain} from $parent to $child")
    dprintln(s"before=${child.cchains}")
    val localCChain = child.localCChain
    val cc = child.cloneCC(parent.localCChain)
    cc.inner.en.connect(localCChain.outer.done)
    dprintln(s"after=${child.cchains}")
    design.removeNode(parent)
  }

  addPass(canRun=design.optimizer.hasRun) {
    design.top.outerCUs.foreach {
      case pcu if pcu.children.size == 1=>
        pcu.children.head match {
          case cu => 
            dprintln(s"fusing parent=$pcu and child=$cu")
            //swapCopy(pcu, cu) // Don't touch counter after memory ananlyzer
            fuseCUs(pcu, cu)
        }
      case pcu =>
    }
  } 

  override def finPass = {
    endInfo("Finishing fusion transformation")
  }

}
