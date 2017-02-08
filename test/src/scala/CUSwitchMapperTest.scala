package pir.test

import pir.{Design, Config}
import pir.typealias._
import pir.misc._
import pir.graph._
import pir.graph.enums._
import pir.codegen.{DotCodegen}
import pir.plasticine.main._
import pir.plasticine.config._
import pir.plasticine.graph.{ComputeUnit => PCU, Top => PTop, Node => PNode, SwitchBox}
import pir.graph.mapper._
import pir.graph.traversal._
import scala.language.reflectiveCalls

import org.scalatest.{Sequential => _, _}
import scala.util.{Try, Success, Failure}

class CUSwitchMapperTest extends UnitTest with Metadata {

  def quote(pne:PNE)(implicit design:Design) = DotCodegen.quote(pne)

  lazy val design = new Design with PlaceAndRoute {
    // PNodes
    override val arch = SN_4x4 
    val mapper = new CUMapper().routers.collect{case vr:VectorRouter => vr}.head
    def checkRange(start:PCU, min:Int, max:Int, shouldContain:List[PCU], shouldNotContain:List[PCU]) = {
      def cuCons(pcu:PCL, path:mapper.FatPath) = { 
        if ((path.size >= min) && (path.size < max) && (pcu!=start))
          Some(path)
        else
          None
      }
      def sbCons(psb:PSB, path:mapper.FatPath) = if (path.size < max) Some(path) else None
      val result = mapper.advance(start, cuCons _, sbCons _)
      // println(s"start: ${quote(start)}")
      //result.foreach { case (to, path) =>
      //  println(s"- hop:${path.size} to:${quote(to)} path:${CUSwitchMapper.quote(path)}")
      //}
      //println(s"number of options: ${result.size}")
      val neighbors = result.map(_._1)
      shouldContain.foreach { c =>
        assert(neighbors.contains(c))
      }
      shouldNotContain.foreach { c =>
        assert(!neighbors.contains(c))
      }
    }
    new CUVectorDotPrinter("TestSwitch.dot").print
  }

  "SwitchBox Connection 1 hop" should "success" taggedAs(WIP) in {
    val arr = design.arch.cuArray
    val shouldContain = List(arr(2)(1), arr(1)(2));
    design.checkRange(arr(1)(1), 1, 2, shouldContain, design.arch.cus.diff(shouldContain))
  }
  "SwitchBox Connection 2 hop" should "success" in {
    val arr = design.arch.cuArray
    val shouldContain = List(arr(2)(0), arr(2)(1), arr(2)(2))
    design.checkRange(arr(1)(1), 2, 3, shouldContain, design.arch.cus.diff(shouldContain))
  }
  "SwitchBox Connection 3 hop" should "success" in {
    val arr = design.arch.cuArray
    val shouldContain = List(arr(1)(0), arr(2)(0), arr(3)(0),
                         arr(2)(1), arr(3)(1),
                         arr(1)(2), arr(2)(2), arr(3)(2),
                         arr(2)(3))
    design.checkRange(arr(1)(1), 3, 4, shouldContain, design.arch.cus.diff(shouldContain))
  }
  "SwitchBox Connection 4 hop" should "success" taggedAs(WIP) in {
    val arr = design.arch.cuArray
    val shouldNotContain = List(arr(0)(3), arr(1)(1), arr(2)(1))
    design.checkRange(arr(1)(1), 4, 5, design.arch.cus.diff(shouldNotContain), shouldNotContain)
  }

  "SwitchBox Connection 5 hop" should "success" in {
    val arr = design.arch.cuArray
    val shouldNotContain = List(arr(1)(1))
    design.checkRange(arr(1)(1), 1, 7, design.arch.cus.diff(shouldNotContain), shouldNotContain)
  }

  "SwitchBox Connection 5 Compare BFS advance with DFS advance" should "success" in {
    val arr = design.arch.cuArray
    val start = arr(1)(1); val min = 1; val max = 7
    def cuCons(toVin:PIB, path:design.Path) = { 
      val pcu = toVin.src
      (path.size >= min) && (path.size < max) && (pcu!=start)
    }
    def sbCons(psb:PSB, path:design.Path) = (path.size < max)
    val result1 = design.advanceBFS((pne:PNE) => pne.vectorIO.outs)(start, cuCons _, sbCons _)(design)
    val result2 = design.advanceDFS((pne:PNE) => pne.vectorIO.outs)(start, cuCons _, sbCons _)(design)
    result1 should equal (result2)
  }

  "SwitchBox Mapping" should "success" in {
    new Design {
      top = Top()
      // Nodes
      val vts = List.fill(5)(Vector())
      val outer = Sequential("outer", top, Nil) { implicit CU => }
      val c0 = Pipeline("c0", outer, Nil){ implicit CU => 
        CU.vecOut(vts(0)) 
      }
      val c1 = Pipeline("c1", outer, Nil){ implicit CU => 
        CU.vecIn(vts(0))
        CU.vecOut(vts(1)) 
      }
      val c2 = Pipeline("c2", outer, Nil){ implicit CU => 
        CU.vecIn(vts(1))
        CU.vecIn(vts(0))
        CU.vecOut(vts(2)) 
      }
      val c3 = Pipeline("c3", outer, Nil){ implicit CU => 
        CU.vecIn(vts(2))
        CU.vecOut(vts(3)) 
        CU.vecIn(vts(1))
      }
      val c4 = Pipeline("c4", outer, Nil){ implicit CU => 
        CU.vecIn(vts(1))
        CU.vecIn(vts(3))
      }
      val cus = c0::c1::c2::c3::c4::Nil
      top.innerCUs(cus)
      top.outerCUs(outer::Nil)
      top.vectors(vts)
      // PNodes
      override val arch = SN_4x4 
      // Mapping
      val mapper = new CUMapper()

      new PIRDataDotGen().run
      Try {
        mapper.map(PIRMap.empty)
      } match {
        case Success(mapping) => 
          new CUVectorDotPrinter("TestSwitchMapping.dot").print
        case Failure(e) =>
          println(e)
          new CUVectorDotPrinter("TestSwitchMapping.dot").print; throw e
      }
    }
  }

  "SwitchBox Mapping - DotProduct" should "success" in {
    new Design {
      top = Top()
      val aos = List.tabulate(1) { i => ArgOut(s"ao$i") }
      val sls = Nil
      // Nodes
      val vts = List.fill(12)(Vector())
      val outer = Sequential("outer", top, Nil) { implicit CU => }
      val c00 = Pipeline("c00", outer, Nil){ implicit CU => 
        CU.vecOut(vts(4)) 
      }
      val c01 = Pipeline("c01", outer, Nil){ implicit CU => 
        CU.vecOut(vts(5)) 
      }
      val c0 = Pipeline("c0", outer, Nil){ implicit CU => 
        CU.vecOut(vts(0)) 
        CU.vecIn(vts(4))
        CU.vecIn(vts(5))
      }
      val c10 = Pipeline("c10", outer, Nil){ implicit CU => 
        CU.vecOut(vts(6)) 
      }
      val c11 = Pipeline("c11", outer, Nil){ implicit CU => 
        CU.vecOut(vts(7)) 
      }
      val c1 = Pipeline("c1", outer, Nil){ implicit CU => 
        CU.vecIn(vts(6))
        CU.vecIn(vts(7))
        CU.vecOut(vts(1)) 
      }
      val c20 = Pipeline("c20", outer, Nil){ implicit CU => 
        CU.vecOut(vts(8)) 
      }
      val c21 = Pipeline("c21", outer, Nil){ implicit CU => 
        CU.vecOut(vts(9)) 
      }
      val c2 = Pipeline("c2", outer, Nil){ implicit CU => 
        CU.vecOut(vts(2)) 
        CU.vecIn(vts(8))
        CU.vecIn(vts(9))
      }
      val c30 = Pipeline("c30", outer, Nil){ implicit CU => 
        CU.vecOut(vts(10)) 
      }
      val c31 = Pipeline("c31", outer, Nil){ implicit CU => 
        CU.vecOut(vts(11)) 
      }
      val c3 = Pipeline("c3", outer, Nil){ implicit CU => 
        CU.vecOut(vts(3)) 
        CU.vecIn(vts(10))
        CU.vecIn(vts(11))
      }
      val c4 = Pipeline("c4", outer, Nil){ implicit CU => 
        CU.vecIn(vts(0))
        CU.vecIn(vts(1))
        CU.vecIn(vts(2))
        CU.vecIn(vts(3))
        CU.scalarOut(aos(0))
      }
      val cus = c00::c01::c0::c10::c11::c1::c20::c21::c2::c30::c31::c3::c4::Nil
      top.innerCUs(cus)
      top.outerCUs(outer::Nil)
      top.scalars(sls ++ aos)
      top.vectors(vts)
      // PNodes
      implicit override val arch = SN_4x4 

      new ScalarBundling().run
      new PIRPrinter().run
      // Mapping
      val mapper = new CUMapper()

      new PIRDataDotGen().run
      Try {
        mapper.map(PIRMap.empty)
      } match {
        case Success(mapping) => 
          new CUVectorDotPrinter("TestDotProduct.dot").print(mapping)
        case Failure(e) => 
          MapperLogger.dprintln(e)
          MapperLogger.close
          new CUVectorDotPrinter("TestDotProduct.dot").print; throw e
      }
    }
  }

  "SwitchBox Mapping: Dependency out of order" should "success" in {
    new Design {
      top = Top()
      // Nodes
      val vts = List.fill(5)(Vector())
      val outer = Sequential("outer", top, Nil) { implicit CU => }
      val c0 = Pipeline("c0", outer, Nil){ implicit CU => 
        CU.vecOut(vts(0)) 
      }
      val c1 = Pipeline("c1", outer, Nil){ implicit CU => 
        CU.vecIn(vts(0))
        CU.vecOut(vts(1)) 
      }
      val c2 = Pipeline("c2", outer, Nil){ implicit CU => 
        CU.vecIn(vts(1))
        CU.vecIn(vts(0))
        CU.vecOut(vts(2)) 
      }
      val c3 = Pipeline("c3", outer, Nil){ implicit CU => 
        CU.vecIn(vts(1))
        CU.vecIn(vts(2))
        CU.vecOut(vts(3)) 
      }
      val c4 = Pipeline("c4", outer, Nil){ implicit CU => 
        CU.vecIn(vts(1))
        CU.vecIn(vts(3))
      }
      val cus = (c0::c1::c2::c3::c4::Nil).reverse
      top.innerCUs(cus)
      top.outerCUs(outer::Nil)
      top.vectors(vts)
      // PNodes
      implicit override val arch = SN_4x4 
      // Mapping
      val mapper = new CUMapper()
      new PIRDataDotGen().run
      Try {
        mapper.map(PIRMap.empty)
      } match {
        case Success(mapping) => 
          new CUVectorDotPrinter("TestOODependency.dot").print
        case Failure(e) => 
          new CUVectorDotPrinter("TestOODependency.dot").print; throw e
      }
      MapperLogger.close
    }
  }

  "SwitchNetwork Connection" should "success" in {
    new Design with PlaceAndRoute {
      top = Top()
      val aos = Nil 
      val sls = Nil
      // Nodes
      val vts = Nil 
      val cus = Nil 
      top.innerCUs(cus)
      top.scalars(sls ++ aos)
      top.vectors(vts)
      // PNodes
      implicit override val arch = SN_4x4 

      def advance(start:PNE, validCons:(PIB, Path) => Boolean, advanceCons:(PSB, Path) => Boolean):PathMap =
        advance((pne:PNE) => pne.vectorIO.outs)(start, validCons, advanceCons)

      def advanceCons(psb:PSB, path:Path) = { 
        (path.size < 5) // path is within maximum hop to continue
      }
      {
        val start = arch.top
        def validCons(toVin:PIB, path:Path) = {
          val to = toVin.src
          coordOf.get(to).fold(false) { _ == (1,1) } &&
          (to!=start) // path doesn't end at depended CU
        }
        val paths = advance(start, validCons _, advanceCons _)
        assert(paths.size>0)
      }
      {
        val start = arch.mcs(3) 
        def validCons(toVin:PIB, path:Path) = {
          val to = toVin.src
          to == arch.top &&
          (to!=start) // path doesn't end at depended CU
        }
        val paths = advance(start, validCons _, advanceCons _)
        assert(paths.size>0)
      }
      {
        val start = arch.mcs(3) 
        val target = arch.cuArray(1)(1) 
        def validCons(toVin:PIB, path:Path) = {
          val to = toVin.src
          to == target &&
          (to!=start) // path doesn't end at depended CU
        }
        val paths = advance(start, validCons _, advanceCons _)
        assert(paths.size>0)
      }
      {
        val start = arch.cuArray(1)(1) 
        val target = arch.mcs(3)  
        def validCons(toVin:PIB, path:Path) = {
          val to = toVin.src
          to == target &&
          (to!=start) // path doesn't end at depended CU
        }
        val paths = advance(start, validCons _, advanceCons _)
        assert(paths.size>0)
      }
      {
        val start = arch.mcs(3) 
        val target = arch.cuArray(0)(1) 
        def validCons(toVin:PIB, path:Path) = {
          val to = toVin.src
          to == target &&
          (to!=start) // path doesn't end at depended CU
        }
        val paths = advance(start, validCons _, advanceCons _)
        assert(paths.size>0)
      }
      {
        val start = arch.cuArray(0)(1) 
        val target = arch.mcs(3)  
        def validCons(toVin:PIB, path:Path) = {
          val to = toVin.src
          to == target &&
          (to!=start) // path doesn't end at depended CU
        }
        val paths = advance(start, validCons _, advanceCons _)
        assert(paths.size>0)
      }
    }
  }

  "SwitchNetwork FatPaths" should "success" taggedAs(WIP) in {
    new Design with FatPlaceAndRoute{
      top = Top()
      val aos = Nil 
      val sls = Nil
      // Nodes
      val vts = Nil 
      val cus = Nil 
      top.innerCUs(cus)
      top.scalars(sls ++ aos)
      top.vectors(vts)
      // PNodes
      implicit override val arch = SN_2x2 

      val sbs = arch.sbArray
      val cuArray = arch.cuArray

      def advanceCons(psb:PSB, fatpath:FatPath) = { 
        (fatpath.size < 5) // path is within maximum hop to continue
      }

      {
        val start = arch.cuArray(0)(0) 
        val target = arch.cuArray(1)(1)  
        def validCons(reached:PCL, path:FatPath):Option[FatPath] = {
          var valid = true
          val to = reached 
          valid &&= (to == target)
          valid &&= (to!=start) // path doesn't end at depended CU
          if (valid) Some(path)
          else None
        }
        val fatpaths = advanceBFS((pne:PNE) => pne.vectorIO.outs)(start, validCons _, advanceCons _)

      
        // Plot fatedge
        //var mp = PIRMap.empty
        //fatpath.foreach { fatedge => fatedge.foreach { case ( vo, vi) => mp = mp.setFB(vi, vo) } }
        //new CUCtrlDotPrinter().print(mp)
        
        assert(fatpaths.forall(_._1 == target))
        fatpaths.foreach { case (to, fatpath) =>
          fatpath.foreach { case fatedge:FatEdge =>
            assert(fatedge.map(_._1.src).toSet.size==1) // Make sure fatEdge have same start PNE
            assert(fatedge.map(_._2.src).toSet.size==1) // Make sure fatEdge have same end PNE
          }
        }

        assert(fatpaths.size==35)

        //var mp = PIRMap.empty
        //val paths = CtrlMapper.filterUsedRoutes(fatpaths, mp)
        //println(paths.size)
        //paths.foreach { path =>
          //var mp = PIRMap.empty; path._2.foreach { case ( vo, vi) => mp = mp.setFB(vi, vo) }
          //new CUCtrlDotPrinter().print(mp);System.in.read()
        //}
        
        // Take edges between csb(1)(1) => csb(1)(2) but leave one available. Then shouldn't reduce
        // number of result
        var mp = PIRMap.empty
        var edges = sbs(1)(1).ctrlIO.outs.flatMap { vout => vout.fanOuts.map { vin => (vout, vin) } }
                    .filter{ case (vo, vi) => vi.src==sbs(1)(2)}
        edges.tail.foreach { case (vo, vi) =>
          mp = mp.setFB(vi, vo)
        }
        val paths = filterUsedRoutes(fatpaths, mp)
        assert(paths.size==35)
        //paths.foreach { path =>
          //var mp = PIRMap.empty; path._2.foreach { case ( vo, vi) => mp = mp.setFB(vi, vo) }
          //new CUCtrlDotPrinter().print(mp);System.in.read()
        //}

        // Take all edges between csb(1)(1) => csb(1)(2)
        //                        csb(1)(1) => csb(2)(1)
        //                        csb(1)(1) => pcus(1)(1)
        // Should reduce number of valid results
        //var edges = csbs(1)(1).outs.flatMap { vout => vout.fanOuts.map { vin => (vout, vin) } }
                    //.filter{ case (vo, vi) => vi.src==csbs(1)(2)}
        //edges ++= csbs(1)(1).outs.flatMap { vout => vout.fanOuts.map { vin => (vout, vin) } }
                  //.filter{ case (vo, vi) => vi.src==csbs(2)(1)}
        //edges ++= csbs(1)(1).outs.flatMap { vout => vout.fanOuts.map { vin => (vout, vin) } }
                  //.filter{ case (vo, vi) => vi.src==cuArray(1)(1)}
        //edges.foreach { case (vo, vi) =>
          //mp = mp.setFB(vi, vo)
        //}
        ////new CUCtrlDotPrinter().print(mp)
        //val paths = CtrlMapper.filterUsedRoutes(fatpaths, mp)
        ////paths.foreach { path =>
          ////var mp = PIRMap.empty; path._2.foreach { case ( vo, vi) => mp = mp.setFB(vi, vo) }
          ////new CUCtrlDotPrinter().print(mp);System.in.read()
        ////}
        //assert(paths.size==8)
      }
    }
  }

}

