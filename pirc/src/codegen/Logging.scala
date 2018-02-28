package prism.codegen

import pirc._

import java.io.FileOutputStream

trait Logging {

  private val debug = Config.debug
  val logger = new Printer {
    override def emit(s:String):Unit = if (debug) { super.emit(s); flush }
    override def emitln(s:String):Unit = if (debug) { super.emitln(s); flush }

    override def emitBlock[T](bs:Option[String], b:Option[Braces], es:Option[()=>String])(block: =>T):T = { 
      emitBSln(bs, b, None)
      val res = block
      val resHeader = s"result${bs.fold("") { bs => s" [$bs]"}} ="
      res match {
        case res:Unit =>
        case res:Iterable[_] =>
          dbg(resHeader)
          res.foreach { res => dbg(s" - $res") }
        case res:Iterator[_] =>
          dbg(resHeader)
          res.foreach { res => dbg(s" - $res") }
        case res => dbg(resHeader + s" $res")
      }
      emitBEln(None, b, es.map(es => es()))
      res
    }
  }

  private def promp(header:Option[String], s:Any) = s"${header.fold("") { h => s"[$h] "}}$s"

  def dbgblk[T](header:Option[String], s:String)(block: =>T):T = logger.emitBlock(promp(header, s))(block)
  def dbgblk[T](s:String)(block: =>T):T = dbgblk(None, s)(block)

  def dbg(pred:Boolean, header:Option[String], s:Any):Unit = if (pred) logger.emitln(promp(header, s))
  def dbg(pred:Boolean, header:String, s:Any):Unit = dbg(pred, Some(header), s) 
  def dbg(header:String, s:Any):Unit = dbg(debug, header, s) 
  def dbg(pred:Boolean, s:Any):Unit = dbg(pred, None, s) 
  def dbg(s:Any):Unit = dbg(debug, None, s) 
  def dbsln(s:String):Unit = logger.emitBSln(s)
  def dbeln(s:String):Unit = logger.emitBEln(s)
  def dbeln:Unit = logger.emitBEln("")

}
