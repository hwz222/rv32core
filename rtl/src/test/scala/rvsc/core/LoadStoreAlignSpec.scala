package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for StoreAlign and LoadAlign. Legal inputs only:
// real load / store funct3 and aligned addresses (see LoadStoreAlign.scala).
//   1. StoreAlign: every size and byte position, written out by hand.
//   2. LoadAlign:  every load and position, with the top bit 0 and 1.
//   3. Random values against Scala models.
//   4. Store then load: write a value into a word with wdata / wstrb, read it
//      back with the same size and position, and get the value again.
class LoadStoreAlignSpec extends AnyFlatSpec with ChiselScalatestTester {

  val Mask = 0xffffffffL
  // (funct3, legal addrLow values)
  val stores = Seq((0, Seq(0, 1, 2, 3)), (1, Seq(0, 2)), (2, Seq(0)))           // sb sh sw
  val loads  = Seq((0, Seq(0, 1, 2, 3)), (1, Seq(0, 2)), (2, Seq(0)),           // lb lh lw
                   (4, Seq(0, 1, 2, 3)), (5, Seq(0, 2)))                         // lbu lhu

  // ----------------------------------------------------------- models

  // StoreAlign model: (wdata, wstrb)
  def storeModel(f3: Int, lo: Int, data: Long): (Long, Int) = (f3 & 3) match {
    case 0 => val b = data & 0xff;   (b * 0x01010101L, 1 << lo)
    case 1 => val h = data & 0xffff; (h * 0x00010001L, 3 << lo)
    case _ => (data & Mask, 0xf)
  }

  // LoadAlign model
  def loadModel(f3: Int, lo: Int, rdata: Long): Long = {
    val shifted = rdata >>> (8 * lo)
    f3 match {
      case 0 => (shifted & 0xff).toByte.toLong & Mask          // lb: sign-extend
      case 1 => (shifted & 0xffff).toShort.toLong & Mask       // lh: sign-extend
      case 2 => rdata & Mask                                   // lw
      case 4 => shifted & 0xff                                 // lbu
      case 5 => shifted & 0xffff                               // lhu
    }
  }

  // ----------------------------------------------------------- StoreAlign

  def checkStore(c: StoreAlign, f3: Int, lo: Int, data: Long, wdata: Long, wstrb: Int): Unit = {
    c.io.funct3.poke(f3.U(3.W))
    c.io.addrLow.poke(lo.U(2.W))
    c.io.storeData.poke(data.U(32.W))
    val msg = f"funct3=$f3 addrLow=$lo data=0x$data%08x"
    c.io.wdata.expect(wdata.U(32.W), msg)
    c.io.wstrb.expect(wstrb.U(4.W), msg)
  }

  "StoreAlign" should "copy the data and enable the right bytes" in {
    // storeData = 0x11223344
    val cases = Seq(
      (0, 0, 0x44444444L, 0x1), (0, 1, 0x44444444L, 0x2),     // sb
      (0, 2, 0x44444444L, 0x4), (0, 3, 0x44444444L, 0x8),
      (1, 0, 0x33443344L, 0x3), (1, 2, 0x33443344L, 0xc),     // sh
      (2, 0, 0x11223344L, 0xf)                                // sw
    )
    test(new StoreAlign) { c =>
      for ((f3, lo, wd, ws) <- cases) checkStore(c, f3, lo, 0x11223344L, wd, ws)
    }
  }

  it should "match the Scala model on random data" in {
    val rnd = new scala.util.Random(7)
    test(new StoreAlign) { c =>
      for ((f3, los) <- stores; lo <- los; _ <- 0 until 50) {
        val data = rnd.nextLong() & Mask
        val (wd, ws) = storeModel(f3, lo, data)
        checkStore(c, f3, lo, data, wd, ws)
      }
    }
  }

  // ----------------------------------------------------------- LoadAlign

  def checkLoad(c: LoadAlign, f3: Int, lo: Int, rdata: Long, want: Long): Unit = {
    c.io.funct3.poke(f3.U(3.W))
    c.io.addrLow.poke(lo.U(2.W))
    c.io.rdata.poke(rdata.U(32.W))
    c.io.loadData.expect(want.U(32.W), f"funct3=$f3 addrLow=$lo rdata=0x$rdata%08x")
  }

  "LoadAlign" should "pick the bytes and extend them" in {
    // top bit 1 in every byte: sign extension fills with 1
    val w = 0x8899aabbL
    val cases = Seq(
      (0, 0, 0xffffffbbL), (0, 1, 0xffffffaaL), (0, 2, 0xffffff99L), (0, 3, 0xffffff88L), // lb
      (4, 0, 0x000000bbL), (4, 1, 0x000000aaL), (4, 2, 0x00000099L), (4, 3, 0x00000088L), // lbu
      (1, 0, 0xffffaabbL), (1, 2, 0xffff8899L),                                           // lh
      (5, 0, 0x0000aabbL), (5, 2, 0x00008899L),                                           // lhu
      (2, 0, 0x8899aabbL)                                                                 // lw
    )
    // top bit 0 in every byte: lb gives the same as lbu
    val w2 = 0x11223344L
    val cases2 = Seq((0, 0, 0x44L), (0, 3, 0x11L), (1, 0, 0x3344L), (1, 2, 0x1122L))
    test(new LoadAlign) { c =>
      for ((f3, lo, want) <- cases) checkLoad(c, f3, lo, w, want)
      for ((f3, lo, want) <- cases2) checkLoad(c, f3, lo, w2, want)
    }
  }

  it should "match the Scala model on random data" in {
    val rnd = new scala.util.Random(8)
    test(new LoadAlign) { c =>
      for ((f3, los) <- loads; lo <- los; _ <- 0 until 50) {
        val rdata = rnd.nextLong() & Mask
        checkLoad(c, f3, lo, rdata, loadModel(f3, lo, rdata))
      }
    }
  }

  // ----------------------------------------------------------- store then load

  "StoreAlign and LoadAlign" should "give back the stored value" in {
    // chiseltest runs one module per test(), so: store first, then load.
    val rnd = new scala.util.Random(9)
    val cases = for ((f3, los) <- stores; lo <- los; _ <- 0 until 30)
      yield (f3, lo, rnd.nextLong() & Mask, rnd.nextLong() & Mask)   // (funct3, addrLow, data, old word)

    // 1. StoreAlign gives wdata / wstrb; write them into the old word like DMem does.
    val written = scala.collection.mutable.ArrayBuffer[Long]()
    test(new StoreAlign) { s =>
      for ((f3, lo, data, old) <- cases) {
        s.io.funct3.poke(f3.U); s.io.addrLow.poke(lo.U); s.io.storeData.poke(data.U(32.W))
        val wdata = s.io.wdata.peek().litValue.toLong
        val wstrb = s.io.wstrb.peek().litValue.toInt
        val mask  = (0 until 4).filter(k => ((wstrb >> k) & 1) == 1).map(k => 0xffL << (8 * k)).sum
        val word  = (old & ~mask & Mask) | (wdata & mask)
        written += word
      }
    }

    // 2. LoadAlign reads the same size and position back (unsigned): the stored part.
    test(new LoadAlign) { l =>
      for (((f3, lo, data, _), word) <- cases.zip(written)) {
        val loadF3 = if (f3 == 2) 2 else f3 + 4       // lbu, lhu, lw
        val want   = f3 match { case 0 => data & 0xff; case 1 => data & 0xffff; case _ => data }
        l.io.funct3.poke(loadF3.U); l.io.addrLow.poke(lo.U); l.io.rdata.poke(word.U(32.W))
        l.io.loadData.expect(want.U(32.W), f"store funct3=$f3 addrLow=$lo data=0x$data%08x")
      }
    }
  }
}
