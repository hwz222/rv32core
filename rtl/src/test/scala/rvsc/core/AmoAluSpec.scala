package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for AmoAlu: the new word of each AMO, against a Scala model of
// isa_sim (hart.cpp: AMO*.W). Combinational: poke, then expect.
class AmoAluSpec extends AnyFlatSpec with ChiselScalatestTester {
  val M = 0xffffffffL
  def model(f5: Int, old: Long, src: Long): Long = {
    val so = old.toInt; val sb = src.toInt
    f5 match {
      case 0x01 => src
      case 0x00 => (old + src) & M
      case 0x04 => old ^ src
      case 0x0c => old & src
      case 0x08 => old | src
      case 0x10 => if (so < sb) old else src
      case 0x14 => if (so > sb) old else src
      case 0x18 => if (old < src) old else src
      case 0x1c => if (old > src) old else src
    }
  }
  val ops = Seq(0x01, 0x00, 0x04, 0x0c, 0x08, 0x10, 0x14, 0x18, 0x1c)

  def check(c: AmoAlu, f5: Int, old: Long, src: Long): Unit = {
    c.io.op.poke(f5.U); c.io.old.poke(old.U(32.W)); c.io.src.poke(src.U(32.W))
    c.io.out.expect(model(f5, old, src).U(32.W), f"funct5=0x$f5%02x old=0x$old%08x src=0x$src%08x")
  }

  "AmoAlu" should "make the new word of every AMO (t11 values and corner cases)" in {
    test(new AmoAlu) { c =>
      val vals = Seq(0L, 1L, 5L, 11L, 0xfffffff8L /* -8 */, 0xfffffffdL /* -3 */,
                     0x7fffffffL, 0x80000000L, 0xffffffffL)
      for (f5 <- ops; a <- vals; b <- vals) check(c, f5, a, b)
    }
  }

  it should "match the model on random values" in {
    val rnd = new scala.util.Random(8)
    test(new AmoAlu) { c =>
      for (_ <- 0 until 2000) check(c, ops(rnd.nextInt(ops.size)), rnd.nextLong() & M, rnd.nextLong() & M)
    }
  }
}
