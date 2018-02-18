import pir._
import pir.node._
import arch._
import pirc.enums._

object TRSM extends PIRApp {
  def main(top:Top) = {
    val x4760 = DRAM().name("x4760").ctrl(top) // x4760 = DRAMNew(ArrayBuffer(Const(64)),Const(0))
    val x4761 = DRAM().name("x4761").ctrl(top) // x4761 = DRAMNew(ArrayBuffer(Const(64)),Const(0))
    val x4762 = DRAM().name("x4762").ctrl(top) // x4762 = DRAMNew(ArrayBuffer(Const(64)),Const(0))
    val x4950 = UnitController(style=SeqPipe, level=OuterControl).name("x4950").ctrl(top) // Hwblock(Block(Const(())),false)
    val x4765_d0_b0 = SRAM(size=64, banking=NoBanking()).name("x4765_d0_b0").ctrl(x4950) // x4765 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4765_d1_b0 = SRAM(size=64, banking=NoBanking()).name("x4765_d1_b0").ctrl(x4950) // x4765 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4765_d2_b0 = SRAM(size=64, banking=NoBanking()).name("x4765_d2_b0").ctrl(x4950) // x4765 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4765_d3_b0 = SRAM(size=64, banking=NoBanking()).name("x4765_d3_b0").ctrl(x4950) // x4765 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4765_d4_b0 = SRAM(size=64, banking=NoBanking()).name("x4765_d4_b0").ctrl(x4950) // x4765 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4765_d5_b0 = SRAM(size=64, banking=NoBanking()).name("x4765_d5_b0").ctrl(x4950) // x4765 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4766_d0_b0 = SRAM(size=64, banking=NoBanking()).name("x4766_d0_b0").ctrl(x4950) // x4766 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4766_d1_b0 = SRAM(size=64, banking=NoBanking()).name("x4766_d1_b0").ctrl(x4950) // x4766 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4766_d2_b0 = SRAM(size=64, banking=NoBanking()).name("x4766_d2_b0").ctrl(x4950) // x4766 = SRAMNew(ArrayBuffer(Const(8), Const(8)))
    val x4767_d0_b0 = SRAM(size=64, banking=NoBanking()).name("x4767_d0_b0").ctrl(x4950) // x4767 = SRAMNew(ArrayBuffer(Const(64)))
    val x4768_d0_b0 = SRAM(size=64, banking=NoBanking()).name("x4768_d0_b0").ctrl(x4950) // x4768 = SRAMNew(ArrayBuffer(Const(64)))
    val x4769_d0_b0 = SRAM(size=64, banking=NoBanking()).name("x4769_d0_b0").ctrl(x4950) // x4769 = SRAMNew(ArrayBuffer(Const(64)))
    val x4788 = UnitController(style=StreamPipe, level=OuterControl).name("x4788").ctrl(x4950) // UnitPipe(List(Const(true)),Block(Const(())))
    val b5062 = StreamOut(field="offset").name("b5062").ctrl(x4788) // x4770 = StreamOutNew(BurstCmdBus)
    val b5063 = StreamOut(field="size").name("b5063").ctrl(x4788) // x4770 = StreamOutNew(BurstCmdBus)
    val x4771 = StreamIn(field="data").name("x4771").ctrl(x4788) // x4771 = StreamInNew(BurstDataBus())
    val x4780 = UnitController(style=SeqPipe, level=InnerControl).name("x4780").ctrl(x4788) // UnitPipe(List(Const(true)),Block(x4779))
    val x4772 = OpDef(op=FixConvert, inputs=List(Const(0).ctrl(x4780))).name("x4772").ctrl(x4780) // FixConvert(Const(0),TRUE,_32,_0)
    val x4773 = OpDef(op=FixSla, inputs=List(x4772, Const(2).ctrl(x4780))).name("x4773").ctrl(x4780) // FixLsh(x4772,Const(2))
    val x4774 = OpDef(op=FixConvert, inputs=List(x4773)).name("x4774").ctrl(x4780) // FixConvert(x4773,TRUE,_64,_0)
    val x4775 = top.dramAddress(x4760).name("x4775").ctrl(x4780) // GetDRAMAddress(x4760)
    val x4776 = OpDef(op=FixAdd, inputs=List(x4774, x4775)).name("x4776").ctrl(x4780) // FixAdd(x4774,x4775)
    val x4777 = OpDef(op=FixConvert, inputs=List(x4776)).name("x4777").ctrl(x4780) // FixConvert(x4776,TRUE,_64,_0)
    // x4778 = SimpleStruct(ArrayBuffer((offset,x4777), (size,Const(256)), (isLoad,Const(true))))
    val b5064_b5062 = WriteMem(b5062, x4777).name("b5064_b5062").ctrl(x4780) // StreamWrite(x4770,x4778,Const(true))
    val b5065_b5063 = WriteMem(b5063, Const(256)).name("b5065_b5063").ctrl(x4780) // StreamWrite(x4770,x4778,Const(true))
    val x4781 = FringeContainer(x4760,b5062,b5063,x4771).name("x4781").ctrl(x4788) // FringeDenseLoad(x4760,x4770,x4771)
    val x4782 = Counter(min=Const(0).ctrl(x4788), max=Const(64).ctrl(x4788), step=Const(1).ctrl(x4788), par=1).name("x4782").ctrl(x4788) // CounterNew(Const(0),Const(64),Const(1),Const(1))
    val x4783 = CounterChain(List(x4782)).name("x4783").ctrl(x4788) // CounterChainNew(List(x4782))
    val x4787 = LoopController(style=InnerPipe, level=InnerControl, cchain=x4783).name("x4787").ctrl(x4788) // UnrolledForeach(List(Const(true)),x4783,Block(Const(())),List(List(b3167)),List(List(b3168)))
    val b3167 = CounterIter(x4782, None).ctrl(x4787).name("b3167")
    val b3168 = DummyOp().ctrl(x4787).name("b3168")
    val x4784 = ReadMem(x4771).name("x4784").ctrl(x4787) // ParStreamRead(x4771,List(b3168))
    val x4785 = x4784 // x4785 = VectorApply(x4784,0)
    val x4786 = StoreBanks(List(x4767_d0_b0), List(b3167), x4785).name("x4786").ctrl(x4787) // ParSRAMStore(x4767,List(List(b3167)),List(x4785),List(b3168))
    val x4807 = UnitController(style=StreamPipe, level=OuterControl).name("x4807").ctrl(x4950) // UnitPipe(List(Const(true)),Block(Const(())))
    val b5066 = StreamOut(field="offset").name("b5066").ctrl(x4807) // x4789 = StreamOutNew(BurstCmdBus)
    val b5067 = StreamOut(field="size").name("b5067").ctrl(x4807) // x4789 = StreamOutNew(BurstCmdBus)
    val x4790 = StreamIn(field="data").name("x4790").ctrl(x4807) // x4790 = StreamInNew(BurstDataBus())
    val x4799 = UnitController(style=SeqPipe, level=InnerControl).name("x4799").ctrl(x4807) // UnitPipe(List(Const(true)),Block(x4798))
    val x4791 = OpDef(op=FixConvert, inputs=List(Const(0).ctrl(x4799))).name("x4791").ctrl(x4799) // FixConvert(Const(0),TRUE,_32,_0)
    val x4792 = OpDef(op=FixSla, inputs=List(x4791, Const(2).ctrl(x4799))).name("x4792").ctrl(x4799) // FixLsh(x4791,Const(2))
    val x4793 = OpDef(op=FixConvert, inputs=List(x4792)).name("x4793").ctrl(x4799) // FixConvert(x4792,TRUE,_64,_0)
    val x4794 = top.dramAddress(x4761).name("x4794").ctrl(x4799) // GetDRAMAddress(x4761)
    val x4795 = OpDef(op=FixAdd, inputs=List(x4793, x4794)).name("x4795").ctrl(x4799) // FixAdd(x4793,x4794)
    val x4796 = OpDef(op=FixConvert, inputs=List(x4795)).name("x4796").ctrl(x4799) // FixConvert(x4795,TRUE,_64,_0)
    // x4797 = SimpleStruct(ArrayBuffer((offset,x4796), (size,Const(256)), (isLoad,Const(true))))
    val b5068_b5066 = WriteMem(b5066, x4796).name("b5068_b5066").ctrl(x4799) // StreamWrite(x4789,x4797,Const(true))
    val b5069_b5067 = WriteMem(b5067, Const(256)).name("b5069_b5067").ctrl(x4799) // StreamWrite(x4789,x4797,Const(true))
    val x4800 = FringeContainer(x4761,b5066,b5067,x4790).name("x4800").ctrl(x4807) // FringeDenseLoad(x4761,x4789,x4790)
    val x4801 = Counter(min=Const(0).ctrl(x4807), max=Const(64).ctrl(x4807), step=Const(1).ctrl(x4807), par=1).name("x4801").ctrl(x4807) // CounterNew(Const(0),Const(64),Const(1),Const(1))
    val x4802 = CounterChain(List(x4801)).name("x4802").ctrl(x4807) // CounterChainNew(List(x4801))
    val x4806 = LoopController(style=InnerPipe, level=InnerControl, cchain=x4802).name("x4806").ctrl(x4807) // UnrolledForeach(List(Const(true)),x4802,Block(Const(())),List(List(b3188)),List(List(b3189)))
    val b3188 = CounterIter(x4801, None).ctrl(x4806).name("b3188")
    val b3189 = DummyOp().ctrl(x4806).name("b3189")
    val x4803 = ReadMem(x4790).name("x4803").ctrl(x4806) // ParStreamRead(x4790,List(b3189))
    val x4804 = x4803 // x4804 = VectorApply(x4803,0)
    val x4805 = StoreBanks(List(x4768_d0_b0), List(b3188), x4804).name("x4805").ctrl(x4806) // ParSRAMStore(x4768,List(List(b3188)),List(x4804),List(b3189))
    val x4808 = Counter(min=Const(0).ctrl(x4950), max=Const(8).ctrl(x4950), step=Const(1).ctrl(x4950), par=1).name("x4808").ctrl(x4950) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4809 = Counter(min=Const(0).ctrl(x4950), max=Const(8).ctrl(x4950), step=Const(1).ctrl(x4950), par=1).name("x4809").ctrl(x4950) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4810 = CounterChain(List(x4809,x4808)).name("x4810").ctrl(x4950) // CounterChainNew(List(x4809, x4808))
    val x4819 = LoopController(style=InnerPipe, level=InnerControl, cchain=x4810).name("x4819").ctrl(x4950) // UnrolledForeach(List(Const(true)),x4810,Block(Const(())),List(List(b3199), List(b3200)),List(List(b3201), List(b3202)))
    val b3199 = CounterIter(x4809, Some(0)).ctrl(x4819).name("b3199")
    val b3201 = DummyOp().ctrl(x4819).name("b3201")
    val b3200 = CounterIter(x4808, None).ctrl(x4819).name("b3200")
    val b3202 = DummyOp().ctrl(x4819).name("b3202")
    val x4811 = OpDef(op=FixConvert, inputs=List(b3199)).name("x4811").ctrl(x4819) // FixConvert(b3199,TRUE,_32,_0)
    val x4812 = OpDef(op=FixSla, inputs=List(x4811, Const(3).ctrl(x4819))).name("x4812").ctrl(x4819) // FixLsh(x4811,Const(3))
    val x4813 = OpDef(op=FixConvert, inputs=List(b3200)).name("x4813").ctrl(x4819) // FixConvert(b3200,TRUE,_32,_0)
    val x4814 = OpDef(op=FixAdd, inputs=List(x4812, x4813)).name("x4814").ctrl(x4819) // FixAdd(x4812,x4813)
    val x4815 = OpDef(op=BitAnd, inputs=List(b3201, b3202)).name("x4815").ctrl(x4819) // And(b3201,b3202)
    val x4816 = LoadBanks(List(x4767_d0_b0), List(x4814)).name("x4816").ctrl(x4819) // ParSRAMLoad(x4767,List(List(x4814)),List(x4815))
    val x4817 = x4816 // x4817 = VectorApply(x4816,0)
    val x4818 = StoreBanks(List(x4765_d0_b0, x4765_d5_b0, x4765_d1_b0, x4765_d2_b0, x4765_d3_b0, x4765_d4_b0), List(b3199, b3200), x4817).name("x4818").ctrl(x4819) // ParSRAMStore(x4765,List(List(b3199, b3200)),List(x4817),List(x4815))
    val x4820 = Counter(min=Const(0).ctrl(x4950), max=Const(8).ctrl(x4950), step=Const(1).ctrl(x4950), par=1).name("x4820").ctrl(x4950) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4821 = Counter(min=Const(0).ctrl(x4950), max=Const(8).ctrl(x4950), step=Const(1).ctrl(x4950), par=1).name("x4821").ctrl(x4950) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4822 = CounterChain(List(x4821,x4820)).name("x4822").ctrl(x4950) // CounterChainNew(List(x4821, x4820))
    val x4831 = LoopController(style=InnerPipe, level=InnerControl, cchain=x4822).name("x4831").ctrl(x4950) // UnrolledForeach(List(Const(true)),x4822,Block(Const(())),List(List(b3215), List(b3216)),List(List(b3217), List(b3218)))
    val b3215 = CounterIter(x4821, Some(0)).ctrl(x4831).name("b3215")
    val b3217 = DummyOp().ctrl(x4831).name("b3217")
    val b3216 = CounterIter(x4820, None).ctrl(x4831).name("b3216")
    val b3218 = DummyOp().ctrl(x4831).name("b3218")
    val x4823 = OpDef(op=FixConvert, inputs=List(b3215)).name("x4823").ctrl(x4831) // FixConvert(b3215,TRUE,_32,_0)
    val x4824 = OpDef(op=FixSla, inputs=List(x4823, Const(3).ctrl(x4831))).name("x4824").ctrl(x4831) // FixLsh(x4823,Const(3))
    val x4825 = OpDef(op=FixConvert, inputs=List(b3216)).name("x4825").ctrl(x4831) // FixConvert(b3216,TRUE,_32,_0)
    val x4826 = OpDef(op=FixAdd, inputs=List(x4824, x4825)).name("x4826").ctrl(x4831) // FixAdd(x4824,x4825)
    val x4827 = OpDef(op=BitAnd, inputs=List(b3217, b3218)).name("x4827").ctrl(x4831) // And(b3217,b3218)
    val x4828 = LoadBanks(List(x4768_d0_b0), List(x4826)).name("x4828").ctrl(x4831) // ParSRAMLoad(x4768,List(List(x4826)),List(x4827))
    val x4829 = x4828 // x4829 = VectorApply(x4828,0)
    val x4830 = StoreBanks(List(x4766_d0_b0, x4766_d1_b0, x4766_d2_b0), List(b3215, b3216), x4829).name("x4830").ctrl(x4831) // ParSRAMStore(x4766,List(List(b3215, b3216)),List(x4829),List(x4827))
    val x4832 = Counter(min=Const(0).ctrl(x4950), max=Const(8).ctrl(x4950), step=Const(4).ctrl(x4950), par=1).name("x4832").ctrl(x4950) // CounterNew(Const(0),Const(8),Const(4),Const(1))
    val x4833 = CounterChain(List(x4832)).name("x4833").ctrl(x4950) // CounterChainNew(List(x4832))
    val x4912 = LoopController(style=SeqPipe, level=OuterControl, cchain=x4833).name("x4912").ctrl(x4950) // UnrolledForeach(List(Const(true)),x4833,Block(Const(())),List(List(b3230)),List(List(b3231)))
    val b3230 = CounterIter(x4832, Some(0)).ctrl(x4912).name("b3230")
    val b3231 = DummyOp().ctrl(x4912).name("b3231")
    val x4834 = Counter(min=Const(0).ctrl(x4912), max=b3230, step=Const(1).ctrl(x4912), par=1).name("x4834").ctrl(x4912) // CounterNew(Const(0),b3230,Const(1),Const(1))
    val x4835 = CounterChain(List(x4834)).name("x4835").ctrl(x4912) // CounterChainNew(List(x4834))
    val x4856 = LoopController(style=SeqPipe, level=OuterControl, cchain=x4835).name("x4856").ctrl(x4912) // UnrolledForeach(List(b3231),x4835,Block(Const(())),List(List(b3234)),List(List(b3235)))
    val b3234 = CounterIter(x4834, Some(0)).ctrl(x4856).name("b3234")
    val b3235 = DummyOp().ctrl(x4856).name("b3235")
    val x4836 = Counter(min=Const(0).ctrl(x4856), max=Const(8).ctrl(x4856), step=Const(1).ctrl(x4856), par=1).name("x4836").ctrl(x4856) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4837 = Counter(min=Const(0).ctrl(x4856), max=Const(4).ctrl(x4856), step=Const(1).ctrl(x4856), par=1).name("x4837").ctrl(x4856) // CounterNew(Const(0),Const(4),Const(1),Const(1))
    val x4838 = CounterChain(List(x4837,x4836)).name("x4838").ctrl(x4856) // CounterChainNew(List(x4837, x4836))
    val x4855 = LoopController(style=SeqPipe, level=InnerControl, cchain=x4838).name("x4855").ctrl(x4856) // UnrolledForeach(List(b3235, b3231),x4838,Block(Const(())),List(List(b3239), List(b3240)),List(List(b3241), List(b3242)))
    val b3239 = CounterIter(x4837, Some(0)).ctrl(x4855).name("b3239")
    val b3241 = DummyOp().ctrl(x4855).name("b3241")
    val b3240 = CounterIter(x4836, None).ctrl(x4855).name("b3240")
    val b3242 = DummyOp().ctrl(x4855).name("b3242")
    val x4839 = OpDef(op=FixConvert, inputs=List(b3239)).name("x4839").ctrl(x4855) // FixConvert(b3239,TRUE,_32,_0)
    val x4840 = OpDef(op=FixAdd, inputs=List(b3230, x4839)).name("x4840").ctrl(x4855) // FixAdd(b3230,x4839)
    val x4841 = OpDef(op=FixConvert, inputs=List(b3230)).name("x4841").ctrl(x4855) // FixConvert(b3230,TRUE,_32,_0)
    val x4842 = OpDef(op=FixEql, inputs=List(x4841, Const(0).ctrl(x4855))).name("x4842").ctrl(x4855) // FixEql(x4841,Const(0))
    val x4843 = OpDef(op=BitAnd, inputs=List(b3241, b3242)).name("x4843").ctrl(x4855) // And(b3241,b3242)
    val x4844 = OpDef(op=BitAnd, inputs=List(b3235, b3231)).name("x4844").ctrl(x4855) // And(b3235,b3231)
    val x4845 = OpDef(op=BitAnd, inputs=List(x4843, x4844)).name("x4845").ctrl(x4855) // And(x4843,x4844)
    val x4846 = LoadBanks(List(x4765_d5_b0), List(x4840, b3240)).name("x4846").ctrl(x4855) // ParSRAMLoad(x4765,List(List(x4840, b3240)),List(x4845))
    val x4847 = x4846 // x4847 = VectorApply(x4846,0)
    val x4848 = LoadBanks(List(x4766_d2_b0), List(x4840, b3234)).name("x4848").ctrl(x4855) // SRAMLoad(x4766,ArrayBuffer(Const(8), Const(8)),List(x4840, b3234),Const(0),x4845)
    val x4849 = LoadBanks(List(x4765_d4_b0), List(b3234, b3240)).name("x4849").ctrl(x4855) // ParSRAMLoad(x4765,List(List(b3234, b3240)),List(x4845))
    val x4850 = x4849 // x4850 = VectorApply(x4849,0)
    val x4851 = OpDef(op=FixMul, inputs=List(x4848, x4850)).name("x4851").ctrl(x4855) // FixMul(x4848,x4850)
    val x4852 = OpDef(op=FixSub, inputs=List(x4847, x4851)).name("x4852").ctrl(x4855) // FixSub(x4847,x4851)
    val x4853 = OpDef(op=MuxOp, inputs=List(x4842, x4847, x4852)).name("x4853").ctrl(x4855) // Mux(x4842,x4847,x4852)
    val x4854 = StoreBanks(List(x4765_d0_b0, x4765_d5_b0, x4765_d1_b0, x4765_d2_b0, x4765_d3_b0, x4765_d4_b0), List(x4840, b3240), x4853).name("x4854").ctrl(x4855) // ParSRAMStore(x4765,List(List(x4840, b3240)),List(x4853),List(x4845))
    val x4857 = Counter(min=Const(0).ctrl(x4912), max=Const(4).ctrl(x4912), step=Const(1).ctrl(x4912), par=1).name("x4857").ctrl(x4912) // CounterNew(Const(0),Const(4),Const(1),Const(1))
    val x4858 = CounterChain(List(x4857)).name("x4858").ctrl(x4912) // CounterChainNew(List(x4857))
    val x4911 = LoopController(style=SeqPipe, level=OuterControl, cchain=x4858).name("x4911").ctrl(x4912) // UnrolledForeach(List(b3231),x4858,Block(Const(())),List(List(b3263)),List(List(b3264)))
    val b3263 = CounterIter(x4857, Some(0)).ctrl(x4911).name("b3263")
    val b3264 = DummyOp().ctrl(x4911).name("b3264")
    val x4859_d0 = Reg(init=0).name("x4859_d0").ctrl(x4911) // x4859 = RegNew(Const(0))
    val x4859_d1 = Reg(init=0).name("x4859_d1").ctrl(x4911) // x4859 = RegNew(Const(0))
    val x4860 = Reg(init=0.0).name("x4860").ctrl(x4911) // x4860 = RegNew(Const(0))
    val x4867 = UnitController(style=SeqPipe, level=InnerControl).name("x4867").ctrl(x4911) // UnitPipe(List(b3264, b3231),Block(Const(())))
    val x4861 = OpDef(op=FixConvert, inputs=List(b3263)).name("x4861").ctrl(x4867) // FixConvert(b3263,TRUE,_32,_0)
    val x4862 = OpDef(op=FixAdd, inputs=List(x4861, b3230)).name("x4862").ctrl(x4867) // FixAdd(x4861,b3230)
    val x4863 = OpDef(op=BitAnd, inputs=List(b3264, b3231)).name("x4863").ctrl(x4867) // And(b3264,b3231)
    val x4864 = LoadBanks(List(x4766_d1_b0), List(x4862, x4862)).name("x4864").ctrl(x4867) // SRAMLoad(x4766,ArrayBuffer(Const(8), Const(8)),List(x4862, x4862),Const(0),x4863)
    val x4865_x4859_d0 = WriteMem(x4859_d0, x4862).name("x4865_x4859_d0").ctrl(x4867) // RegWrite(x4859,x4862,x4863)
    val x4865_x4859_d1 = WriteMem(x4859_d1, x4862).name("x4865_x4859_d1").ctrl(x4867) // RegWrite(x4859,x4862,x4863)
    val x4866_x4860 = WriteMem(x4860, x4864).name("x4866_x4860").ctrl(x4867) // RegWrite(x4860,x4864,x4863)
    val x4868 = Counter(min=Const(0).ctrl(x4911), max=Const(8).ctrl(x4911), step=Const(1).ctrl(x4911), par=1).name("x4868").ctrl(x4911) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4869 = CounterChain(List(x4868)).name("x4869").ctrl(x4911) // CounterChainNew(List(x4868))
    val x4878 = LoopController(style=SeqPipe, level=InnerControl, cchain=x4869).name("x4878").ctrl(x4911) // UnrolledForeach(List(b3264, b3231),x4869,Block(Const(())),List(List(b3276)),List(List(b3277)))
    val b3276 = CounterIter(x4868, None).ctrl(x4878).name("b3276")
    val b3277 = DummyOp().ctrl(x4878).name("b3277")
    val x4870 = ReadMem(x4859_d1).name("x4870").ctrl(x4878) // RegRead(x4859)
    val x4871 = OpDef(op=BitAnd, inputs=List(b3277, b3264)).name("x4871").ctrl(x4878) // And(b3277,b3264)
    val x4872 = OpDef(op=BitAnd, inputs=List(x4871, b3231)).name("x4872").ctrl(x4878) // And(x4871,b3231)
    val x4873 = LoadBanks(List(x4765_d3_b0), List(x4870, b3276)).name("x4873").ctrl(x4878) // ParSRAMLoad(x4765,List(List(x4870, b3276)),List(x4872))
    val x4874 = x4873 // x4874 = VectorApply(x4873,0)
    val x4875 = ReadMem(x4860).name("x4875").ctrl(x4878) // RegRead(x4860)
    val x4876 = OpDef(op=FixDiv, inputs=List(x4874, x4875)).name("x4876").ctrl(x4878) // FixDiv(x4874,x4875)
    val x4877 = StoreBanks(List(x4765_d0_b0, x4765_d5_b0, x4765_d1_b0, x4765_d2_b0, x4765_d3_b0, x4765_d4_b0), List(x4870, b3276), x4876).name("x4877").ctrl(x4878) // ParSRAMStore(x4765,List(List(x4870, b3276)),List(x4876),List(x4872))
    val x4879_d0 = Reg(init=0).name("x4879_d0").ctrl(x4911) // x4879 = RegNew(Const(0))
    val x4879_d1 = Reg(init=0).name("x4879_d1").ctrl(x4911) // x4879 = RegNew(Const(0))
    val x4884 = UnitController(style=SeqPipe, level=InnerControl).name("x4884").ctrl(x4911) // UnitPipe(List(b3264, b3231),Block(x4883))
    val x4880 = OpDef(op=FixSub, inputs=List(Const(4).ctrl(x4884), b3263)).name("x4880").ctrl(x4884) // FixSub(Const(4),b3263)
    val x4881 = OpDef(op=FixSub, inputs=List(x4880, Const(1).ctrl(x4884))).name("x4881").ctrl(x4884) // FixSub(x4880,Const(1))
    val x4882 = OpDef(op=BitAnd, inputs=List(b3264, b3231)).name("x4882").ctrl(x4884) // And(b3264,b3231)
    val x4883_x4879_d0 = WriteMem(x4879_d0, x4881).name("x4883_x4879_d0").ctrl(x4884) // RegWrite(x4879,x4881,x4882)
    val x4883_x4879_d1 = WriteMem(x4879_d1, x4881).name("x4883_x4879_d1").ctrl(x4884) // RegWrite(x4879,x4881,x4882)
    val x4885 = ReadMem(x4879_d1).name("x4885").ctrl(x4911) // RegRead(x4879)
    val x4886 = Counter(min=Const(0).ctrl(x4911), max=x4885, step=Const(1).ctrl(x4911), par=1).name("x4886").ctrl(x4911) // CounterNew(Const(0),x4885,Const(1),Const(1))
    val x4887 = CounterChain(List(x4886)).name("x4887").ctrl(x4911) // CounterChainNew(List(x4886))
    val x4910 = LoopController(style=SeqPipe, level=OuterControl, cchain=x4887).name("x4910").ctrl(x4911) // UnrolledForeach(List(b3264, b3231),x4887,Block(Const(())),List(List(b3296)),List(List(b3297)))
    val b3296 = CounterIter(x4886, Some(0)).ctrl(x4910).name("b3296")
    val b3297 = DummyOp().ctrl(x4910).name("b3297")
    val x4888 = Counter(min=Const(0).ctrl(x4910), max=Const(8).ctrl(x4910), step=Const(1).ctrl(x4910), par=1).name("x4888").ctrl(x4910) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4889 = CounterChain(List(x4888)).name("x4889").ctrl(x4910) // CounterChainNew(List(x4888))
    val x4909 = LoopController(style=SeqPipe, level=InnerControl, cchain=x4889).name("x4909").ctrl(x4910) // UnrolledForeach(List(b3297, b3264, b3231),x4889,Block(Const(())),List(List(b3300)),List(List(b3301)))
    val b3300 = CounterIter(x4888, None).ctrl(x4909).name("b3300")
    val b3301 = DummyOp().ctrl(x4909).name("b3301")
    val x4890 = ReadMem(x4859_d0).name("x4890").ctrl(x4909) // RegRead(x4859)
    val x4891 = OpDef(op=FixConvert, inputs=List(x4890)).name("x4891").ctrl(x4909) // FixConvert(x4890,TRUE,_32,_0)
    val x4892 = OpDef(op=FixAdd, inputs=List(x4891, Const(1).ctrl(x4909))).name("x4892").ctrl(x4909) // FixAdd(x4891,Const(1))
    val x4893 = OpDef(op=FixConvert, inputs=List(b3296)).name("x4893").ctrl(x4909) // FixConvert(b3296,TRUE,_32,_0)
    val x4894 = OpDef(op=FixAdd, inputs=List(x4892, x4893)).name("x4894").ctrl(x4909) // FixAdd(x4892,x4893)
    val x4895 = ReadMem(x4879_d0).name("x4895").ctrl(x4909) // RegRead(x4879)
    val x4896 = OpDef(op=FixEql, inputs=List(x4895, Const(0).ctrl(x4909))).name("x4896").ctrl(x4909) // FixEql(x4895,Const(0))
    val x4897 = OpDef(op=BitAnd, inputs=List(b3301, b3297)).name("x4897").ctrl(x4909) // And(b3301,b3297)
    val x4898 = OpDef(op=BitAnd, inputs=List(b3264, b3231)).name("x4898").ctrl(x4909) // And(b3264,b3231)
    val x4899 = OpDef(op=BitAnd, inputs=List(x4897, x4898)).name("x4899").ctrl(x4909) // And(x4897,x4898)
    val x4900 = LoadBanks(List(x4765_d2_b0), List(x4894, b3300)).name("x4900").ctrl(x4909) // ParSRAMLoad(x4765,List(List(x4894, b3300)),List(x4899))
    val x4901 = x4900 // x4901 = VectorApply(x4900,0)
    val x4902 = LoadBanks(List(x4766_d0_b0), List(x4894, b3263)).name("x4902").ctrl(x4909) // SRAMLoad(x4766,ArrayBuffer(Const(8), Const(8)),List(x4894, b3263),Const(0),x4899)
    val x4903 = LoadBanks(List(x4765_d1_b0), List(b3263, b3300)).name("x4903").ctrl(x4909) // ParSRAMLoad(x4765,List(List(b3263, b3300)),List(x4899))
    val x4904 = x4903 // x4904 = VectorApply(x4903,0)
    val x4905 = OpDef(op=FixMul, inputs=List(x4902, x4904)).name("x4905").ctrl(x4909) // FixMul(x4902,x4904)
    val x4906 = OpDef(op=FixSub, inputs=List(x4901, x4905)).name("x4906").ctrl(x4909) // FixSub(x4901,x4905)
    val x4907 = OpDef(op=MuxOp, inputs=List(x4896, x4901, x4906)).name("x4907").ctrl(x4909) // Mux(x4896,x4901,x4906)
    val x4908 = StoreBanks(List(x4765_d0_b0, x4765_d5_b0, x4765_d1_b0, x4765_d2_b0, x4765_d3_b0, x4765_d4_b0), List(x4894, b3300), x4907).name("x4908").ctrl(x4909) // ParSRAMStore(x4765,List(List(x4894, b3300)),List(x4907),List(x4899))
    val x4913 = Counter(min=Const(0).ctrl(x4950), max=Const(8).ctrl(x4950), step=Const(1).ctrl(x4950), par=1).name("x4913").ctrl(x4950) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4914 = CounterChain(List(x4913)).name("x4914").ctrl(x4950) // CounterChainNew(List(x4913))
    val x4926 = LoopController(style=MetaPipe, level=OuterControl, cchain=x4914).name("x4926").ctrl(x4950) // UnrolledForeach(List(Const(true)),x4914,Block(Const(())),List(List(b3327)),List(List(b3328)))
    val b3327 = CounterIter(x4913, Some(0)).ctrl(x4926).name("b3327")
    val b3328 = DummyOp().ctrl(x4926).name("b3328")
    val x4915 = Counter(min=Const(0).ctrl(x4926), max=Const(8).ctrl(x4926), step=Const(1).ctrl(x4926), par=1).name("x4915").ctrl(x4926) // CounterNew(Const(0),Const(8),Const(1),Const(1))
    val x4916 = CounterChain(List(x4915)).name("x4916").ctrl(x4926) // CounterChainNew(List(x4915))
    val x4925 = LoopController(style=InnerPipe, level=InnerControl, cchain=x4916).name("x4925").ctrl(x4926) // UnrolledForeach(List(b3328),x4916,Block(Const(())),List(List(b3331)),List(List(b3332)))
    val b3331 = CounterIter(x4915, None).ctrl(x4925).name("b3331")
    val b3332 = DummyOp().ctrl(x4925).name("b3332")
    val x4917 = OpDef(op=FixConvert, inputs=List(b3327)).name("x4917").ctrl(x4925) // FixConvert(b3327,TRUE,_32,_0)
    val x4918 = OpDef(op=FixSla, inputs=List(x4917, Const(3).ctrl(x4925))).name("x4918").ctrl(x4925) // FixLsh(x4917,Const(3))
    val x4919 = OpDef(op=FixConvert, inputs=List(b3331)).name("x4919").ctrl(x4925) // FixConvert(b3331,TRUE,_32,_0)
    val x4920 = OpDef(op=FixAdd, inputs=List(x4918, x4919)).name("x4920").ctrl(x4925) // FixAdd(x4918,x4919)
    val x4921 = OpDef(op=BitAnd, inputs=List(b3332, b3328)).name("x4921").ctrl(x4925) // And(b3332,b3328)
    val x4922 = LoadBanks(List(x4765_d0_b0), List(b3327, b3331)).name("x4922").ctrl(x4925) // ParSRAMLoad(x4765,List(List(b3327, b3331)),List(x4921))
    val x4923 = x4922 // x4923 = VectorApply(x4922,0)
    val x4924 = StoreBanks(List(x4769_d0_b0), List(x4920), x4923).name("x4924").ctrl(x4925) // ParSRAMStore(x4769,List(List(x4920)),List(x4923),List(x4921))
    val x4949 = UnitController(style=StreamPipe, level=OuterControl).name("x4949").ctrl(x4950) // UnitPipe(List(Const(true)),Block(Const(())))
    val b5070 = StreamOut(field="offset").name("b5070").ctrl(x4949) // x4927 = StreamOutNew(BurstCmdBus)
    val b5071 = StreamOut(field="size").name("b5071").ctrl(x4949) // x4927 = StreamOutNew(BurstCmdBus)
    val x4928 = StreamOut(field="data").name("x4928").ctrl(x4949) // x4928 = StreamOutNew(BurstFullDataBus())
    val x4929 = StreamIn(field="ack").name("x4929").ctrl(x4949) // x4929 = StreamInNew(BurstAckBus)
    val x4938 = UnitController(style=SeqPipe, level=InnerControl).name("x4938").ctrl(x4949) // UnitPipe(List(Const(true)),Block(x4937))
    val x4930 = OpDef(op=FixConvert, inputs=List(Const(0).ctrl(x4938))).name("x4930").ctrl(x4938) // FixConvert(Const(0),TRUE,_32,_0)
    val x4931 = OpDef(op=FixSla, inputs=List(x4930, Const(2).ctrl(x4938))).name("x4931").ctrl(x4938) // FixLsh(x4930,Const(2))
    val x4932 = OpDef(op=FixConvert, inputs=List(x4931)).name("x4932").ctrl(x4938) // FixConvert(x4931,TRUE,_64,_0)
    val x4933 = top.dramAddress(x4762).name("x4933").ctrl(x4938) // GetDRAMAddress(x4762)
    val x4934 = OpDef(op=FixAdd, inputs=List(x4932, x4933)).name("x4934").ctrl(x4938) // FixAdd(x4932,x4933)
    val x4935 = OpDef(op=FixConvert, inputs=List(x4934)).name("x4935").ctrl(x4938) // FixConvert(x4934,TRUE,_64,_0)
    // x4936 = SimpleStruct(ArrayBuffer((offset,x4935), (size,Const(256)), (isLoad,Const(false))))
    val b5072_b5070 = WriteMem(b5070, x4935).name("b5072_b5070").ctrl(x4938) // StreamWrite(x4927,x4936,Const(true))
    val b5073_b5071 = WriteMem(b5071, Const(256)).name("b5073_b5071").ctrl(x4938) // StreamWrite(x4927,x4936,Const(true))
    val x4939 = Counter(min=Const(0).ctrl(x4949), max=Const(64).ctrl(x4949), step=Const(1).ctrl(x4949), par=1).name("x4939").ctrl(x4949) // CounterNew(Const(0),Const(64),Const(1),Const(1))
    val x4940 = CounterChain(List(x4939)).name("x4940").ctrl(x4949) // CounterChainNew(List(x4939))
    val x4945 = LoopController(style=InnerPipe, level=InnerControl, cchain=x4940).name("x4945").ctrl(x4949) // UnrolledForeach(List(Const(true)),x4940,Block(Const(())),List(List(b3357)),List(List(b3358)))
    val b3357 = CounterIter(x4939, None).ctrl(x4945).name("b3357")
    val b3358 = DummyOp().ctrl(x4945).name("b3358")
    val x4941 = LoadBanks(List(x4769_d0_b0), List(b3357)).name("x4941").ctrl(x4945) // ParSRAMLoad(x4769,List(List(b3357)),List(b3358))
    val x4942 = x4941 // x4942 = VectorApply(x4941,0)
    // x4943 = SimpleStruct(ArrayBuffer((_1,x4942), (_2,Const(true))))
    val x4944_x4928 = WriteMem(x4928, x4942).name("x4944_x4928").ctrl(x4945) // ParStreamWrite(x4928,List(x4943),List(b3358))
    val x4946 = FringeContainer(x4762,b5070,b5071,x4928,x4929).name("x4946").ctrl(x4949) // FringeDenseStore(x4762,x4927,x4928,x4929)
    val x4948 = UnitController(style=SeqPipe, level=InnerControl).name("x4948").ctrl(x4949) // UnitPipe(List(Const(true)),Block(Const(())))
    val x4947 = ReadMem(x4929).name("x4947").ctrl(x4948) // StreamRead(x4929,Const(true))
    
  }
}
