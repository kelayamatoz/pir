package pir.graph.traversal

import pir.{Design, Config}
import pir.codegen._
import pir.misc._
import pir.typealias._
import pir.graph.mapper.{PIRMap, PIRException}
import pir.plasticine.main._
import pir.plasticine.graph.{SwitchBox, Node}

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Set
import scala.collection.mutable.Map
import scala.collection.mutable.HashMap
import java.io.File
import scala.reflect.runtime.universe._

class CUCtrlDotPrinter(fileName:String)(implicit design:Design) extends DotCodegen with Metadata { 
  implicit lazy val spade:Spade = design.arch

  def this()(implicit design:Design) = this(Config.spadeCtrlNetwork)

  override val stream = newStream(fileName) 

  val scale = 13
  def emitPCLs(pcls:List[PCL], mapping:Option[PIRMap]) = {
    //emitln(s"splines=ortho;")
    val bandWidth = spade match {
      case sb:SwitchNetwork => sb.switchNetworkCtrlBandwidth
      case pn:PointToPointNetwork => 1
    }
    pcls.foreach { pcl =>
      val recs = ListBuffer[String]()
      pcl match {
        case pcu:PCU => 
          assert(pcu.cins.size==pcu.couts.size)
          val ctrlIns = pcu.cins.map { cin => s"<${cin}> ${cin}"}
          val ctrlOuts = pcu.couts.map { cout => s"<${cout}> ${cout}"}
          val qpcu = quote(pcu)
          val culabel = mapping.fold(qpcu) { mp => mp.clmap.pmap.get(pcu).fold(qpcu) { cu => 
            val icl = cu.asInstanceOf[ICL]
            s"{$qpcu|${(icl +: icl.outers).mkString(s"|")}}"} 
          }
          //recs += s"{${cins(1)}  | ${cins(0)}   | ${cins(7)}}"
          //recs += s"{${couts(1)} | ${couts(0)}  | ${couts(7)}}"
          //recs += s"{${cins(2)}  | ${culabel}   | ${cins(6)}}"
          //recs += s"{${couts(2)} |              | ${couts(6)}}"
          //recs += s"{${cins(3)}  | ${cins(4)}   | ${cins(5)}}"
          //recs += s"{${couts(3)} | ${couts(4)}  | ${couts(5)}}"
          def cins(i:Int) = List.tabulate(bandWidth) { ibw => s"${ctrlIns(i+ibw*8)}" }.mkString(" | ")
          def couts(i:Int) = List.tabulate(bandWidth) { ibw => s"${ctrlOuts(i+ibw*8)}" }.mkString(" | ")
          recs += s"{${cins(1)}  | ${couts(1)}  | ${cins(0)}| ${couts(0)} | ${cins(7)}   | ${couts(7)}}"
          recs += s"{{${cins(2)} | ${couts(2)}} | ${culabel}              | {${couts(6)} | ${cins(6)}}}"
          recs += s"{${cins(3)}  | ${couts(3)}  | ${couts(4)}| ${cins(4)} | ${cins(5)}   | ${couts(5)}}"
        case ptop:PTop => recs += s"$ptop" 
      }
      val label = s"{${recs.mkString("|")}}"
      var attr = DotAttr().shape(Mrecord)
      coordOf.get(pcl).foreach { case (x,y) => attr.pos((x*scale, y*scale)) }
      mapping.foreach { mp => if (mp.clmap.pmap.contains(pcl)) attr.style(filled).fillcolor(indianred) }
      pcl match {
        case pcu:PCU =>
          emitNode(pcl, label, attr)
        case ptop:PTop => s"$ptop" 
          emitNode(quote(ptop, true), label, attr)
          emitNode(quote(ptop, false), label, attr)
      }
      pcl.cins.foreach { cin =>
        emitInput(pcl, cin, mapping)
      }
    }
  }

  def emitSwitchBoxes(sbs:List[PSB], mapping:Option[PIRMap]) = CUDotPrinter.emitSwitchBoxes(sbs, mapping, scale)(this)
  def emitInput(pcl:PCL, pvin:PIB, mapping:Option[PIRMap]) = CUDotPrinter.emitInput(pcl, pvin, mapping, scale)(this)

  def print:Unit = { print(design.mapping) }

  def print(mapping:PIRMap):Unit = {
    emitBlock("digraph G") {
      design.arch match {
        case pn:PointToPointNetwork =>
        case sn:SwitchNetwork if (mapping!=null) =>
          emitPCLs(sn.cus :+ sn.top, Some(mapping))
          emitSwitchBoxes(sn.csbs.flatten, Some(mapping))
        case sn:SwitchNetwork if (mapping==null) =>
          emitPCLs(sn.cus :+ sn.top, None)
          emitSwitchBoxes(sn.csbs.flatten, None)
      }
    }
    close
  }

}

object CUDotPrinter extends Metadata {
  def emitSwitchBoxes(sbs:List[PSB], mapping:Option[PIRMap], scale:Int)(printer:DotCodegen)(implicit design:Design) = {
    import printer._
    implicit val spade = design.arch
    sbs.foreach { sb =>
      val (x,y) = coordOf(sb)
      val attr = DotAttr().shape(Mrecord)
      coordOf.get(sb).foreach { case (x,y) => attr.pos((x*scale-scale/2, y*scale-scale/2)) }
      val label = mapping.flatMap { mp => 
        if (sb.vins.exists( vi => mp.fbmap.contains(vi))) { 
          attr.style(filled).fillcolor(indianred) 
          val xbar = sb.vouts.flatMap { vout => 
            mp.fpmap.get(vout.voport).map{ fp =>
              s"i-${indexOf(fp.src)} -\\> o-${indexOf(vout)}"
            }
          }.mkString(s"|") 
          Some(s"{${quote(sb)}|${xbar}}")
        } else {
          None
        }
      }.getOrElse(quote(sb))
      emitNode(sb, label, attr)
      sb.vins.foreach { pvin =>
        pvin.fanIns.foreach { pvout =>
          val attr = DotAttr()
          mapping.foreach { mp => 
            if (mp.fbmap.get(pvin).fold(false) { _ == pvout}) { 
              var label = s"(i-${indexOf(pvin)})"
              if (pvout.src.isInstanceOf[PSB]) label += s"\n(o-${indexOf(pvout)})"
              attr.color(indianred).style(bold).label(label)
            } 
          }
          pvout.src match {
            case from:PSB => emitEdge(s"$from", sb, attr)
            case from:PCU => emitEdge(s"$from:$pvout", sb, attr)
            case from:PTop => emitEdge(quote(from, coordOf(sb)._2==0), sb, attr)
          }
        }
      }
    }
  }
  def emitInput(pcl:PCL, pvin:PIB, mapping:Option[PIRMap], scale:Int)(printer:DotCodegen)(implicit design:Design) = {
    import printer._
    implicit val spade = design.arch
    pvin.fanIns.foreach { pvout =>
      val attr = DotAttr()
      mapping.foreach { m => 
        if (m.fbmap.get(pvin).fold(false){ pvo => pvo == pvout }) {
          attr.color(indianred).style(bold)
          m.vimap.pmap.get(pvin).foreach { vin =>
            var label = pvout.src match {
              case cu:PCU if m.clmap.pmap.contains(cu) =>
                vin match {
                  case dvi:DVI => s"${dvi.vector}[\n${dvi.vector.scalars.mkString(",\n")}]"
                  case vi:VI => s"${vi.vector}"
                  case ip:IP => ""
                }
              case top:PTop =>
                val dvo = m.vomap.pmap(pvout).asInstanceOf[DVO] 
                s"${dvo.vector}[\n${dvo.vector.scalars.mkString(",\n")}]"
              case s => ""
            }
            vin match {
              case ip:IP =>
                label += s"to: ${ip} \nfrom:${ip.from}" 
              case _ =>
            }
            attr.label(label)
          }
        }
      }
      pvout.src match {
        case from:PSB =>
          attr.label.foreach { l => attr.label(l + s"\n(o-${indexOf(pvout)})") }
          pcl match {
            case ptop:PTop => emitEdge(from, quote(ptop, coordOf(from)._2==0), attr)
            case _ => emitEdge(from, s"$pcl:$pvin", attr)
          }
        case from:PCU =>
          emitEdge(from, pvout, pcl, pvin, attr)
        case from:PTop =>
          spade match {
            case sn:SwitchNetwork =>
              val bottom = coordOf(pvin.src)._2==0 
              emitEdge(quote(from, bottom), s"$pcl:$pvin", attr)
            case pn:PointToPointNetwork =>
              emitEdge(quote(from), s"$pcl:$pvin", attr)
          }
      }
    }
  }
}

class CUDotPrinter(fileName:String)(implicit design:Design) extends DotCodegen with Metadata { 
  implicit lazy val spade:Spade = design.arch

  def this()(implicit design:Design) = this(Config.spadeNetwork)

  override val stream = newStream(fileName) 
  
  val scale = 4

  def emitSwitchBoxes(sbs:List[PSB], mapping:Option[PIRMap]) = CUDotPrinter.emitSwitchBoxes(sbs, mapping, scale)(this)
  def emitInput(pcl:PCL, pvin:PIB, mapping:Option[PIRMap]) = CUDotPrinter.emitInput(pcl, pvin, mapping, scale)(this)

  def emitPCLs(pcls:List[PCL], mapping:Option[PIRMap]) = {
    //emitln(s"splines=ortho;")
    pcls.foreach { pcl =>
      val recs = ListBuffer[String]()
      pcl match {
        case pcu:PCU => 
          recs += s"{${pcu.vins.map(vin => s"<${vin}> ${vin}").mkString(s"|")}}" 
          val qpcu = s"${quote(pcu)}"
          recs += mapping.fold(qpcu) { mp => mp.clmap.pmap.get(pcu).fold(qpcu) { cu => 
            val icl = cu.asInstanceOf[ICL]
            s"{$qpcu|{${(icl +: icl.outers).mkString(s"|")}}}"} 
          }
          recs += s"<${pcu.vout}> ${pcu.vout}"
        case ptop:PTop => recs += s"$ptop" 
      }
      val label = s"{${recs.mkString("|")}}"
      var attr = DotAttr().shape(Mrecord)
      coordOf.get(pcl).foreach { case (x,y) => attr.pos((x*scale, y*scale)) }
      mapping.foreach { mp => if (mp.clmap.pmap.contains(pcl)) attr.style(filled).fillcolor(indianred) }
      pcl match {
        case pcu:PCU =>
          emitNode(pcl, label, attr)
        case ptop:PTop => s"$ptop" 
          emitNode(quote(ptop, true), label, attr)
          emitNode(quote(ptop, false), label, attr)
      }
      pcl.vins.foreach { pvin =>
        emitInput(pcl, pvin, mapping)
      }
    }
    //mapping.foreach { mp =>
    //  design.top.vins.foreach { vin =>
    //    mp.vomap.get(vin.writer).foreach { pvout =>
    //      val dvo = mp.vomap.pmap(pvout).asInstanceOf[DVO] 
    //      val label = s"${dvo.vector}[\n${dvo.vector.scalars.mkString(",\n")}]"
    //      val attr = DotAttr().label(label).color(indianred).style(bold)
    //      emitEdge(s"${mp.clmap(dvo.ctrler)}:$pvout", design.top, attr)
    //    }
    //  }
    //}
  }

  def print:Unit = { print(design.mapping) }

  def print(mapping:PIRMap):Unit = {
    emitBlock("digraph G") {
      design.arch match {
        case pn:PointToPointNetwork if (mapping!=null) =>
          print(pn.cus :+ pn.top, mapping)
        case sn:SwitchNetwork if (mapping!=null) =>
          print((sn.cus :+ sn.top, sn.sbs.flatten), mapping)
        case pn:PointToPointNetwork if (mapping==null) =>
          print(pn.cus :+ pn.top)
        case sn:SwitchNetwork if (mapping==null) =>
          print((sn.cus :+ sn.top, sn.sbs.flatten))
      }
    }
    close
  }

  def print(pcls:List[PCL]):Unit = {
    emitPCLs(pcls, None)
  }

  def print(res:(List[PCL], List[SwitchBox])):Unit = {
    val (pcls, sbs) = res
    emitPCLs(pcls, None); emitSwitchBoxes(sbs, None)
  }

  def print(pcls:List[PCL], mapping:PIRMap):Unit = {
    emitPCLs(pcls, Some(mapping))
  }

  def print(res:(List[PCL], List[SwitchBox]), mapping:PIRMap):Unit = {
    val (pcls, sbs) = res
    emitPCLs(pcls, Some(mapping))
    emitSwitchBoxes(sbs, Some(mapping))
  }
}

object ArgDotPrinter extends Metadata{
  def print(ptop:PTop)(printer:DotCodegen)(implicit design:Design) = {
    implicit val spade = design.arch
    def quote(n:Any) = printer.quote(n)
    ptop.vins.foreach { vin =>
      vin.fanIns.foreach { vout =>
        spade match {
          case sn:SwitchNetwork =>
            val bottom = coordOf(vout.src)._2==0 
            printer.emitEdge(quote(vout), quote(ptop, bottom))
          case pn:PointToPointNetwork =>
            printer.emitEdge(quote(vout), quote(ptop))
        }
      }
    }
    ptop.vouts.foreach { vout =>
      vout.fanOuts.foreach { vin =>
        spade match {
          case sn:SwitchNetwork =>
            val bottom = coordOf(vin.src)._2==0 
            printer.emitEdge(quote(ptop, bottom), quote(vin))
          case pn:PointToPointNetwork =>
            printer.emitEdge(ptop, quote(vin))
        }
      }
    }
  }
}
class ArgDotPrinter(fileName:String)(implicit design:Design) extends DotCodegen { 
  override val stream = newStream(fileName)

  def this()(implicit design:Design) = this(Config.spadeArgInOut)

  def print(pcus:List[PCU], ptop:PTop) = {
    emitBlock("digraph G") {
      pcus.foreach { pcu =>
        val recs = ListBuffer[String]()
        recs += s"{${pcu.vins.map(vin => s"<${vin}> ${vin}").mkString(s"|")}}" 
        recs += s"${pcu}"
        recs += s"<${pcu.vout}> ${pcu.vout}"
        val label = recs.mkString("|")
        emitNode(pcu, label, DotAttr().shape(Mrecord))
      }
      
      ArgDotPrinter.print(ptop)(this)
    }
    close
  }
}

class CtrDotPrinter(fileName:String) extends DotCodegen { 

  def this() = this(Config.spadeCtr)

  override val stream = newStream(fileName) 

  def print(pctrs:List[PCtr]) {
    emitBlock(s"digraph G") {
      pctrs.foreach { pctr =>
        val recs = ListBuffer[String]()
        recs += s"<en> en"
        recs += s"${pctr}"
        recs += s"<done> done"
        val label = recs.mkString(s"|")
        emitNode(pctr, label, DotAttr().shape(Mrecord))
        pctr.en.fanIns.foreach { from => 
          emitEdge(s"${s"${from}".replace(".", ":")}", s"${s"${pctr.en}".replace(".",":")}")
        }
      }
    }
    close
  }

  def emitMapping(pctrs:List[PCtr], mapping:PIRMap) = {
    val map = mapping.ctmap.map
    val pmap = mapping.ctmap.pmap
    pctrs.foreach { pctr =>
      val recs = ListBuffer[String]()
      val da = DotAttr().shape(Mrecord)
      recs += s"<en> en"
      recs += s"{${pctr}|${pmap.get(pctr).fold("no mapping"){c => da.color(red); c.toString }}}"
      recs += s"<done> done"
      val label = recs.mkString(s"|")
      emitNode(pctr, label, da)
      pctr.en.fanIns.foreach { from => 
        emitEdge(s"${s"${from}".replace(".", ":")}", s"${s"${pctr.en}".replace(".",":")}")
      }
    }
  }

  def print(pctrs:List[PCtr], mapping:PIRMap) = {
    emitBlock(s"digraph G") {
      emitMapping(pctrs, mapping)
    }
  }

  def print(pctrs:List[PCtr], ctrs:List[Ctr], mapping:PIRMap) {
    emitBlock(s"digraph G") {
      emitSubGraph("Mapping", "Mapping") {
        emitMapping(pctrs, mapping)
      }
      emitSubGraph("Nodes", "Nodes") {
        ctrs.foreach { ctr =>
          emitNode(ctr, ctr, DotAttr().shape(circle).color(indianred).style(filled))
          if (ctr.en.isConnected) 
            emitEdge(s"${ctr.en.from.src}".replace(".",":"), s"${ctr}".replace(".",":"))
        }
      }
    }
    close
  }
}

class SpadeDotGen(cuPrinter:CUDotPrinter, cuCtrlPrinter:CUCtrlDotPrinter, argInOutPrinter:ArgDotPrinter,
  ctrPrinter:CtrDotPrinter, pirMapping:PIRMapping)(implicit design: Design) extends Traversal {

  override def traverse = {
    cuPrinter.print
    cuCtrlPrinter.print
    ctrPrinter.print(design.arch.rcus.head.ctrs)
    argInOutPrinter.print(design.arch.cus, design.arch.top)
  }

  override def finPass = {
    info(s"Finishing Spade Dot Printing in ${cuPrinter.getPath} ${argInOutPrinter.getPath} ${ctrPrinter.getPath}")
  }
}
