import spatial.dsl._
import spatial.lang.{FileBus,FileBusLastBit,Bus}
import utils.io.files._

case class StreamKmeansParam(
  K:scala.Int = 16, // Number of centroids
  field:scala.Int = 8,
  numBatch:scala.Int = 16,
  batch:scala.Int = 4,
  op:scala.Int = 1,
  kp:scala.Int = 1,
  ip:scala.Int = 8
) extends StreamTemplateParam

class StreamKmeans_0 extends StreamKmeans[scala.Int,Int]
class StreamKmeans_1 extends StreamKmeans[scala.Int,Int]{ override lazy val param = StreamKmeansParam(op=2) }
class StreamKmeans_2 extends StreamKmeans[scala.Int,Int]{ override lazy val param = StreamKmeansParam(kp=2) }
class StreamKmeans_3 extends StreamKmeans[scala.Int,Int]{ override lazy val param = StreamKmeansParam(ip=4) }
class StreamKmeans_4 extends StreamKmeans[scala.Int,Int]{ override lazy val param = StreamKmeansParam(ip=4, kp=2) }
class StreamKmeans_5 extends StreamKmeans[scala.Int,Int]{ override lazy val param = StreamKmeansParam(ip=8, kp=4) }
class StreamKmeans_6 extends StreamKmeans[scala.Int,Int]{ override lazy val param = StreamKmeansParam(ip=8, kp=4, op=2) }

// Given a set of centroids, compute the closest centroid and output centroid indices for a stream of
// records
@spatial abstract class StreamKmeans[HT:Numeric,T:Arith:Num](implicit ev:Cast[Text,T]) extends StreamInference[HT,T,Int] {

  lazy val param = StreamKmeansParam()
  import param._

  val r = scala.util.Random
  val centroids = Seq.tabulate(K) { k => Seq.tabulate(field) { f => r.nextInt(numToken) } }

  def hostBody(inData:Seq[Seq[HT]]) = {
    inData.map { record =>
      centroids.zipWithIndex.minBy { case (cent, i) =>
        record.zip(cent).map { case (r,c) => 
          val d = num[HT].abs(tonum(r)-c)
          d * d
        }.sum
      }._2
    }
  }

  def accelBody(insram:SRAM2[T]):SRAM1[Int] = { 
    val cLUT = LUT.fromSeq[T](centroids.map { _.map { _.to[T] } })
    val outsram = SRAM[Int](batch)
    Foreach(0 until batch par op) { b =>
      val dists = SRAM[T](K)
      Foreach(0 until K par kp) { k =>
        val dist = Reg[T]
        Reduce(dist)(0 until field par ip) { f =>
          val d = Arith[T].abs(insram(b,f) - cLUT(k,f))
          d * d
        } { _ + _ }
        dists(k) = dist.value
      }
      val minDist = Reg[T]
      Reduce(minDist)(0 until K par ip) { k => dists(k) } { Num[T].min(_,_) }
      val minIdx = Reg[Int]
      Reduce(minIdx)(0 until K) { k =>
        mux(dists(k) == minDist.value, k, K+1)
      } { min(_,_) }
      outsram(b) = minIdx.value
    }
    outsram
  }

}