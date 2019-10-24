package pir
package pass

import pir.node._
import pir.mapper._
import prism.graph._
import scala.collection.mutable

trait MemoryAnalyzer extends PIRPass { self:PIRTransformer =>

  def insertToken(fctx:Context, tctx:Context, dep:Option[Output[PIRNode]]=None):TokenRead = {
    val isFIFO = false
    val fctrl = fctx.ctrl.get
    val tctrl = tctx.ctrl.get
    dbgblk(s"InsertToken(fctx=$fctx($fctrl), tctx=$tctx($tctrl))") {
      val (enq, deq) = compEnqDeq(isFIFO=isFIFO, fctx, tctx, None, Nil)
      val write = within(fctx, fctrl) {
        allocate[TokenWrite](_.done.isConnectedTo(enq)) {
          stage(TokenWrite().done(enq).dummy(dep))
        }
      }
      within(tctx, tctrl) {
        allocate[TokenRead](read => read.in.isConnectedTo(write.out) && read.done.isConnectedTo(deq)) {
          stage(TokenRead().in(write).done(deq))
        }
      }
    }
  }

  def compEnqDeq(isFIFO:Boolean, octx:Context, ictx:Context, out:Option[Output[PIRNode]], ins:Seq[Input[PIRNode]]):(Output[PIRNode], Output[PIRNode]) = {
    val from = out.map { _.src }
    val o = octx.ctrl.get
    val i = ictx.ctrl.get
    dbgblk(s"compEnqDeq(isFIFO=$isFIFO, o=${dquote(o)}, i=${dquote(i)})") {
      dbg(s"out=$from.$out")
      dbg(s"ins=${ins.map { in => s"${in.src}.$in"}.mkString(",")}")
      (out, ins) match {
        case (out,ins) if isFIFO => (childDone(o, octx), childDone(i, ictx))
        case (out,Seq(InputField(n:LoopController, "stopWhen"))) if o == i => (childDone(o, octx), childDone(i, ictx))
        case (out,ins) if o == i => (done(o, octx), done(i, ictx)) // This should be childDone other than hack for block reduce token
        case (out,ins) =>
          val lca = leastCommonAncesstor(o,i).get
          val oAncesstors = o.ancestorTree
          val iAncesstors = i.ancestorTree
          val oidx = oAncesstors.indexOf(lca)
          val iidx = iAncesstors.indexOf(lca)
          // Use def to prevent evaluation outside if statement to prevent idx out of bound
          // in case of one ctrl is ancesstor of another
          def octrl = oAncesstors(oidx-1)
          def ictrl = iAncesstors(iidx-1)
          val enq = if (lca == o) childDone(o, octx) else done(octrl, octx)
          val deq = if (lca == i) childDone(i, ictx) else done(ictrl, ictx)
          dbg(s"enqCtrl=${enq.src.getCtrl} deqCtrl=${deq.src.getCtrl}")
          (enq,deq)
      }
    }
  }

  def childDone(ctrl:ControlTree, ctx:Context):Output[PIRNode] = {
    val ctrler = if (ctx.streaming.get) {
      within(ctx, ctrl) { 
        allocate[UnitController]()(stage(UnitController().par(1)))
      }
    } else if (!compiler.hasRun[DependencyDuplication]) {
      // Centralized controller
      ctrl.ctrler.get
    } else {
       //Distributed controller
      ctx.getCtrler(ctrl)
    }
    ctrler.childDone
  }

  def done(ctrl:ControlTree, ctx:Context):Output[PIRNode] = {
    if (!compiler.hasRun[DependencyDuplication]) {
      // Centralized controller
      ctrl.ctrler.get.done
    } else {
      //Distributed controller
      ctx.getCtrler(ctrl).done
    }
  }

  def allocate[T<:PIRNode:ClassTag:TypeTag](
    filter:T => Boolean = (n:T) => true,
    allowDuplicates:Boolean = false
  )(newNode: => T):T = {
    val ct = implicitly[ClassTag[T]]
    val container = stackTop[PIRParent].getOrElse(bug(s"allocate[$ct] outside PIRParent env")).as[PIRNode]
    (container, classTag[T]) match {
      case (container:Top, ct) if ct == classTag[Const] => newNode // allocation is too expensive performance-wise, just get a new one
      case (container, ct) if ct == classTag[Const] => 
        container.children.find { case c:T => filter(c); case _ => false }.getOrElse { newNode }.as[T]
      case _ =>
        val nodes = container.collectDown[T]().filter(filter)
        val opt = if (allowDuplicates) nodes.headOption else assertOneOrLess(nodes, s"$ct under $container")
        opt.getOrElse {
          val node = within(container) { newNode }
          dbg(s"allocate[$ct](container=$container) = ${dquote(node)}")
          node
        }
    }
  }

  private def equalValue(a:Any, b:Any):Boolean = {
    (a,b) match {
      case (a:Iterable[_], b:Iterable[_]) => a.size == b.size && (a,b).zipped.forall { (a,b) => equalValue(a,b) }
      case (a,b) => a == b && (a.getClass == b.getClass)
    }
  }

  def allocConst(value:Any, tp:Option[BitType]=None) = allocate[Const] { c => 
    tp.fold(true) { _ == c.out.getTp } &&
    equalValue(c.value,value) &&
    stackTop[Ctrl].fold(true) { ctrl => c.getCtrl == ctrl }
  } { 
    val c = Const(value)
    tp.foreach { tp =>
      c.out.tpMeta.update(tp)
    }
    stage(c)
  }

  /*
   * W => W (fake dependency) partial updates
   * W => R (true dependency)
   * R => W (fake dependency)
   * R => R (fake dependency for SRAM [time multiplex read port])/(no dependency for DRAM)
   *
   * Dependency checking: 
   *  called on all access and their visible predecessors:
   * For A1, A2
   *  if sequential:
   *    if R => R for DRAM:
   *      no synchronization
   *    else:
   *      token(1)
   *  if pipeline/multibuffer:
   *    if buffer depth known(SRAM): 
   *      token(depth)
   *    if buffer depth unknown(DRAM)
   *      token(1) for true dependency
   *      no synchronization for fake dependency
   * For A2, A1:
   *  if sequential:
   *    if R => R for DRAM:
   *      no synchronization
   *    else:
   *      token(1).init(1)
   *  if piplined/multibuffer:
   *    if buffer depth known(SRAM):
   *      if sram access latency = 1 and A2,A1 single cycle apart
   *        no synchronization
   *      else:
   *        token(depth).init(depth)
   *    if buffer depth unknown(SRAM):
   *      token(1).init(1) for true dependency
   *      no synchronization for fake dependency
   *
   *  For DRAM since we don't know number of buffer the user have, we don't synchronize on pipelined
   *  fake dependency. 
   *  It's user's responsibility to handle backpressure or fake dependency either 
   *  with explicit token or
   *  make sure there's no need of synchronization because:
   *    1. "infinite" buffers, working on non-overlapping tiles. Most commonly used
   *    2. There is true carried dependency 
   *
   * Monotonicity: if A1 => A2 and there's no entry point between A1 and A2, then no need to
   * synchronize A2 with anyone else. This assume if exists A3 such that A3 => A2, then A3 => A1.
   * This is trivially true for A2.tp == A1.tp. When they are not equal, 
   * A3   A1   A2   Monotonicity
   * W    W    R        V
   * R    W    R        V
   * R    R    W        (X for DRAM, V for SRAM)
   * W    R    W        V
   *
   * To handle this, we first find all forward and carried accesses each access can depends on, then
   * perform transitive reduction on the forward dependency DAG and backward dependency DAG, and
   * finally insert tokens.
   * 
   * */
  def consistencyBarrier[A<:PIRNode](accesses:List[A])(dependsOn:(A,A) => Boolean)(insertBarrier:(A,A,Boolean) => Unit):List[UnrolledAccess[A]] = {
    val uas = accesses.groupBy { _.progorder.get }.map { 
      case (progorder, accesses) => 
        val lanes = accesses.head match {
          case access:Access => accesses.sortBy { _.as[Access].order.get }
          case access => accesses.sortBy { _.id }
        }
        UnrolledAccess(lanes)
    }.toList
    def insertBarrierUnrolled(predua:UnrolledAccess[A], ua:UnrolledAccess[A], carried:Boolean) = {
      if (dependsOn(ua.lanes.head, predua.lanes.head)) {
        predua.lanes.zipWithIndex.foreach { case (pred, predLane) =>
          ua.lanes.zipWithIndex.foreach { case (access, accessLane) =>
            dbgblk(s"insertBarrier(${dquote(predua)}[$predLane], ${dquote(ua)}[$accessLane], carried=$carried)") {
              insertBarrier(pred,access,carried)
            }
          }
        }
      }
    }
    def dependsOnUnrolled(a:UnrolledAccess[A],b:UnrolledAccess[A]) = {
      dependsOn(a.lanes.head, b.lanes.head)
    }
    unrolledConsistencyBarrier(uas)(dependsOnUnrolled)(insertBarrierUnrolled)
  }

  def unrolledConsistencyBarrier[A<:PIRNode](uas:List[UnrolledAccess[A]])(dependsOn:(UnrolledAccess[A],UnrolledAccess[A]) => Boolean)(insertBarrier:(UnrolledAccess[A],UnrolledAccess[A],Boolean) => Unit):List[UnrolledAccess[A]] = {
    val sorted = uas.sortBy { _.progorder }

    val mem = uas.head.lanes.head match {
      case access:Access => access.mem.T
      case fringe:DRAMCommand => fringe.dram
    }
    dbgblk(s"sorted(${dquote(mem)})") {
      sorted.foreach { ua =>
        dbg(s"UA[${ua.progorder}] ${ua.lanes.map{dquote}.mkString(",")}")
        dbg(s"- ${ua.srcCtx}")
      }
    }

    //def findPredecessors(ua:UnrolledAccess[A], ctrl:ControlTree):(List[UnrolledAccess[A]],List[UnrolledAccess[A]]) = {
      //val ancestor = ua.ctrl.ancestorUnder(ctrl).get
      //val scope = sorted.filter { other => 
        //val otherAncestor = other.ctrl.ancestorUnder(ctrl)
        //otherAncestor.fold(false) { otherAncestor =>
          //other == ua || // Include ua in the scope
          //otherAncestor.isEmpty || // Include other if other is the immediate child of ctrl
          //otherAncestor != ancestor // Include other if other is descendent of ctrl but doesn't share immediate child of ctrl with ua
        //}
      //}

      //// Search backward from position of ua to find the first dependency in the same scope
      //val (before, rest) = scope.span { _ != ua }
      //val (_,after) = rest.splitAt(1)
      //dbg(s"ua=${dquote(ua)} ctrl=$ctrl before=${before.map{dquote}} after=${after.map{dquote}}")

      //val forward = before.reverseIterator.filter { before => 
        //dependsOn(ua, before)
      //}.toList
      //val carried = if (ctrl.isLoop.get) {
        //after.reverseIterator.filter { after => 
          //dependsOn(ua,after)
        //}.toList
      //} else Nil

      //ctrl.parent.fold {
        //(forward, carried)
      //} { parentCtrl =>
        //val (outerForward, outerCarried) = findPredecessors(ua, parentCtrl)
        //(forward ++ outerForward, carried ++ outerCarried)
      //}
    //}

    def findPredecessors(ua:UnrolledAccess[A]):(List[UnrolledAccess[A]],List[UnrolledAccess[A]]) = {
      val (before, rest) = sorted.span { _ != ua }
      val (_,after) = rest.splitAt(1)

      val forward = before.filter { before => dependsOn(ua, before) }
      val carried = after.filter { after => 
        val lca = leastCommonAncesstor(ua.ctrl, after.ctrl).get
        if (lca.ancestorTree.exists { _.isLoop.get }) {
          dependsOn(ua, after)
        } else false
      }

      (forward, carried)
    }

    // Get an reachable sets for forward and carried dependency
    val (forward, carried) = sorted.map { ua =>
      val (forward,carried) = findPredecessors(ua)
      dbg(s"${dquote(ua)} forward: ${forward.map{dquote(_)}.mkString(",")} carried: ${carried.map{dquote(_)}.mkString(",")}")
      (ua -> forward,ua -> carried)
    }.unzip.map1 { _.toMap }.map2 { _.toMap }

    // Perform transitive reduction on the reachable set. Forward and carried separately to keep
    // them DAG.  
    val reducedForward = forward.map { case (ua, reachable) =>
      val reachedByNeighbors = reachable.flatMap { rua => forward(rua).toSet }
      ua -> reachable.filterNot { reachedByNeighbors.contains(_) }
    }

    val reducedCarried = carried.map { case (ua, reachable) =>
      val reachedByNeighbors = reachable.flatMap { rua => carried(rua).toSet }
      ua -> reachable.filterNot { reachedByNeighbors.contains(_) }
    }

    sorted.foreach { ua =>
      dbg(s"${dquote(ua)} rforward:${reducedForward(ua).map{dquote(_)}.mkString(",")} rcarried: ${reducedCarried(ua).map{dquote(_)}.mkString(",")}")
    }

    sorted.foreach { ua =>
      reducedForward(ua).foreach { prev =>
        insertBarrier(prev, ua, false)
      }
      reducedCarried(ua).foreach { prev =>
        insertBarrier(prev, ua, true)
      }
    }
    sorted
  }

  override def dquote(n:Any) = n match {
    case n:UnrolledAccess[_] => s"UA[${n.progorder}]"
    case _ => super.dquote(n)
  }

}

/*
 * Group of accesses belong to the same pre unrolled access, sorted by unrolling order
 * */
case class UnrolledAccess[T<:PIRNode](lanes:List[T]) {
  def progorder = lanes.head.progorder.get
  def ctrl = lanes.head.getCtrl
  def srcCtx = lanes.head.srcCtx.v.getOrElse(s"No source context")
}
