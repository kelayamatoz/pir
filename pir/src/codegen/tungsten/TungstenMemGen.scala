package pir
package codegen

import pir.node._
import prism.graph._
import prism.codegen._
import scala.collection.mutable

trait TungstenMemGen extends TungstenCodegen with TungstenCtxGen {

  override def emitNode(n:N) = n match {
    case n:GlobalOutput =>
      val (tp, name) = varOf(n)
      genTop {
        emitln(s"""$tp $name("$n");""")
        dutArgs += name
      }
      genTopEnd {
        val bcArgs = n.out.T.map { out => varOf(out)._2.& }
        emitln(s"""Broadcast<Token> bc_$n("bc_$n", ${name.&}, {${bcArgs.mkString(",")}});""")
        dutArgs += s"bc_$n"
      }
      
    case n:GlobalInput =>
      val (tp, name) = varOf(n)
      genTop {
        emitln(s"""$tp $name("$n");""")
        dutArgs += name
      }
      genTopEnd {
        val bcArgs = n.out.T.map { out => varOf(out)._2.& }
        emitln(s"""Broadcast<Token> bc_$n("bc_$n", ${name.&}, {${bcArgs.mkString(",")}});""")
        dutArgs += s"bc_$n"
      }

    case n:LocalOutAccess =>
      val (tp, name) = varOf(n)
      genTop {
        emitln(s"""$tp $name("$n");""")
        dutArgs += name
      }
      addEscapeVar(n)
      genCtxInits {
        emitln(s"""inputs.push_back($name);""")
        if (n.initToken.get) {
          emitToken(s"init_$n", n.getVec, n.inits.get)
          emitln(s"$name->Push(init_$n);")
        }
      }
      emitVec(n)(s"toT<${n.tp}>($name->Read(), ${if (n.getVec==1) "0" else "i" })")
      genCtxComputeEnd {
        emitIf(s"${n.done.qref}") {
          emitln(s"$name->Pop();")
        }
      }

    case n:BufferWrite if n.data.T.isInstanceOf[DRAMCommand] =>

    case n:BufferWrite if n.data.T.isInstanceOf[BankedRead] =>
      n.out.T.foreach { send =>
        addEscapeVar(send)
        genCtxInits {
          emitln(s"AddSend(${nameOf(send)});");
        }
        genCtxEval {
          emitAccess(n.data.T.as[Access], prev=true) { buffer =>
            emitIf(s"${n.done.qref}") {
              emitln(s"${send}->Push($buffer->ReadData());");
            }
          }
        }
      }

    case n:BufferWrite =>
      val (tp, name) = varOf(n)
      emitNewMember(tp, name)
      val ctx = n.ctx.get
      n.out.T.foreach { send =>
        if (!send.isDescendentOf(ctx)) addEscapeVar(send)
        genCtxInits {
          emitln(s"AddSend(${nameOf(send)}, $name);")
        }
      }
      emitIf(s"${n.done.qref} && ${n.en.qref}") {
        emitln(s"$name->Push(make_token(${n.data.qref}));")
      }

    case n:TokenWrite =>
      n.out.T.foreach { send =>
        addEscapeVar(send)
        genCtxInits {
          emitln(s"AddSend(${nameOf(send)});");
        }
        genCtxComputeEnd {
          emitIf(s"${n.done.qref}") {
            emitln(s"${nameOf(send)}->Push(make_token(true));")
          }
        }
      }

    case n:Memory =>
      val (tp, name) = varOf(n)
      val accesses = n.accesses.filterNot { _.port.isEmpty }
      emitln(s"""$tp $name("$n", {${accesses.map { a => s""""$a"""" }.mkString(",")}});""")
      dutArgs += name

    case n:MemRead if n.mem.T.isFIFO =>
      val mem = n.mem.T
      addEscapeVar(mem)
      emitEn(n.en)
      emitln(s"int $n = 0;")
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
        emitln(s"$mem->Push(${n.data.qref});")
      }

    case n:BankedRead =>
      addEscapeVar(n.mem.T)
      emitAccess(n) { mem =>
        emitln(s"${mem}->SetupRead(make_token(${n.offset.qref}));")
      }
      genCtxComputeEnd {
        emitln(s"""${n.mem.T}->SetDone("$n", ${n.done.qref});""")
      }

    case n:BankedWrite =>
      addEscapeVar(n.mem.T)
      emitIf(s"${n.en.qref}") {
        emitAccess(n) { mem =>
          emitln(s"${mem}->Write(make_token(${n.data.qref}), make_token(${n.offset.qref}));")
        }
      }
      genCtxComputeEnd {
        emitln(s"""${n.mem.T}->SetDone("$n", ${n.done.qref});""")
      }

    //case n:MemRead =>
      //emitAccess(n) { mem =>
        //emitln(s"auto $n = ${mem}->Read();")
      //}

    //case n:MemWrite =>
      //emitAccess(n) { mem =>
        //emitln(s"if (${n.en.qref}) ${mem}->Write(${n.data.T});")
      //}

    case n => super.emitNode(n)
  }

  override def quoteRef(n:Any):String = n match {
    case n@InputField(access:Access, "done") if !n.as[Input[PIRNode]].isConnected => "false"
    case n@OutputField(access:BankedRead, "valid") => s"${access}_buffer->ReadValid()"
    case n => super.quoteRef(n)
  }

  def emitAccess(n:Access, prev:Boolean=false)(func:String => Unit) = {
    val mem = n.mem.T
    if (n.port.nonEmpty) {
      if (!prev)
        emitln(s"""auto* ${n}_buffer = $mem->GetBuffer("$n");""")
      else
        emitln(s"""auto* ${n}_buffer = $mem->GetPrevBuffer("$n");""")
      func(s"${n}_buffer")
    } else {
      emitBlock(s"for (auto* ${n}_buffer: $mem->buffers)") {
        func(s"""${n}_buffer""");
      }
    }
  }

  override def varOf(n:PIRNode):(String,String) = n match {
    case n:GlobalOutput =>
      (s"FIFO<Token, 4>", s"$n")
    case n:GlobalInput =>
      (s"FIFO<Token, 2>", s"$n")
    case n:LocalOutAccess =>
      (s"FIFO<Token, 4>", s"fifo_$n") //TODO
    case n:LocalInAccess =>
      val data = n match {
        case n:BufferWrite => n.data.T
        case n:TokenWrite => n.done.T
      }
      val pipeDepth = data match {
        case data:BankedRead => 1
        case _ => numStagesOf(n.ctx.get)
      }
      (s"ValPipeline<Token, $pipeDepth>", s"pipe_$n")
    case n:FIFO =>
      (s"FIFO<int, ${n.getDepth}>", s"$n")
    case n:SRAM =>
      val numBanks = n.getBanks.product
      (s"NBuffer<BankedSRAM<int, ${n.capacity/n.getDepth}, ${n.nBanks}>, ${n.getDepth}>", s"$n")
    case n:LUT =>
      (s"NBuffer<BankedSRAM<int, ${n.capacity/n.getDepth}, ${n.nBanks}>, ${n.getDepth}>", s"$n")
    case n:Reg =>
      (s"NBuffer<Register<int>, ${n.getDepth}>", s"$n")
    case n => super.varOf(n)
  }

}
