package spade

class SpadeConfig(compiler:Compiler) extends prism.Config(compiler) {

  register("simulate", false, info="Enable simulation")
  register("waveform", true, info="Enable waveform")
  register("time-out", 100, info="Simulation time out after X cycles")
  register("open", default=false, info="Open dot graph after codegen")

  def simulate:Boolean = option[Boolean]("simulate")
  def waveform:Boolean = option[Boolean]("waveform")
  def simulationTimeOut:Int = option[Int]("time-out")
  def openDot = option[Boolean]("open")
  
  /* ------------------ Architecture parameters ---------------- */
  register[Int]("clock", default=1e9.toInt, info="Clock Frequency")
  register[Int]("word", default=32, info="Word width")
  register[Int]("vec", default=16, info="Vector width of SIMD lanes and vector network")
  register[Int]("pmu-sram-size", default=64*1024, info="SRAM capacity in PMU in word.")
  register[String]("net", default="inf", info="Network type [dynamic, static, hybrid, asic, p2p]")
  register[String]("topo", default="mesh", info="Network topology [mesh, torus, cmesh]")
  register[Int]("row", default=16, info="number of rows in network")
  register[Int]("col", default=8, info="number of columns in network")
  register[Boolean]("nn", default=false, info="enable nearest neighbor")
  register[Boolean]("dag", default=true, info="enable dram address generator in network")
  register[String]("pattern", default="checkerboard", info="Pattern in layout of different CU types. [checkerboard,mcmcstrip]")
  register[Int]("argin", default=6, info="number of ArgIn")
  register[Int]("argout", default=4, info="number of ArgOut")
  register[Int]("tokenout", default=5, info="number of TokenOut")
  register[Int]("fifo-depth", default=16, info="Depth of FIFO for all CUs")
  register[Int]("vfifo", default=4, info="Number of vector FIFO within Terminal")
  register[Int]("vlink", default=2, info="Number of vector link between switches")
  register[Int]("slink", default=4, info="Number of scalar link between switches")
  register[Int]("pcu-pr", info="Number of pipeline register in pcu")
  register[Int]("pmu-pr", info="Number of pipeline register in pmu")
  register[Int]("pcu-vin", info="Number of vector input to pcu")
  register[Int]("pcu-vout", info="Number of vector output to pcu")
  register[Int]("pmu-vin", info="Number of vector input to pmu")
  register[Int]("pmu-vout", info="Number of vector output to pmu")
  register[Int]("pcu-sin", info="Number of scalar input to pcu")
  register[Int]("pcu-sout", info="Number of scalar output to pcu")
  register[Int]("pmu-sin", info="Number of scalar input to pmu")
  register[Int]("pmu-sout", info="Number of scalar output to pmu")
  register[Int]("pcu-vfifo", info="Number of vector fifo in pcu")
  register[Int]("pcu-sfifo", info="Number of scalar fifo in pcu")
  register[Int]("pcu-cfifo", info="Number of scalar fifo in pcu")
  register[Int]("pcu-stage", default=6, info="Number of stages in pmu")
  register[Int]("pmu-stage", default=6, info="Number of stages in pmu")
  register[Int]("dag-stage", default=6, info="Number of stages in pmu")
  register[Int]("pmu-vfifo", info="Number of vector fifo in pmu")
  register[Int]("pmu-sfifo", info="Number of scalar fifo in pmu")
  register[Int]("pmu-cfifo", info="Number of scalar fifo in pmu")
  register[Int]("dag-vfifo", info="Number of vector fifo in dag")
  register[Int]("dag-sfifo", info="Number of scalar fifo in dag")
  register[Int]("dag-cfifo", info="Number of scalar fifo in dag")
  register[Int]("add-row", default=0, info="Additional rows in the network")
  register[Int]("mcrank", info="Number of column of MC and DRAM AG on both sides of the fringe")
  register[String]("mem-tech", info="Off-chip memory technology [DDR3, DDR4, HBM]", default="DDR4")
  register[Int]("vc", default=4, info="Number of virtual classes per network")
  register[Boolean]("scheduled", default=false, info="Wether stage is time multiplex or pipelined")
  register[String]("link-prop", default="db", info="[db-double buffered, cd-credit based]")
  register[Int]("flit-width", default=512, info="Flit width for dynamic network")

}
