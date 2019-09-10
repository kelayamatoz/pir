package pir
package codegen

import pir.node._
import prism.graph._
import spade.param._

class TungstenRunner(implicit compiler: PIR) extends PIRPass with Printer {

  override def runPass = {
    val conf = config
    import conf._
    withOpen(config.appDir, s"runtst.sh", false) {
      emitln(s"""cd tungsten""")
      emitln("rm -f tungsten")
      emitln(s"""make""")
      //if (!noPlaceAndRoute) {
        //emitln("""cp script_hybrid script""")
      //} else {
        //emitln("""cp script_p2p script""")
      //}
      emitln("""./tungsten "$@"""")
    }
    deleteFile(tstLog)
    var failed = false
    val exitCode = shellProcess("tungsten", s"bash runtst.sh", config.appDir, tstLog) { line =>
      if (line.contains("error") || line.contains("exception")) {
        failed = true
        info(Console.RED, s"tungsten", line)
      } else if (line.contains("PASS: true")) {
        info(Console.GREEN, s"tungsten", line)
      } else if (line.contains("PASS: false")) {
        info(Console.RED, s"tungsten", line)
        fail(s"Validation failed. details in $tstLog")
      } else if (line.contains("Simulation complete at")) {
        info(Console.GREEN, s"tungsten", line)
      }
    }
    if (exitCode != 0 || failed) {
      fail(s"Tungsten failed. details in $tstLog")
    }
    shell(header=Some("tungsten"), command=s"python bin/annotate.py", cwd=Some(config.tstOut))
    if (exitCode == 0 && !failed && config.printStat) {
      shell(header=Some("tungsten"), command=s"python bin/simstat.py", cwd=Some(config.tstOut), processLambda=Some(println(_))) 
    }
    
  }

}
