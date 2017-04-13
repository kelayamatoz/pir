package pir.mapper
import pir.{Design, Config}
import pir.util.typealias._
import pir.pass.PIRMapping
import pir.graph.{PipeReg => PR, VecInPR, LoadPR}
import pir.plasticine.graph.{PipeReg => PPR}
import pir.plasticine.util._
import pir.plasticine.main._
import pir.exceptions._
import pir.util.PIRMetadata

import scala.collection.mutable.ListBuffer
import scala.collection.immutable.Set
import scala.collection.immutable.HashMap
import scala.collection.immutable.Map

class SFifoMapper(implicit val design:Design) extends Mapper {
  type N = SMem
  type R = PSMem
  val typeStr = "ScalFifoMapper"
  override def debug = Config.debugSFifoMapper
  import pirmeta.{indexOf => _, _}
  import spademeta._

  def finPass(cu:ICL)(m:M):M = m 

  def constrain(n:N, r:R, m:M):M = {
    m.setSM(n, r)
      .setOP(n.readPort, r.readPort)
      .setIP(n.writePort, r.writePort)
  }

  // After RegAlloc
  def resFunc(cu:ICL)(n:N, m:M, triedRes:List[R]):List[R] = {
    val pcu = m.clmap(cu)
    val regs = n.readPort.to.map(_.src).collect{ case pr:PR => pr.reg }
    val reses = if (regs.isEmpty) { // scalarIn is not used in pipeline stages. Pick whichever is not used
      pcu.sbufs
    } else if (regs.size==1) {
      val preg = m.rcmap(regs.head)
      val ppr = pcu.fustages.head.get(preg)
      ppr.in.fanIns.map{_.src}.collect{ case sb:R => sb }
    } else {
      throw PIRException(s"scalarIn:$n is connected to more than 1 pipeRegs: ${regs.mkString(",")}")
    }
    reses.diff(triedRes).filterNot{ r => m.smmap.pmap.contains(r) }
  }

  def map(cu:ICL, pirMap:M):M = {
    val pcu = pirMap.clmap(cu).asCU
    log(cu) {
      bind(
        allNodes=cu.smems,
        initMap=pirMap, 
        constrain=constrain _,
        resFunc=resFunc(cu) _, //(n, m, triedRes) => List[R]
        finPass=(m:M) => m
      )
    }
  }

  def map(cu:CL, pirMap:M):M = {
    cu match {
      case cu:ICL => map(cu, pirMap)
      case cu => pirMap
    }
  }
}