package pir
package codegen

import pir.node._
import pir.pass._
import prism.graph._
import prism.codegen._

class PlastirouteLinkGen(implicit compiler: PIR) extends PlastisimCodegen with CSVCodegen {
  override def fileName = config.prouteLinkName

  lazy val script = "gen_link.py"

  def genScript(block: => Unit) = enterFile(config.psimOut, script)(block)

  override def runPass = {
    if (!noPlaceAndRoute) {
      genScript {
        emitln("""
import argparse

parser = argparse.ArgumentParser(description='Generate full link.csv based on partial csv')
parser.add_argument('-p', '--partial', type=str, help='Path to partial csv')
parser.add_argument('-d', '--dst', type=str, help='Path to destination csv to append to')

(opts, args) = parser.parse_known_args()
parsed = {}
with open(opts.partial, 'r') as f:
    for line in f:
        link,rest = line.strip().split(",", 1)
        parsed[link] = rest
with open (opts.dst, 'a') as f:
""")
      }
      setHeaders("link","ctx","src","tp","count","dst[0]","out[0]")
      super.runPass
      genScript {
emitln("""
    for link in parsed:
        f.write(link + "," + parsed[link])
""")
      }
      getStream(buildPath(config.psimOut, script)).get.close
    }
  }

  override def emitNode(n:N) = n match {
    case n:GlobalOutput => emitLink(n)
    case n => visitNode(n)
  }

  override def quote(n:Any) = n match {
    case n:GlobalIO => n.externAlias.v.getOrElse(s"$n")
    case _ => super.quote(n)
  }

  def emitLink(n:GlobalOutput) = {
    val intInputs = n.out.T.filterNot { _.isExtern.get }
    val intIns = intInputs.map{ in => s"${in.global.get.id},${quote(in)}" }.mkString(",")
    val ctx = n.in.T.ctx.get
    if (n.isExtern.get) {
      // Inputs to PIR module.
      if (n.isEscaped) {
        genScript {
          emitln(s"""    f.write("${quote(n)}," + parsed["${quote(n)}"] + ",$intIns\\n") # external output""")
          emitln(s"""    del parsed["${quote(n)}"]""")
        }
      }
    } else {
      if (n.out.T.exists { _.isExtern.get}) {
        genScript {
          val fields = List(
            quote(n),
            ctx.id,
            n.global.get.id,
            if (n.getVec == 1) 1 else 2,
            1000000,
            intIns
          )
          emitln(s"""    f.write("${fields.mkString(",")}" + parsed["${quote(n)}"] + "\\n") # internal output""")
          emitln(s"""    del parsed["${quote(n)}"]""")
        }
      } else {
        val row = newRow
        row("link") = quote(n)
        row("ctx") = ctx.id
        row("src") = n.global.get.id
        row("tp") = if (n.getVec == 1) 1 else 2 // 1 for scalar, 2 for vector
        //row("count") = n.constCount
        row("count") = n.count.get.getOrElse(1000000) //TODO: use more reasonable heuristic when count is not available
        n.out.T.zipWithIndex.foreach { case (gin, idx) =>
          if (!gin.isExtern.get) {
            row(s"dst[$idx]") = gin.global.get.id
            row(s"out[$idx]") = gin
          }
        }
      }
    }
  }
}
