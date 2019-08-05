import spatial.dsl._
import spatial.lib.ML._
import scala.reflect._

class StreamInfNN4_0 extends StreamInfNN[scala.Int,Int]()()

// 2 layer MLP [field x L1] [L1 x 1]
@spatial abstract class StreamInfNN4[HT:Numeric:ClassTag,T:Num](
  val field:scala.Int = 8,
  val numBatch:scala.Int = 16,
  val batch:scala.Int = 4,
  val L1:scala.Int = 32,
  val L2:scala.Int = 32,
  val L3:scala.Int = 32
)(
  val op1:scala.Int = 1,
  val op2:scala.Int = 1,
  val op3:scala.Int = 1,
  val mp1:scala.Int = 1, // L1/ip1
  val mp2:scala.Int = 1, // L2/ip2
  val mp3:scala.Int = 1, // L2/ip2
  val opb:scala.Int = 1,
  val ipf:scala.Int = math.min(field, 16),
  val ip1:scala.Int = math.min(L1,16),
  val ip2:scala.Int = math.min(L2,16),
  val ip3:scala.Int = math.min(L3,16),
  val ipb:scala.Int = math.min(16,batch),
)(implicit ev:Cast[Text,T]) extends StreamInference[scala.Float,T,T] {

  val mpf = 1
  val L4 = 1
  val op4 = 1
  val W1:Seq[Seq[scala.Float]] = Seq.tabulate(field, L1) { (i,j) => (i*L1 +j) }
  val W2:Seq[Seq[scala.Float]] = Seq.tabulate(L1, L2) { (i,j) => (i*L2 +j) }
  val W3:Seq[Seq[scala.Float]] = Seq.tabulate(L2, L3) { (i,j) => (i*L3 +j) }
  val W4:Seq[Seq[scala.Float]] = Seq.tabulate(L3, L4) { (i,j) => (i*L4 +j) }
  val B1:Seq[scala.Float] = Seq.tabulate(L1) { i => i }
  val B2:Seq[scala.Float] = Seq.tabulate(L2) { i => i }
  val B3:Seq[scala.Float] = Seq.tabulate(L3) { i => i }
  val B4:Seq[scala.Float] = Seq.tabulate(L4) { i => i }

  def accelBody(insram:SRAM2[T]) = { // insram [batch, field]
    // Layer 1
    val w1 = LUT.fromSeq[T](W1.map { _.map { _.to[T]} })
    val b1 = LUT.fromSeq[T](B1.map { _.to[T]})
    val w2 = LUT.fromSeq[T](W2.map { _.map { _.to[T]} })
    val b2 = LUT.fromSeq[T](B2.map { _.to[T]})
    val w3 = LUT.fromSeq[T](W3.map { _.map { _.to[T]} })
    val b3 = LUT.fromSeq[T](B3.map { _.to[T]})
    val w4 = LUT.fromSeq[T](W4.map { _.map { _.to[T]} })
    val b4 = LUT.fromSeq[T](B4.map { _.to[T]})
    val outsram = SRAM[T](batch)
    Foreach(0 until batch par opb) { b =>
      val l1 = SRAM[T](L1)
      val l2 = SRAM[T](L2)
      val l3 = SRAM[T](L3)
      denselayer_tiled[T](w1, b1, ipf, mpf, op1, relu[T] _, out=l1){ i => insram(b,i) }
      denselayer_tiled[T](w2, b2, ip1, mp1, op2, relu[T] _, in=l1, out=l2)
      denselayer_tiled[T](w3, b3, ip2, mp2, op3, relu[T] _, in=l2, out=l3)
      denselayer_tiled[T](w4, b4, ip3, mp3, op4, activation[T](x => x), in=l3){ case (o,d) => outsram(b) = d }
    }
    outsram
  }

  def hostBody(inData:Seq[Seq[scala.Float]]) = {
    inData.map { fields =>
      val l1 = unstaged_denselayer[scala.Float](fields, W1, B1, unstaged_relu _)
      val l2 = unstaged_denselayer[scala.Float](l1, W2, B2, unstaged_relu _)
      val l3 = unstaged_denselayer[scala.Float](l2, W3, B3, unstaged_relu _)
      val l4 = unstaged_denselayer[scala.Float](l3, W4, B4, { x => x })
      l4.head
    }
  }

}
