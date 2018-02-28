package pir.codegen

import pir._
import pir.pass._
import prism.codegen._

trait PIRCodegen extends PIRTraversal with DFSTopDownTopologicalTraversal with Codegen {

  val forward = true
  val dirName = compiler.outDir

  def quote(n:Any):String = qdef(n)

  override def runPass = {
    traverseNode(compiler.top)
  }
  
}
