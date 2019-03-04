package pir

class PIRConfig(compiler:Compiler) extends spade.SpadeConfig(compiler) {

  /* ------------------- Compiler --------------------  */
  register("ctrl", default=true, info="Enable control logic generation")
  register("splitting", default=true, info="Enable splitting")
  register("splitting-algo", default="alias_weighted_igraph", info="splitting algorithm. [weighted_igraph, alias_igraph, alias_weighted_igraph]") 
  register("mapping", default=true, info="Enable mapping")
  register("arch", default="MyDesign", info="Default architecture for mapping")
  register("ag-dce", default=true, info="Enable aggressive dead code elimination")
  register("rt-elm", default=true, info="Enable route through elimination")
  register("stat", default=false, info="Printing statistics")
  register("force-align", default=false, info="Remove control signals that handle unaligned parallalization")
  register("shuffle-hack", default=false, info="Workaround to enable optimization of Shuffle elimination prevented by unrolling bug")
  register("igraph", default=false, info="Enable igraph codegen")

  def arch = option[String]("arch")
  def enableSplitting = option[Boolean]("splitting")
  def enableMapping = option[Boolean]("mapping")
  def enableRouteElim = option[Boolean]("rt-elm")
  def aggressive_dce = option[Boolean]("ag-dce")
  def printStat = option[Boolean]("stat")
  def forceAlign = option[Boolean]("force-align")
  def enableIgraph = option[Boolean]("igraph")

  /* ------------------- Routing --------------------  */
  register("routing-algo", default="dor", info="If net=[dynamic] - [dor, planed, proute]. Option ignored for other network. dor - dimention order routing. planed - arbitrary source routing, proute - use plastiroute for place and route. If proute is chosen plastiroute will be launched from pir if $PLASTIROUTE_HOME is set") 
  register("routing-cost", default="H-hop", info="Routing cost [hop, balanced, H-hop, H-balanced]. hop - use hop count only for cost in search, balanced - use traffic load + hop count as cost, H-hop: use Manhattan distance as heurisc cost and use hop count for cost. H-balanced: use Manhattan distance as heurisc cost and use hop count and traffic load for cost.") 
  def enableHopCountCost = option[String]("routing-cost") match {
    case "hop" => true
    case "balanced" => false
    case "H-hop" => true
    case "H-balanced" => false
  }

  def enableBalancedCost = option[String]("routing-cost") match {
    case "hop" => false
    case "balanced" => true
    case "H-hop" => false
    case "H-balanced" => true
  }

  def enableHeuristicCost = option[String]("routing-cost") match {
    case "hop" => false
    case "balanced" => false
    case "H-hop" => true
    case "H-balanced" => true
  }
  def routingAlgo = option[String]("routing-algo")

  /* ------------------- Plastisim --------------------  */
  register[String]("psim-home", default=sys.env.get("PLASTISIM_HOME"), info="Plastisim Home")
  register("psim", default=false, info="Enable code generations for plastisim")
  register("run-psim", default=false, info="Launch Plastisim simulation")
  register[String]("load-psim", info="Path to load psim log")
  register[Long]("psim-timeout", info="Maximum time out for psim")
  register[String]("psim-out", info="Directory to copy psim files over")
  register[Int]("psim-q", default=1, info="VC slowdown parameter")
  register("trace", default=false, info="Enable trace generation for simulation")
  register[String]("count", info="Annotation on count values")

  def genPsim = option[Boolean]("psim") && enableCodegen
  def runPsim = option[Boolean]("run-psim") && genPsim
  def loadPsim = getOption[String]("load-psim")
  def enableTrace = genPsim && option[Boolean]("trace")
  def psimHome = getOption[String]("psim-home").getOrElse(throw PIRException(s"psim-home is not set"))
  def psimOut = getOption[String]("psim-out").getOrElse { buildPath(cwd, s"plastisim") }

  /* ------------------- Plastiroute --------------------  */
  register[String]("proute-home", default=sys.env.get("PLASTIROUTE_HOME"), info="Plastiroute Home")
  register("proute-algo", default="route_dor_YX", info="Plastiroute routing algorithm") 
  register("proute-q", default=1, info="Maximum number of vc") 
  register("proute-opts", default="-i1000 -p100 -t1 -d100", info="Plastiroute options") 
  register("proute-seed", default=0, info="Plastiroute seed") 
  register("rerun-proute", default=true, info="Run Plastiroute") 
  def prouteHome = getOption[String]("proute-home").getOrElse(throw PIRException(s"proute-home is not set"))
  def genProute = genPsim || genTungsten
  def runproute = runPsim || runTst 

  /* ------------------- Tungsten --------------------  */
  register[String]("tungsten-home", default=sys.env.get("TUNGSTEN_HOME"), info="Tungsten Home")
  register("run-tst", default=false, info="Launch Tungsten simulation")
  register("tungsten", default=false, info="Enable tungsten codegen")
  def genTungsten = enableCodegen && option[Boolean]("tungsten")
  def runTst = option[Boolean]("run-tst")
  def tstOutDir = buildPath(cwd, "tungsten")
  def tstHome = getOption[String]("tungsten-home").getOrElse(throw PIRException(s"tungsten-home is not set"))

  /* ------------------- Debugging --------------------  */
  register("bp-split", default=false, info="Enable break point for splitting")
  register("bp-pr", default=false, info="Enable break point for place and route")
  register("dot", default=false, info="Enable dot codegen")
  register("snapshot", default=false, info="Enable placement snapshot")
  register("snapint", default=10, info="Placement snapshot interval")

  def enableSplitBreakPoint = debug && option[Boolean]("bp-split")
  def enablePlaceAndRouteBreakPoint = debug && option[Boolean]("bp-pr")
  def enableSnapshot = debug && option[Boolean]("snapshot")
  def enableDot:Boolean = enableCodegen && option[Boolean]("dot")
}
