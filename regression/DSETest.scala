import spatial._
import spatial.util.spatialConfig
import utils.io.files._
import scala.reflect.runtime.universe._

abstract class Param[T:TypeTag] extends Product {
  def prefix = this.getClass.getSimpleName.replace("Param","")
  def fields = typeOf[T].members.sorted.collect {
    case m: MethodSymbol if m.isCaseAccessor => m.name.toString
  }.toList
  override def toString = {
    val fs = fields.zip(productIterator.toList).map { case (f,v) => s"${f}_$v" }.mkString("_")
    s"${prefix}_$fs"
  }
}

trait DSETest extends SpatialTest { test =>
  def param:Param[_]

  override def compileArgs = s"--pir"

  val cmdlnArgs = sys.env.get("TEST_ARGS").getOrElse("").split(" ").map(_.trim).toList

  val psimHome = buildPath(IR.config.cwd, s"plastisim")
  val prouteHome = buildPath(IR.config.cwd, s"plastiroute")
  val pshPath = buildPath(IR.config.cwd, s"bin", "psh")

  val pirArgs:List[String] = 
    "bash" ::
    "run.sh" ::
    "--dot=false" ::
    "--trace=true" ::
    "--force-align" ::
    "--psim=true" ::
    "--run-psim=false" ::
    s"--tungsten=false" ::
    s"--psim-home=$psimHome" ::
    s"--proute-home=$prouteHome" ::
    Nil

  abstract class ExpBackend extends Backend(name, "", "", "") {
    override val name = this.getClass.getSimpleName.replace("$","")
    override def shouldRun: Boolean = checkFlag(s"test.${name}")
    def compileOnly = checkFlag(s"test.compileOnly")
    def runOnly = checkFlag(s"test.runOnly")
    override def genDir(name:String):String = s"${IR.config.cwd}/out/${this.name}/$name/"
    override def logDir(name:String):String = s"${IR.config.cwd}/out/${this.name}/$name/"
    override def repDir(name:String):String = s"${IR.config.cwd}/out/${this.name}/$name/"
    override def runBackend() = {
      s"${test.name}" should s"run for backend $name" in {
        val name = test.name
        IR.config.name = name
        IR.config.genDir = genDir(test.name)
        IR.config.logDir = logDir(test.name)
        IR.config.repDir = repDir(test.name)
        createDirectories(IR.config.genDir)
        createDirectories(IR.config.logDir)
        createDirectories(IR.config.repDir)
        val result = runPasses()
        result.resolve()
      }
    }
    def runPasses():Result

    implicit class ResultOp(result:Result) {
      def >> (next: => Result) = result match {
        case Pass => next
        case result => result.orElse(next)
      }
    }

    def genpir():Result = {
      import java.nio.file.{Paths, Files}
      val pirPath = IR.config.genDir + "/pir/AccelMain.scala"
      val pirExists = Files.exists(Paths.get(pirPath))
      val buildExists = Files.exists(Paths.get(IR.config.genDir + "/build.sbt"))
      if (pirExists && buildExists) {
        println(s"${Console.GREEN}${pirPath}${Console.RESET} succeeded. Skipping")
        Pass 
      } else {
        compile().next()()
      }
    }

    def scommand(pass: String, args: Seq[String], timeout: Long, parse: String => Result, Error: String => Result, rerun:Boolean=false): Result = {
      import java.nio.file.{Paths, Files}
      val logPath = IR.config.logDir + s"/$pass.log"
      var res:Result = Unknown
      if (Files.exists(Paths.get(logPath)) && !rerun) {
        res = scala.io.Source.fromFile(logPath).getLines.map { parse }.fold(res){ _ orElse _ }
      }
      if (res == Pass) {
        println(s"${Console.GREEN}${logPath}${Console.RESET} succeeded. Skipping")
        Pass 
      } else command(pass, args, timeout, parse, Error)
    }

    def runpir() = {
      var cmd = pirArgs :+
      "--load=false" :+
      "--mapping=false"
      cmd ++= cmdlnArgs
      def parse(line:String) = {
        if (line.contains("error")) Fail
        else if (line.contains("fail")) Fail
        else if (line.contains("Compilation succeed in")) Pass
        else Unknown
      }
      val timeout = 3000
      scommand(s"runpir", cmd, timeout, parse _, RunError.apply)
    }

    def mappir(args:String, fifo:Int=100) = {
      var cmd = pirArgs :+
      "--load=true" :+
      "--mapping=true" :+
      s"--fifo-depth=$fifo" :+
      "--stat" 
      cmd ++= args.split(" ").map(_.trim).toList
      cmd ++= cmdlnArgs
      def parse(line:String) = {
        if (line.contains("Compilation succeed in")) Pass
        else if (line.contains("Not enough resource of type")) Pass
        else Unknown
      }
      val timeout = 3000
      scommand(s"mappir", cmd, timeout, parse _, { case msg:String => println(msg); Pass})
    }

    def parseProute(vcLimit:Int)(line:String) = {
      val usedVc = if (line.contains("Used") && line.contains("VCs")) {
        Some(line.split("Used ")(1).split("VCs")(0).trim.toInt)
      } else None
      usedVc.fold[Result](Unknown) { usedVc =>
        if (vcLimit == 0 && usedVc > 0) Fail else Pass
      }
    }

    def runproute(row:Int=16, col:Int=8, vlink:Int=2, slink:Int=4, time:Int = -1, iter:Int=1000, vcLimit:Int=4, stopScore:Int= -1, rerun:Boolean=false) = {
      var cmd = s"${buildPath(prouteHome, "plastiroute")}" :: 
      "-n" :: s"${IR.config.genDir}/pir/plastisim/node.csv" ::
      "-l" :: s"${IR.config.genDir}/pir/plastisim/link.csv" ::
      "-v" :: s"${IR.config.genDir}/pir/plastisim/summary.csv" ::
      "-g" :: s"${IR.config.genDir}/pir/plastisim/proute.dot" ::
      "-o" :: s"${IR.config.genDir}/pir/plastisim/final.place" ::
      "-T" :: "checkerboard" ::
      "-a" :: "route_min_directed_valient" ::
      "-r" :: s"$row" ::
      "-c" :: s"$col" ::
      "-x" :: s"$vlink" ::
      "-e" :: s"$slink" ::
      "-S" :: s"$time" ::
      "-q" :: s"$vcLimit" ::
      "-E" :: s"$stopScore" ::
      "-s0" ::
      s"-i$iter" ::
      Nil
      cmd ++= "-p100 -t1 -d100".split(" ").map(_.trim).toList
      cmd ++= args.split(" ").map(_.trim).toList
      val timeout = 10800 * 2 // 6 hours
      val name = "runproute"
      scommand(name, cmd, timeout, parseProute(vcLimit) _, RunError.apply, rerun)
    }

    def parsePsim(line:String) = {
      if (line.contains("Simulation complete at cycle:")) {
        println(line)
        Pass
      }
      else if (line.contains("DEADLOCK") || line.contains("TIMEOUT")) Fail
      else Unknown
    }

    def runpsim(name:String="runpsim", flit:Int=512, linkTp:String="B", placefile:String="", rerun:Boolean=false) = {
      var cmd = s"${buildPath(psimHome, "plastisim")}" :: 
      "-f" :: s"${IR.config.genDir}/pir/plastisim/psim.conf" ::
      s"-i$flit" ::
      "-l" :: s"$linkTp" ::
      "-q1" ::
      Nil
      if (placefile != "") {
        cmd :+= "-p"
        cmd :+= placefile
      }
      cmd ++= args.split(" ").map(_.trim).toList
      val timeout = 10800 * 4 // 12 hours
      scommand(name, cmd, timeout, parsePsim _, RunError.apply, rerun)
    }

    def psh(ckpt:String) = {
      var cmd = pshPath ::
      s"--ckpt=$ckpt" ::
      Nil
      val timeout = 600
      command(s"psh", cmd, timeout, { case _ => Unknown}, RunError.apply)
    }

  }

  object Asic extends ExpBackend {
    def runPasses():Result = {
      genpir() >>
      runpir() >>
      mappir("--net=asic") >>
      runpsim()
    }
  }

  case class P2P(
    row:Int=16,
    col:Int=8,
  ) extends ExpBackend {
    override def shouldRun: Boolean = super.shouldRun || checkFlag(s"test.P2P")
    override val name = s"P${row}x${col}"
    def runPasses():Result = {
      genpir() >>
      runpir() >>
      mappir(s"--net=p2p --row=$row --col=${col}") >>
      runpsim()
    }
  }

  case class Hybrid(
    row:Int=16,
    col:Int=8,
    vlink:Int=2,
    slink:Int=4,
    time:Int= -1,
    scheduled:Boolean=false,
    iter:Int=1000,
    vcLimit:Int=4,
    linkTp:String="B",
    flit:Int=512,
    fifo:Int=100,
  ) extends ExpBackend {
    override def shouldRun: Boolean = super.shouldRun || checkFlag(s"test.Hybrid")
    override val name = {
      var n = s"H${row}x${col}v${vlink}s${slink}"
      if (time != -1) n += s"t${time}"
      if (vcLimit != 4) n += s"c${vcLimit}"
      if (scheduled) n += s"w"
      if (linkTp != "B") n += s"${linkTp}"
      if (flit != 512) n += s"f${flit}"
      if (fifo != 100) n += s"d${fifo}"
      n
    }
    def runPasses():Result = {
      genpir() >>
      runpir() >>
      mappir(s"--row=$row --col=$col --pattern=checkerboard --vc=$vcLimit --scheduled=${scheduled}", fifo=fifo) >>
      runproute(row=row, col=col, vlink=vlink, slink=slink, iter=iter, vcLimit=vcLimit) >>
      runpsim(placefile=s"${IR.config.genDir}/pir/plastisim/final.place", linkTp=linkTp, flit=flit)
    }
  }

  case class Static(
    row:Int=16,
    col:Int=8,
    vlink:Int=2,
    slink:Int=4,
    time:Int= -1,
    scheduled:Boolean=false,
    iter:Int=1000,
    linkTp:String="B",
    fifo:Int=100,
  ) extends ExpBackend {
    override def shouldRun: Boolean = super.shouldRun || checkFlag(s"test.Static")
    override val name = {
      var n = s"S${row}x${col}v${vlink}s${slink}"
      if (time != -1) n += s"t${time}"
      if (scheduled) n += s"w"
      if (linkTp != "B") n += s"${linkTp}"
      if (fifo != 100) n += s"d${fifo}"
      n
    }
    def runPasses():Result = {
      genpir() >>
      runpir() >>
      mappir(s"--row=$row --col=$col --pattern=checkerboard --vc=0 --scheduled=${scheduled}", fifo=fifo) >>
      runproute(row=row, col=col, vlink=vlink, slink=slink, iter=iter, vcLimit=0, stopScore=25) >>
      runpsim(placefile=s"${IR.config.genDir}/pir/plastisim/final.place", linkTp=linkTp)
    }
  }

  override def backends: Seq[Backend] = 
    Asic :: 
    P2P(row=14,col=14) :: 
    //Hybrid(row=14,col=14,vlink=1,slink=4) :: 
    //Static(row=14,col=14,vlink=1,slink=4) :: 
    super.backends

}
