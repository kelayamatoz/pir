package pir
package pass

import pir.node._
import pir.mapper._
import prism.graph._
import spade.param._
import scala.collection.mutable

trait GlobalMemoryLowering extends GenericMemoryLowering {
  override def visitNode(n:N) = n match {
    case n:Memory if !n.isLockSRAM => createMemGlobal(n)
    case _ => super.visitNode(n)
  }

  private def lowerLUT(mem:Memory):Unit = {
    if (!mem.isInstanceOf[LUT]) return
    val lut = mem.as[LUT]
    dbgblk(s"lowerLUT($lut)") {
      val bank = lut.outAccesses.map { _.getVec }.sum
      dbg(s"bank=$bank")
      mem.banks.reset
      mem.banks := List(bank)
      var bankid = 0
      lut.outAccesses.foreach { outAccess =>
        val read = outAccess.as[BankedRead]
        // Use offset to carry bank address fornow. After flatten bank address,
        // swap bank addr with offset. 
        read.offset.disconnect
        val bk = within(read.parent.get, read.getCtrl) {
          stage(Const(List.tabulate(read.getVec) { i => bankid + i }).out)
        }
        read.offset(bk)
        bankid += read.getVec
      }
    }
  }

  private def lowerLUTAccess(mem:Memory, access:Access):Unit = {
    if (!mem.isInstanceOf[LUT]) return
    val lut = mem.as[LUT]
    val read = access.as[BankedRead]
    dbgblk(s"lowerLUTAccess($mem, $access)") {
      val offset = read.bank.singleConnected.get
      read.bank.disconnect
      read.bank(read.offset.singleConnected)
      read.offset.disconnect
      read.offset(offset)
    }
  }

  // Allocate one new Context per controller. This approach might not work if the address
  // calculation of different accesses in the same controller are data dependent on some other
  // memory access, which will create cycle on that context
  private val addrCtxs = mutable.Map[ControlTree, Context]()
  private def createMemGlobal(mem:Memory) = dbgblk(s"createMemGlobal($mem)"){
    val memCU = within(pirTop) { MemoryContainer() }
    // Create Memory Context
    swapParent(mem, memCU)
    lowerLUT(mem)
    val bankids = (0 until mem.banks.get.product).toList
    mem.bankids := bankids
    val accesses = mem.accesses
    List(mem.inAccesses, mem.outAccesses).foreach { accesses =>
      groupAccess(mem, accesses).foreach { group =>
        group.head match {
          case _:BankedAccess => 
            lowerBankedAccesses(mem, memCU, group.asInstanceOf[Set[BankedAccess]])
          case _ => 
            lowerAccess(mem, memCU, assertOne(group, s"$mem.access group"))
        }
      }
    }
    consistencyBarrier(mem.accesses)(dependsOn){ case (from,to,carried) =>
      insertBarrier(from,to,carried)
    }
    mem.accesses.foreach { access =>
      val ctx = access.ctx.get
      bufferInput(ctx, fromCtx=addrCtxs.get(access.getCtrl))
    }
    addrCtxs.clear
  }

  // Remove accesses that are been broadcasted
  private def resolveBroadcast(accesses:List[Access]):List[Access] = {
    accesses.groupBy { _.castgroup.v }.flatMap { 
      case (None, accesses) => accesses
      case (Some(grp), accesses) =>
        val (heads, tail) = accesses.partition { a => 
          val broadcast = assertIdentical(a.broadcast.get, s"$a.broadcast").get
          broadcast == 0
        }
        val head = assertOne(heads, 
          s"broadcast in castgroup $grp for ${accesses.head.mem} ${accesses}")
        tail.foreach { tail =>
          (head, tail) match {
            case (head:BankedRead, tail:BankedRead) =>
              swapOutput(tail.out, head.out)
              tail.mem.disconnect
            case (head, tail) => err(s"Invalid broadcast from $head to $tail")
          }
        }
        if (tail.nonEmpty) 
          dbg(s"broadcast $head => $tail")
        removeNodes(tail)
        List(head)
    }.toList
  }

  private def groupAccess(mem:Memory, accesses:List[Access]):List[Set[Access]] = dbgblk(s"groupAccess($mem)") {
    accesses.groupBy { _.port.v }.flatMap { case (group, accesses) =>
      accesses.groupBy { _.muxPort.v }.map { case (muxPort, accesses) =>
        resolveBroadcast(accesses).toSet
      }
    }.toList
  }

  private def lowerAccess(mem:Memory, memCU:MemoryContainer, access:Access) = dbgblk(s"lowerAccess($mem, $memCU, $access)") {
    val ctx = within(memCU, access.ctx.get.getCtrl) { Context() }
    if (mem.isFIFO) {
      ctx.streaming(false)
    } else {
      ctx.streaming(true)
    }
    swapParent(access, ctx)
    access match {
      case access:MemRead =>
        bufferOutput(access.out)
      case access:MemWrite =>
        if (!mem.isFIFO) {
          bufferInput(access.data)
          bufferInput(access.en)
        }
        //val writeEns = access.en.T
        //dbg(s"writeEns=$writeEns")
        //val fromValid = writeEns.forall { case en:CounterValid => true }
        //if (!fromValid) bufferInput(access.en)
    }
    // Connect access.done
    if (mem.isFIFO) {
      access.done(childDone(access.getCtrl, ctx))
    } else if (mem.depth.get > 1 && access.port.get.nonEmpty) {
      val ctrlMap = leastMatchedPeers(mem.accesses.filterNot{_.port.get.isEmpty}.map { _.getCtrl} ).get
      val ctrl = ctrlMap(access.getCtrl)
      access.done(done(ctrl, ctx))
      bufferInput(access.done)
    }
  }

  private def lowerBankedAccesses(mem:Memory, memCU:MemoryContainer, accesses:Set[BankedAccess]) = dbgblk(s"lowerBankedAccesses($mem, $memCU, $accesses)") {
    val headAccess = accesses.head
    val mergeCtrl = headAccess.getCtrl
    val mergeCtx = within(memCU, headAccess.ctx.get.getCtrl) { Context() }
    // Optimize for fully unrolled case
    val constAddr = accesses.forall { access =>
      access.bank.connected.forall { case (OutputField(c:Const, "out")) => true; case _ => false } &&
      access.offset.connected.forall { case (OutputField(c:Const, "out")) => true; case _ => false } &&
      !access.en.isConnected
    }
    dbg(s"mergeCtrl = $mergeCtrl")
    dbg(s"mergeCtx=$mergeCtx")
    dbg(s"constAddr=$constAddr")
    val red = within(mergeCtx, mergeCtrl) {
      val requests = accesses.map { access =>
        val addrCtx = access match {
          //case access if accesses.size == 1 || constAddr => mergeCtx
          //case access:BankedWrite => access.ctx.get 
          case access => 
            addrCtxs.getOrElseUpdate(access.getCtrl, {
              within(memCU, access.ctx.get.getCtrl) { Context() }
            })
        }
        dbg(s"addrCtx for $access = $addrCtx")
        swapParent(access, addrCtx)
        flattenBankAddr(access)
        flattenEnable(access)
        lowerLUTAccess(mem, access)
        val bank = access.bank.connected
        val ofsOut = access.offset.singleConnected.get
        val ofs = stage(Shuffle(-1).from(bank).to(allocConst(mem.bankids.get)).base(ofsOut))
        bufferInput(ofs.base, fromCtx=Some(addrCtx))
        bufferInput(ofs.from, fromCtx=Some(addrCtx))
        val data = access match {
          case access:BankedWrite => 
            val shuffle = stage(Shuffle(0).from(bank).to(allocConst(mem.bankids.get)).base(access.data.connected))
            bufferInput(shuffle.base) // Prevent copying data producer into addrCtx
            bufferInput(shuffle.from, fromCtx=Some(addrCtx))
            Some(shuffle)
          case access => None
        }
        dbg(s"ofs:${dquote(ofs)} data:${data.map{dquote}}")
        (ofs.out, data.map { _.out })
      }
      var red:List[(Output[PIRNode], Option[Output[PIRNode]])] = requests.toList
      while(red.size > 1) {
        red = red.sliding(2,2).map{ 
          case List((o1, d1),(o2, d2)) =>
            val of = stage(OpDef(SelectNonNeg).addInput(o1, o2))
            val dt = zipOption(d1, d2).map { case (d1, d2) =>
              val dt = stage(OpDef(FixOr).addInput(d1,d2))
              dt
            }
            (stage(of.out), dt.map { dt => stage(dt.out) })
          case List((o1, d1)) => (o1, d1)
        }.toList
      }
      red
    }

    val List((ofs, data)) = red
    val accessCtx = within(memCU, headAccess.ctx.get.getCtrl) { Context().streaming(true) }
    val newAccess = within(accessCtx) {
      data.fold[FlatBankedAccess]{
        stage(FlatBankedRead().offset(ofs).mem(mem).mirrorMetas(headAccess))
      } { data => 
        stage(FlatBankedWrite().offset(ofs).data(data).mem(mem).mirrorMetas(headAccess))
      }
    }
    newAccess.to[FlatBankedRead].foreach { newAccess =>
      newAccess.out.vecMeta.reset
      newAccess.out.vecMeta := mem.nBanks
    }
    bufferInput(accessCtx)

    val addrCtx = addrCtxs(headAccess.getCtrl)
    // Connect access.done
    if (mem.depth.get > 1 && newAccess.port.get.nonEmpty) {
      val ctrlMap = leastMatchedPeers(mem.accesses.filterNot{_.port.get.isEmpty}.map { _.getCtrl} ).get
      val ctrl = ctrlMap(mergeCtrl)
      newAccess.done(done(ctrl, accessCtx))
      bufferInput(newAccess.done, fromCtx=Some(addrCtx))
    }

    newAccess.to[FlatBankedRead].foreach { newAccess =>
      accesses.asInstanceOf[Set[BankedRead]].foreach { access =>
        access.out.connected.distinct.groupBy { in => in.src.ctx.get }.foreach { case (inCtx, ins) =>
          val shuffle = within(inCtx, inCtx.getCtrl)  {
            stage(Shuffle(0).from(allocConst(mem.bankids.get)).to(access.bank.connected).base(newAccess.out))
          }
          dbg(s"val $shuffle = Shuffle() // bankRead")
          bufferInput(shuffle.base)
          bufferInput(shuffle.to, fromCtx=Some(addrCtxs(access.getCtrl)))
          ins.foreach { in =>
            swapConnection(in, access.out, shuffle.out)
          }
        }
      }
    }

    removeNodes(accesses)
    //breakPoint(s"lowerBankedAccesses $mem")
  }

  private def flattenBankAddr(access:BankedAccess):Unit = dbgblk(s"flattenBankAddr($access)"){
    val mem = access.mem.T
    val parent = access.parent.get
    within(parent, parent.getCtrl) {
      // Flatten BankeAddress
      if (access.bank.T.size > 1) {
        def flattenND(inds:List[Output[PIRNode]], dims:List[Int]):Output[PIRNode] = {
          if (inds.size==1) return inds.head
          assert(inds.size == dims.size, s"flattenND inds=$inds dims=$dims have different size for access=$access")
          val i::irest = inds
          val d::drest = dims
          stage(OpDef(FixFMA).addInput(i,allocConst(drest.product), flattenND(irest, drest)).out)
        }
        val dims = mem match {
          case mem:SRAM => mem.banks.get
          case mem:LUT => mem.dims.get
          case mem:RegFile => mem.dims.get
        }
        val fbank = flattenND(access.bank.connected.toList, dims)
        dbg(s"flattenBankAddr ${access.bank.T} => $fbank in $parent")
        access.bank.disconnect
        access.bank(fbank)
      }
    }
  }


  private def dependsOn(deped:Access, dep:Access):Boolean = {
    val lca = leastCommonAncesstor(deped.getCtrl, dep.getCtrl).get
    lca.schedule match {
      case Fork => return false
      case ForkJoin => return false
      case _ =>
    }
    val carried = dep.progorder.get > deped.progorder.get
    if (dep.getCtrl == deped.getCtrl) carried else !carried
  }

  private def insertBarrier(from:Access, to:Access, carried:Boolean) = {
    val token = insertToken(from.ctx.get, to.ctx.get)
    if (from.port.get.isEmpty || to.port.get.isEmpty) {
      token.depth(1)
    } else {
      val depth = to.port.get.get - from.port.get.get + 1
      token.depth(depth)
    }
    if (carried) {
      token.initToken := true
      token.inits := true
      // HACK to mem reduce. Allow writer and read to operate concurrently because we know they
      // don't overlap in range
      if (from.getCtrl == to.getCtrl) {
        token.depth.reset
        token.depth := 2
      }
    }
    dbg(s"$token.depth = ${token.depth.get}")
  }

  override def dquote(n:Any) = n match {
    case n:Memory => n.name.v.fold { n.toString } { name => s"${n}[$name]" }
    case n => super.dquote(n)
  }

}
