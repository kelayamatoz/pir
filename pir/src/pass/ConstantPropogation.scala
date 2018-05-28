package pir
package pass

import pir.node._
import prism.enums.{Op => _,_}

trait ConstantPropogator extends Ops {
  def getBoundOf(n:PIRNode, logger:Option[Logging]=None)(implicit pass:PIRPass):Option[Any] = dbgblk(logger, s"getBoundOf($n)") {
    import pass.pirmeta._
    implicit val design = pass.design
    boundOf.get(n).orElse {
      val bound = n match {
        case Def(n, Const(value)) => Some(value)
        case Def(n, High()) => Some(true)
        case Def(n, Low()) => Some(false)
        case Def(n, LocalLoad(mem::Nil, None)) => getBoundOf(mem, logger)
        case Def(n, LocalStore(mems, None, data)) => getBoundOf(data, logger)
        case WithWriter(writer) => getBoundOf(writer, logger)
        case Def(n, OpDef(op, inputs)) => evalOpt[Option[Int]](op, inputs.map { in => getBoundOf(in, logger) })
        case Def(n, GlobalInput(gout)) => getBoundOf(gout, logger)
        case Def(n, GlobalOutput(data, valid)) => getBoundOf(data, logger)
        case n => None
      }
      bound.foreach { boundOf(n) = _ }
      bound
    }
  }

  def getBoundAs[T:ClassTag](n:PIRNode, logger:Option[Logging]=None)(implicit pass:PIRPass):Option[T] = {
    getBoundOf(n, logger).map {
      case c:T => c
      case c => throw PIRException(s"getBoundOf($n) = $c but expect type ${implicitly[ClassTag[T]]}")
    }
  }

  def evalOpt[T](op:Op, ins:List[Any]):T = {
    ((op, ins) match {
      case (Bypass   , a::_)                         => a
      case (BitAnd   , a::Some(ToBool(false))::_)    => Some(false)
      case (BitAnd   , Some(ToBool(false))::b::_)    => Some(false)
      case (BitOr    , Some(ToBool(true))::b::_)     => Some(true)
      case (BitOr    , a::Some(ToBool(true))::_)     => Some(true)
      case (MuxOp    , Some(ToBool(true))::a::b::_)  => a
      case (MuxOp    , Some(ToBool(false))::a::b::_) => b
      case (op:Op1   , Some(a)::_)                   => Some(eval(op, List(a)))
      case (op:Op2   , Some(a)::Some(b)::_)          => Some(eval(op, List(a,b)))
      case (op:Op3   , Some(a)::Some(b)::Some(c)::_) => Some(eval(op, List(a,b,c)))
      case _                                         => None
    }).asInstanceOf[T]
  }

}
