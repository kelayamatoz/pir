package pir
package codegen

import pir.node._
import prism.graph._
import prism.codegen._
import spade.param._
import scala.collection.mutable

trait TungstenControllerGen extends TungstenCodegen with TungstenCtxGen {
  override def quoteRef(n:Any):String = n match {
    case n@InputField(_:Controller, "en" | "parentEn") => quoteEn(n.as[Input[PIRNode]], None)
    case OutputField(ctrler:Controller, "done") => s"$ctrler->Done()"
    case OutputField(ctrler:Controller, "childDone") => s"$ctrler->ChildDone()"
    case OutputField(ctrler:Controller, "valid") => s"$ctrler->Valid() & $ctrler->ChildDone()"
    case OutputField(ctrler:LoopController, "firstIter") => s"$ctrler->FirstIter()"
    case n => super.quoteRef(n)
  }

  override def emitNode(n:N) = n match {
    case n:ControlBlock => 
      dbg(s"$n")
      val ctrler = n.ctrlers.last
      emitIf(s"${ctrler}->Enabled()") {
        super.visitNode(n)
      }

    case n:Controller =>
      val tp = n match {
        case n:LoopController => "LoopController"
        case n => "UnitController"
      }
      emitNewMember(tp, n)
      genCtxInits {
        assertOneOrLess(n.childCtrlers, s"$n.childCtrlers").foreach { child =>
          emitln(s"""$n->SetChild($child);""");
        }
        emitln(s"controllers.push_back($n);");
        val inputs = n.collectPeer[LocalOutAccess]().filterNot { _.nonBlocking }.map { nameOf }
        emitln(s"${n}->SetInputs({${inputs.mkString(",")}});")
        n.to[LoopController].foreach { n =>
          n.cchain.foreach { ctr =>
            emitln(s"$n->AddCounter(${ctr});")
          }
        }
      }
      emitln(s"$n->SetEn(${n.en.qref});")
      if (n.getCtrl.isLeaf) {
        emitln("Active();")
      }

      // If last level controller is loop controller, generate lane valids

      n.to[LoopController].foreach { n =>
        n.stopWhen.T.foreach { stop =>
          genCtxComputeMid {
            emitln(s"$n->SetStop($stop);")
          }
        }
      }

      super.visitNode(n)

      n.to[HostOutController].foreach { ctrler =>
        if (ctrler.collectPeer[LocalOutAccess]().nonEmpty) {
          emitln(s"Complete(1);")
          genCtxInits {
            emitln(s"Expect(1);")
          }
        }
      }

      if (n.getCtrl.isLeaf) {
        n.to[LoopController].foreach { ctrler =>
          val laneValids = ctrler.cchain.T.foldLeft(List[String]()) { 
            case (Nil, ctr) => List.tabulate(ctr.par) { i => s"$ctr->Valids()[$i]" }
            case (prev, ctr) => 
              prev.flatMap { valid => 
                List.tabulate(ctr.par) { i => s"$valid & $ctr->Valids()[$i]" }
              }
          }
          emitln(s"bool laneValids[] = {${laneValids.mkString(",")}};")
        }
      }


    case n:Counter if n.isForever =>
      emitNewMember(s"ForeverCounter<${n.par}>", n)

    case n:Counter if !n.isForever =>
      emitNewMember(s"Counter<${n.par}>", n)
      emitln(s"$n->setMin(${n.min.T.get});")
      emitln(s"$n->setStep(${n.step.T.get});")
      emitln(s"$n->setMax(${n.max.T.get});")
      emitln(s"$n->Eval();")

    case n@CounterIter(is) =>
      val ctr = n.counter.T
      emitVec(n, is.map { i => s"$ctr->Iters()[$i]" })

    case n@CounterValid(is) =>
      val ctr = n.counter.T
      emitVec(n, is.map { i => s"$ctr->Valids()[$i]" })

    case n => super.emitNode(n)
  }
}
