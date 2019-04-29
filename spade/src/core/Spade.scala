package spade

import spade.param._
import spade.node._
import spade.pass._
import spade.codegen._

import java.io._

trait Spade extends Compiler with DefaultParamLoader {

  val env = new Env {}
  override lazy val config:SpadeConfig = new SpadeConfig(this)

  def handle(e:Throwable):Try[Boolean] = Failure(e)

  var _spadeTop:Option[Top] = None
  def spadeTop = _spadeTop.get

  def getOpt[T](name:String):Option[T] = config.getOption[T](name)

  /* Analysis */
  //TODO: Area model

  //lazy val areaModel = new AreaModel()

  /* Codegen */
  //lazy val spadeNetworkCodegen = new SpadeNetworkCodegen()
  //lazy val statCodegen = new StatCodegen()
  //lazy val paramScalaCodegen = new ParamScalaCodegen(s"GeneratedParameters.scala")

  /* Debug */
  //lazy val spadePrinter = new SpadePrinter()

  override def initSession = {

    addPass(new SpadeBaseFactory())

    //// Pass
    ////addPass(areaModel)

    //// Debug
    ////addPass(new SpadeIRPrinter(s"spade.txt"))
    addPass(new ParamHtmlIRPrinter(s"param.html", spadeTop.param))
    //addPass(new SpadeNetworkDotGen(s"net.dot"))
    //addPass(new NetworkDotCodegen[Word](s"scalar.dot"))
    //addPass(new NetworkDotCodegen[Vector](s"vector.dot"))
    ////addPass(new SpadeIRDotCodegen[PCU](s"pcu.dot"))
    ////addPass(new SpadeIRDotCodegen[PMU](s"pmu.dot"))
    ////addPass(new SpadeIRDotCodegen[SCU](s"scu.dot"))
    ////addPass(spadePrinter)

    //// Codegen
    //addPass(statCodegen)
    //addPass(paramScalaCodegen)
    ////addPass(spadeNetworkCodegen)
    ////addPass(spadeParamCodegen)
  }

  override def loadSession = {
    super.loadSession
    if (env._states.isEmpty) env.createNewState
  }
  
}

object Plasticine extends Spade
