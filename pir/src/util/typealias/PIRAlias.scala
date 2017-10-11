package pir.util.typealias

import pir.node._

trait PIRAlias {
  // PIR Nodes 
  type Node  = pir.node.Node
  type CL    = Controller
  type ICL   = InnerController
  type OCL   = OuterController
  type CU    = ComputeUnit
  type SC    = StreamController
  type MP    = MemoryPipeline
  type PL    = Pipeline
  type MC    = MemoryController
  type PRIM  = Primitive
  type Reg   = pir.node.Reg
  type PR    = PipeReg
  type OCM   = OnChipMem
  type SRAM  = pir.node.SRAM
  type SMem  = ScalarMem
  type LMem  = LocalMem
  type SFIFO = ScalarFIFO
  type VFIFO = VectorFIFO
  type FIFO  = pir.node.FIFO
  type SBuf  = ScalarBuffer
  type MBuf  = MultiBuffer
  type CC    = CounterChain
  type Ctr   = Counter
  type D     = Delay 
  type FU    = FuncUnit
  type ST    = Stage
  //type EST   = EmptyStage
  type WAST  = WAStage
  type RDST  = ReduceStage
  type ACST  = AccumStage
  type GI    = GlobalInput
  type GO    = GlobalOutput
  type GIO   = GlobalIO
  type IO    = pir.node.IO
  type I    = Input
  type O    = Output
  type PDU   = PredicateUnit
  type CB    = CtrlBox
  type MCB   = MemCtrlBox
  type SCB   = StageCtrlBox
  type ICB   = InnerCtrlBox
  type OCB   = OuterCtrlBox
  type TCB   = TopCtrlBox
  type MCCB  = MCCtrlBox 
  type LUT   = pir.node.LUT
  type TOLUT = TokenOutLUT
  type TDLUT = TokenDownLUT
  type EnLUT = pir.node.EnLUT
  type AT    = AndTree
  type UC    = UDCounter
  type Const = pir.node.Const[_<:AnyVal]
  type Top   = pir.node.Top
  type Seq   = Sequential
  type MetaPipe = MetaPipeline
  type Mux = MuxLike
  type VMux = ValidMux
}
