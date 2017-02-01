package pir.graph.mapper
import pir.graph._
import pir._
import pir.typealias._
import pir.codegen.Printer
import pir.graph.traversal.{PIRMapping, MapPrinter}
import pir.plasticine.main._

import scala.collection.immutable.Set
import scala.collection.immutable.HashMap
import scala.collection.mutable.{Map => MMap}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Success, Failure}

trait CUMapper extends Mapper {
  override implicit val mapper:CUMapper = this
  def resMap:MMap[CL, List[PCL]]
  def finPass(m:M):M = m
  def map(m:M):M
  override def debug = Config.debugCUMapper

  def check(info:String, cond:Any):(Boolean,String) = {
      cond match {
        case cond:Boolean => 
          //if (!cond) dprintln(s"$info: pass:$cond numNodes:$nn numRes:$nr")
          (cond, s"$info: false")
        case (nn:Int, nr:Int) => val (pass,_) = check(info, (nn <= nr))
          (pass, s"$info: numNode:$nn numRes:$nr")
        case (ns:Iterable[_], rs:Iterable[_]) => check(info, (ns.size, rs.size))
        case c => throw PIRException(s"Unknown checking format: $cond")
      }
  }

  def check(conds:List[(String, Any)], failureInfo:ListBuffer[String]):Boolean = {
    conds.foldLeft(true) { case (qualify, cond) => 
      val (info, c) = cond
      val (pass, fi) = check(info, c) 
      if (!pass) failureInfo += fi 
      qualify && pass 
    }
  }

  /* 
   * Filter qualified resource. Create a mapping between cus and qualified pcus for each cu
   * */
  def qualifyCheck(pcls:List[PCL], cls:List[CL], map:MMap[CL, List[PCL]])(implicit mapper:CUMapper, design:Design):Unit = {
    val pcus = design.arch.cus
    val cus = design.top.innerCUs
    val grp = cus.groupBy(_.isInstanceOf[MC]) 
    val pgrp = pcus.groupBy(_.isInstanceOf[PMC])
    val mcs = grp.getOrElse(true, Nil)
    val pmcs = pgrp.getOrElse(true, Nil)
    val rcus = grp.getOrElse(false, Nil)
    val prcus = pgrp.getOrElse(false, Nil)
    if (mcs.size > pmcs.size) throw OutOfPMC(pmcs.size, mcs.size)
    if (rcus.size > prcus.size) throw OutOfPCU(prcus.size, rcus.size)
    cls.foreach { cl => 
      val failureInfo = MMap[PCL, ListBuffer[String]]()
      map += cl -> pcls.filter { pcl =>
        val cons = ListBuffer[(String, Any)]()
        cl match {
          case top:Top if pcl.isInstanceOf[PTop] =>
            cons += (("sin"	      , (cl.sins.size, pcl.vins.size))) //TODO
          case cu:ICL if pcl.isInstanceOf[PCU] =>
            val pcu = pcl.asInstanceOf[PCU]
            cons += (("mctpe"       , cu.isInstanceOf[MC] == pcu.isInstanceOf[PMC]))
            cons += (("reg"	      , (cu.infGraph, pcu.regs)))
            cons += (("ctr"	      , (cu.cchains.flatMap(_.counters), pcu.ctrs)))
            cons += (("stage"	    , (cu.stages, pcu.stages)))
            cons += (("tokOut"	  , (cu.ctrlOuts, pcu.ctrlBox.ctrlOuts)))
            cons += (("tokIn"	    , (cu.ctrlIns, pcu.ctrlBox.ctrlIns)))
            cons += (("udc"	      , (cu.udcounters, pcu.ctrlBox.udcs)))
            cons += (("enLut"	    , (cu.enLUTs, pcu.ctrlBox.enLUTs)))
            cons += (("tokDownLut", (cu.tokDownLUTs, pcu.ctrlBox.tokenDownLUTs)))
            cons += (("tokOutLut" , (cu.tokOutLUTs, pcu.ctrlBox.tokenOutLUTs)))
            cons += (("sin"	      , (cl.sins.size, Math.min(pcu.vins.size, pcu.numSinReg)))) //TODO
            cu match {
              case mc:MemoryController => 
              case _ => 
                cons += (("onchipmem"	, (cu.mems, pcu.srams)))
            }
          case _ =>
            cons += (("tpe"       , false))
        }
        cons += (("sout"	    , (cl.souts, pcl.souts)))
        cons += (("vin"	      , (cl.vins.filter(_.isConnected), pcl.vins.filter(_.fanIns.size>0))))
        cons += (("vout"	    , (cl.vouts.filter(_.isConnected), pcl.vouts.filter(_.fanOuts.size>0))))
        design.arch match {
          case sn:SwitchNetwork =>
            cons += (("cin"	      , (cl.ctrlIns.filter(_.isConnected).map(_.from).toSet, pcl.cins.filter(_.fanIns.size>0))))
            cons += (("cout"	    , (cl.ctrlOuts.filter(_.isConnected), pcl.couts.filter(_.fanOuts.size>0))))
          case pn:PointToPointNetwork =>
        }
        failureInfo += pcl -> ListBuffer[String]()
        check(cons.toList, failureInfo(pcl))
      }
      if (map(cl).size==0) {
        val info = failureInfo.map{ case (pcl, info) => s"$pcl: [${info.mkString(",")}] \n"}.mkString(",")
        println(info)
        throw CUOutOfSize(cl, info)
      }
      mapper.dprintln(s"qualified resource: $cl -> ${map(cl)}")
    }
  }
}

object CUMapper {
  def apply(outputMapper:OutputMapper, viMapper:VecInMapper, ctrlMapper:CtrlMapper, fp:PIRMap => PIRMap)(implicit design:Design):CUMapper = {
    design.arch match {
      case sn:SwitchNetwork => new CUSwitchMapper(outputMapper, ctrlMapper) { override def finPass(m:M):M = fp(m) }
      case pn:PointToPointNetwork => new CUP2PMapper(outputMapper, viMapper) { override def finPass(m:M):M = fp(m) }
      case _ => throw PIRException("Unknown network type")
    }
  }
  def apply(outputMapper:OutputMapper, viMapper:VecInMapper, ctrlMapper:CtrlMapper)(implicit design:Design):CUMapper = {
    design.arch match {
      case sn:SwitchNetwork => new CUSwitchMapper(outputMapper, ctrlMapper)
      case pn:PointToPointNetwork => new CUP2PMapper(outputMapper, viMapper)
      case _ => throw PIRException("Unknown network type")
    }
  }
}

case class CUOutOfSize(cl:CL, info:String) (implicit val mapper:CUMapper, design:Design) extends MappingException {
  override val msg = s"cannot map ${cl} due to resource constrains\n${info}"
} 
case class OutOfPMC(nres:Int, nnode:Int) (implicit val mapper:CUMapper, design:Design) extends OutOfResource {
  override val msg = s"Not enough MemoryController in ${design.arch} to map application."
} 
case class OutOfPCU(nres:Int, nnode:Int) (implicit val mapper:CUMapper, design:Design) extends OutOfResource {
  override val msg = s"Not enough ComputeUnits in ${design.arch} to map application."
} 
