package pir
package codegen

import pir.node._
import prism.graph._
import prism.codegen._
import scala.collection.mutable

trait TungstenMemGen extends TungstenCodegen {

  override def emitNode(n:N) = n match {
    case n:LocalOutAccess =>
      genFields {
        val depth = n.depth.get
        emitln(s"""FIFO<Token, $depth> *fifo_${n} = new FIFO<Token, $depth>("$n");""")
      }
      genInits {
        emitln(s"""addInput(fifo_$n);""")
        emitln(s"""AddChild(fifo_$n);""")
      }
      val readByFringe = n.out.T.exists { _.isInstanceOf[DRAMCommand] }
      if (!readByFringe) {
        emitVec(n)(s"fifo_${n}->Read().floatVec_${if (n.getVec==1) "[0]" else "[i]"};")
        emitIf(s"${n.done.T}") {
          emitln(s"fifo_$n->Pop();")
        }
      }

    case n:LocalInAccess =>
      val data = n match {
        case n:BufferWrite => n.data.T
        case n:TokenWrite => n.done.T
      }
      val reads = n.out.T //TODO Consider GlobalOutput
      val send = s"fifo_$n"
      genTop {
        emitln(s"""FIFO<Token, 4> $send("$send");""")
      }
      genTopEnd {
        val bcArgs = reads.map { read => s"ctx_${read.ctx.get}->fifo_${read}" }
        emitln(s"""Broadcast<Token> bc_$n("bc_$n", ${send.&}, ${quote(bcArgs)});""")
        dutArgs += s"bc_$n"
      }
      addEscapeVar(s"CheckedSend<Token>", s"$send")
      data match {
        case data:DRAMCommand =>
        case data:BankedRead =>
          genPush {
            emitIf(s"${data}->Valid()") {
              emitln(s"Token ${n}_respond = Token();")
              emitBlock(s"for (int i = 0; i < ${n.getVec}; i++)") {
                emitln(s"$n.floatVec_[i] = ${data}->ReadData()[i];")
              }
              emitln(s"$send->Push(${n}_respond);")
            }
          }

        case data =>
          val pipe = s"pipe_$n"
          genFields {
            emitln(s"""ValPipeline<Token, $numStages> *$pipe = new ValPipeline<Token, $numStages>("$pipe");""")
          }
          genInits {
            emitln(s"""AddChild($pipe);""")
            emitln(s"""addSend($send, $pipe);""")
          }
          emitIf(s"${n.done.T}") {
            emitln(s"Token ${n} = Token();")
            emitBlock(s"for (int i = 0; i < ${n.getVec}; i++)") {
              emitln(s"$n.floatVec_[i] = ${quoteRef(data).cast("float")};")
            }
            emitln(s"$pipe->Push($n);")
          }
      }

    case n:SRAM =>
      val banks = n.banks.get
      val bankIds = banks.foldLeft[List[List[Int]]](Nil) { case (prev, nbank) =>
        if (prev.isEmpty) (0 until nbank).map { id => List(id) }.toList
        else prev.flatMap { ids => (0 until nbank).map { id => ids :+ id } }
      }
      emitln(s"""${tpOf(n)} $n("$n", ${quote(bankIds)});""")
      n.inAccesses.foreach { inAccess =>
        emitln(s"$n.addWriter($inAccess);")
      }

    case n:LUT =>
      val banks = n.banks.get
      val bankIds = banks.foldLeft[List[List[Int]]](Nil) { case (prev, nbank) =>
        if (prev.isEmpty) (0 until nbank).map { id => List(id) }.toList
        else prev.flatMap { ids => (0 until nbank).map { id => ids :+ id } }
      }
      emitln(s"""${tpOf(n)} $n("$n", ${quote(bankIds)});""")
      n.inAccesses.foreach { inAccess =>
        emitln(s"$n.addWriter($inAccess);")
      }

    case n:BankedRead =>
      val mem = n.mem.T
      val reader = s"reader_${n}"
      addEscapeVar(tpOf(n), reader)
      genTop {
        emitln(s"""${tpOf(n)} $reader("$n", ${n.isBroadcast});""")
        emitln(s"$mem.addReader($reader);")
      }
      emitBankOffset(n)
      emitln(s"${n}->SetupRead(&${n}_bank, &${n}_offset, (bool) ${n.done.T.getOrElse(false)});")

    case n:BankedWrite =>
      val mem = n.mem.T
      addEscapeVar(n)
      genTop {
        emitln(s"""${tpOf(n)} $n("$n", ${n.isBroadcast});""")
        //emitln(s"$mem.addWriter($n);")
      }
      emitBankOffset(n)
      emitln(s"${n}->Write(&${n}_bank, &${n}_offset, &${n.data.T}, (bool) ${n.done.T.getOrElse(false)});")

    case n:FIFO => 
      emitln(s"""${tpOf(n)} $n("$n");""")

    case n:MemRead if n.mem.T.isFIFO =>
      val mem = n.mem.T
      addEscapeVar(mem)
      emitEn(n.en)
      emitln(s"float $n = 0;")
      emitIf(s"${n}_en"){
        emitln(s"${n} = $mem->Read();")
        emitIf(n.done.T) {
          emitln(s"${mem}.Pop();")
        }
      }

    case n:MemWrite if n.mem.T.isFIFO =>
      val mem = n.mem.T
      addEscapeVar(mem)
      emitEn(n.en)
      emitIf(s"${n}_en && ${n.done.T}"){
        emitln(s"$mem->Push(${n.data.T});")
      }

    case n:Reg =>
      emitln(s"""${tpOf(n)} $n("$n");""")
      n.inAccesses.foreach { inAccess =>
        emitln(s"$n.addWriter($inAccess);")
      }

    case n:MemRead =>
      val mem = n.mem.T
      val reader = s"reader_${n}"
      addEscapeVar(n)
      genTop {
        emitln(s"""${tpOf(n)} $reader("$n", ${n.isBroadcast});""")
        emitln(s"$mem.addReader($reader);")
      }
      emitEn(n.en)
      emitln(s"${n}->Read((bool) ${n.done.T.getOrElse(false)}, ${n}_en);")

    case n:MemWrite =>
      val mem = n.mem.T
      addEscapeVar(n)
      genTop {
        emitln(s"""${tpOf(n)} $n("$n", ${n.isBroadcast});""")
        //emitln(s"$mem.addWriter($n);")
      }
      emitEn(n.en)
      emitln(s"${n}->Write(&${n}_bank, &${n}_offset, &${n.data.T}, (bool) ${n.done.T.getOrElse(false)}, ${n}_en);")

    case n => super.emitNode(n)
  }

  def emitBankOffset(n:BankedAccess) = {
    val mem = n.mem.T
    val vec = n.getVec
    emitln(s"std::array<std::array<int, ${mem.bankDims}>,$vec> ${n}_bank;")
    emitBlock(s"for (int i = 0; i < $vec; i++)") {
      n.bank.T.zipWithIndex.foreach { case (bank, i) =>
        emitln(s"${n}_bank[i][$i] = (int) ${quoteRef(bank)};")
      }
    }
    emitln(s"std::array<int,$vec> ${n}_offset;")
    emitBlock(s"for (int i = 0; i < $vec; i++)") {
      emitln(s"${n}_offset[i] = (int) ${quoteRef(n.offset.T)};")
    }
  }

  override def tpOf(n:PIRNode):String = n match {
    case n:FIFO =>
      s"FIFO<float, ${n.getDepth}>"
    case n:SRAM =>
      val numBanks = n.getBanks.product
      s"BufferedBankedSRAM<float, ${n.getDepth}, ${n.bankDims}, ${numBanks}>"
    case n:LUT =>
      val numBanks = n.getBanks.product
      s"BufferedBankedSRAM<float, ${n.getDepth}, ${n.bankDims}, ${numBanks}>"
    case n:BankedRead =>
      s"BufferedBankedReader<float, ${n.mem.T.getDepth}, ${n.mem.T.bankDims}, ${n.getVec}>"
    case n:BankedWrite =>
      s"BufferedBankedWriter<float, ${n.mem.T.getDepth}, ${n.mem.T.bankDims}, ${n.getVec}>"
    case n:Reg =>
      s"BufferedReg<float, ${n.getDepth}>"
    case n:MemWrite if !n.mem.T.isFIFO =>
      s"BufferedWriter<float, ${n.mem.T.getDepth}>"
    case n:MemRead if !n.mem.T.isFIFO =>
      s"BufferedReader<float, ${n.mem.T.getDepth}>"
    case n => super.tpOf(n)
  }

}
