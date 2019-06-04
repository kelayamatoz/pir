package pir
package codegen

import pir.node._
import prism.graph._
import scala.collection.mutable

trait TungstenDRAMGen extends TungstenCodegen with TungstenCtxGen {

  val addrgens = mutable.ListBuffer[String]()

  override def finPass = {
    val cmds = addrgens.map{ i => s"&$i.burstcmd" }.mkString(",")
    val resps = addrgens.map{ i => s"&$i.burstrsp" }.mkString(",")
    val dramFile = buildPath(config.tstHome, "ini", "DRAM.ini")
    val systemFile = buildPath(config.tstHome, "ini", "system.ini")
    genTopFinalMember("DRAMController", "DRAM", Seq("DRAM".qstr, dramFile.qstr, systemFile.qstr, ".".qstr, s"{$cmds}", s"{$resps}"), extern=true)
    super.finPass
  }

  override def emitNode(n:N) = n match {
    case DRAMContext(cmd) => super.visitNode(n)

    case n:DRAMAddr =>
      emitln(s"${n.qtp} $n = (${n.qtp}) ${n.dram.sname.get};")

    case n:FringeDenseLoad =>
      val (tp, name) = varOf(n)
      val offset = nameOf(n.offset.T.as[BufferRead]).&
      val size = nameOf(n.size.T.as[BufferRead]).&
      val data = nameOf(n.data.T.as[BufferWrite].gout.get).&
      genTopFinalMember(tp, name, Seq(n.qstr, offset, size, data))
      addrgens += s"$n"

    case n:FringeDenseStore =>
      val (tp, name) = varOf(n)
      val offset = nameOf(n.offset.T.as[BufferRead]).&
      val size = nameOf(n.size.T.as[BufferRead]).&
      val data = nameOf(n.data.T.as[BufferRead]).&
      val valid = nameOf(n.valid.T.as[BufferRead]).&
      val ack = nameOf(n.ack.T.as[BufferWrite].gout.get).&
      genTopFinalMember(tp, name, Seq(n.qstr, offset, size, data, valid, ack))
      addrgens += s"$n"

    case n:FringeSparseLoad =>
      val (tp, name) = varOf(n)
      val addr = nameOf(n.addr.T.as[BufferRead]).&
      val data = nameOf(n.data.T.as[BufferWrite].gout.get).&
      genTopFinalMember(tp, name, Seq(n.qstr, addr, data))
      addrgens += s"$n"

    case n:FringeSparseStore =>
      val (tp, name) = varOf(n)
      val addr = nameOf(n.addr.T.as[BufferRead]).&
      val data = nameOf(n.data.T.as[BufferRead]).&
      val ack = nameOf(n.ack.T.as[BufferWrite].gout.get).&
      genTopFinalMember(tp, name, Seq(n.qstr, addr, data, ack))
      addrgens += s"$n"

    case n:CountAck =>
      emitln(s"bool $n = true;")

    case n => super.emitNode(n)
  }

  override def varOf(n:PIRNode):(String,String) = n match {
    case n:FringeDenseLoad => (s"DenseLoadAG<${n.data.getVec}, ${spadeParam.burstSizeByte}, ${n.data.qtp}>", s"${n}")
    case n:FringeDenseStore => (s"DenseStoreAG<${n.data.getVec}, ${spadeParam.burstSizeByte}, ${n.data.qtp}>", s"${n}")
    case n:FringeSparseLoad => (s"SparseLoadAG<${n.data.getVec}, ${spadeParam.burstSizeByte}, ${n.data.qtp}>", s"${n}")
    case n:FringeSparseStore => (s"SparseStoreAG<${n.data.getVec}, ${spadeParam.burstSizeByte}, ${n.data.qtp}>", s"${n}")
    case n => super.varOf(n)
  }

  override def quoteRef(n:Any):String = n match {
    case n@OutputField(x:DRAMCommand, field) => s"true"
    case n => super.quoteRef(n)
  }

}
