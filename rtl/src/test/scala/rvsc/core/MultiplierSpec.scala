package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the multiplier. It is combinational: poke, then expect.
//   1. Edge values for the four operations, against a BigInt model.
//   2. 5000 random pairs.
// The model uses the RISC-V definitions directly: the product of the two
// values read as signed / unsigned, then the low or high 32 bits.
class MultiplierSpec extends AnyFlatSpec with ChiselScalatestTester {

  val M32 = BigInt(1) << 32
  def u(x: Long): BigInt = BigInt(x) & (M32 - 1)                       // as unsigned 32-bit
  def s(x: Long): BigInt = { val v = u(x); if (v >= (M32 >> 1)) v - M32 else v }  // as signed

  def model(op: MulDivOp.Type, a: Long, b: Long): BigInt = {
    val p = op match {
      case MulDivOp.MUL    => u(a) * u(b)
      case MulDivOp.MULH   => s(a) * s(b)
      case MulDivOp.MULHSU => s(a) * u(b)
      case MulDivOp.MULHU  => u(a) * u(b)
    }
    val p64 = p & ((BigInt(1) << 64) - 1)                              // two's complement, 64 bits
    if (op == MulDivOp.MUL) p64 & (M32 - 1) else p64 >> 32
  }

  val ops = Seq(MulDivOp.MUL, MulDivOp.MULH, MulDivOp.MULHSU, MulDivOp.MULHU)
  val edges = Seq(0L, 1L, 2L, 3L, 0x7fffffffL, 0x80000000L, 0x80000001L, 0xffffffffL, 0xfffffffeL,
                  0x12345678L, 0xdeadbeefL, 0x00010000L, 0x0000ffffL)

  def check(c: Multiplier, op: MulDivOp.Type, a: Long, b: Long): Unit = {
    c.io.op.poke(op); c.io.a.poke(a.U(32.W)); c.io.b.poke(b.U(32.W))
    c.io.out.expect(model(op, a, b).U(32.W), f"$op a=0x$a%08x b=0x$b%08x")
  }

  "Multiplier" should "match the model on edge values" in {
    test(new Multiplier) { c =>
      for (op <- ops; a <- edges; b <- edges) check(c, op, a, b)
    }
  }

  it should "match the model on random values" in {
    val rnd = new scala.util.Random(5)
    test(new Multiplier) { c =>
      for (_ <- 0 until 5000) {
        val op = ops(rnd.nextInt(4))
        check(c, op, rnd.nextLong() & 0xffffffffL, rnd.nextLong() & 0xffffffffL)
      }
    }
  }
}
