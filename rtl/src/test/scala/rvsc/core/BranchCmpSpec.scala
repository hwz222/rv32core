package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for BranchCmp.
//   1. Each branch with a == b, a < b and a > b, plus the signed / unsigned
//      edge values.
//   2. Random values against a Scala model.
//   3. funct3 010 and 011 (not branches) never give taken.
class BranchCmpSpec extends AnyFlatSpec with ChiselScalatestTester {

  val Mask = 0xffffffffL
  val BEQ = 0; val BNE = 1; val BLT = 4; val BGE = 5; val BLTU = 6; val BGEU = 7
  val names = Map(BEQ -> "beq", BNE -> "bne", BLT -> "blt", BGE -> "bge", BLTU -> "bltu", BGEU -> "bgeu")

  // What each branch should give, computed with Scala Long.
  def model(f3: Int, a: Long, b: Long): Boolean = {
    val sa = a.toInt; val sb = b.toInt           // the same 32 bits read as signed
    f3 match {
      case BEQ  => a == b
      case BNE  => a != b
      case BLT  => sa < sb
      case BGE  => sa >= sb
      case BLTU => a < b
      case BGEU => a >= b
      case _    => false
    }
  }

  def check(c: BranchCmp, f3: Int, a: Long, b: Long, want: Boolean): Unit = {
    c.io.a.poke(a.U(32.W))
    c.io.b.poke(b.U(32.W))
    c.io.funct3.poke(f3.U(3.W))
    c.io.taken.expect(want.B, f"${names.getOrElse(f3, s"funct3=$f3")} a=0x$a%08x b=0x$b%08x")
  }

  "BranchCmp" should "decide each branch on equal, smaller and bigger values" in {
    // (a, b): equal, a < b, a > b, and the edges where signed and unsigned differ
    val pairs = Seq(
      (5L, 5L), (3L, 7L), (7L, 3L),
      (0x80000000L, 0x7fffffffL),   // signed: most negative < most positive; unsigned: bigger
      (0x7fffffffL, 0x80000000L),
      (0xffffffffL, 0L),            // signed: -1 < 0; unsigned: biggest
      (0L, 0xffffffffL),
      (0xffffffffL, 0xffffffffL)
    )
    // a few values written out, so the model is checked too
    assert(model(BLT, 0x80000000L, 0x7fffffffL) && !model(BLTU, 0x80000000L, 0x7fffffffL))
    assert(model(BLT, 0xffffffffL, 0L) && model(BGEU, 0xffffffffL, 0L))
    test(new BranchCmp) { c =>
      for (f3 <- Seq(BEQ, BNE, BLT, BGE, BLTU, BGEU); (a, b) <- pairs)
        check(c, f3, a, b, model(f3, a, b))
    }
  }

  it should "match the Scala model on random values" in {
    val rnd = new scala.util.Random(5)
    test(new BranchCmp) { c =>
      for (f3 <- Seq(BEQ, BNE, BLT, BGE, BLTU, BGEU); i <- 0 until 300) {
        val a = rnd.nextLong() & Mask
        // every fourth pair is equal, so beq / bne also see true
        val b = if (i % 4 == 0) a else rnd.nextLong() & Mask
        check(c, f3, a, b, model(f3, a, b))
      }
    }
  }

  it should "never be taken for funct3 010 and 011" in {
    val rnd = new scala.util.Random(6)
    test(new BranchCmp) { c =>
      for (f3 <- Seq(2, 3); _ <- 0 until 100) {
        val a = rnd.nextLong() & Mask
        check(c, f3, a, rnd.nextLong() & Mask, false)
        check(c, f3, a, a, false)
      }
    }
  }
}
