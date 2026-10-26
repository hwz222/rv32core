package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the ALU.
//   1. Hand-picked cases with the edge values of each operation.
//   2. Random values, checked against a small model written in Scala.
// The ALU is combinational: poke the inputs, then expect the output at once
// (no clock step).
class AluSpec extends AnyFlatSpec with ChiselScalatestTester {

  val Mask = 0xffffffffL                 // keep the low 32 bits of a Long

  // Puts one case on the ALU and checks the result.
  def check(c: ALU, op: AluOp.Type, a: Long, b: Long, want: Long): Unit = {
    c.io.a.poke(a.U(32.W))
    c.io.b.poke(b.U(32.W))
    c.io.op.poke(op)
    c.io.out.expect(want.U(32.W), f"op=$op a=0x$a%08x b=0x$b%08x")
  }

  // ----------------------------------------------------------- hand-picked

  "ALU" should "give the right result for edge cases" in {
    // (operation, a, b, expected result)
    val cases = Seq(
      (AluOp.ADD,  3L,           4L,           7L),
      (AluOp.ADD,  0xffffffffL,  1L,           0L),           // overflow wraps
      (AluOp.SUB,  7L,           3L,           4L),
      (AluOp.SUB,  0L,           1L,           0xffffffffL),  // 0 - 1 = -1
      (AluOp.SLL,  1L,           31L,          0x80000000L),
      (AluOp.SLL,  0xffffffffL,  4L,           0xfffffff0L),  // high bits drop
      (AluOp.SLL,  1L,           33L,          2L),           // only b(4,0): shift 1
      (AluOp.SRL,  0x80000000L,  4L,           0x08000000L),  // fills with 0
      (AluOp.SRL,  0x80000000L,  33L,          0x40000000L),  // shift 1
      (AluOp.SRA,  0x80000000L,  4L,           0xf8000000L),  // fills with the sign
      (AluOp.SRA,  0x40000000L,  4L,           0x04000000L),  // positive: fills with 0
      (AluOp.SRA,  0x80000000L,  31L,          0xffffffffL),
      (AluOp.SLT,  0xffffffffL,  1L,           1L),           // -1 < 1
      (AluOp.SLT,  1L,           0xffffffffL,  0L),
      (AluOp.SLT,  0x80000000L,  0x7fffffffL,  1L),           // most negative < most positive
      (AluOp.SLTU, 0xffffffffL,  1L,           0L),           // unsigned: big number
      (AluOp.SLTU, 1L,           0xffffffffL,  1L),
      (AluOp.SLTU, 5L,           5L,           0L),           // equal is not less
      (AluOp.XOR,  0x0f0f0f0fL,  0x00ff00ffL,  0x0ff00ff0L),
      (AluOp.OR,   0x0f0f0f0fL,  0x00ff00ffL,  0x0fff0fffL),
      (AluOp.AND,  0x0f0f0f0fL,  0x00ff00ffL,  0x000f000fL)
    )
    test(new ALU) { c =>
      for ((op, a, b, want) <- cases) check(c, op, a, b, want)
    }
  }

  // ----------------------------------------------------------- random

  // What each operation should give, computed with Scala Long (64 bits).
  def model(op: AluOp.Type, a: Long, b: Long): Long = {
    val sa = a.toInt.toLong              // the same 32 bits read as signed
    val sb = b.toInt.toLong
    val sh = (b & 31).toInt
    val r =
      if (op == AluOp.ADD) a + b
      else if (op == AluOp.SUB) a - b
      else if (op == AluOp.SLL) a << sh
      else if (op == AluOp.SRL) a >>> sh
      else if (op == AluOp.SRA) sa >> sh
      else if (op == AluOp.SLT) (if (sa < sb) 1L else 0L)
      else if (op == AluOp.SLTU) (if (a < b) 1L else 0L)
      else if (op == AluOp.XOR) a ^ b
      else if (op == AluOp.OR) a | b
      else a & b                         // AND
    r & Mask
  }

  it should "match the Scala model on random values" in {
    val rnd = new scala.util.Random(1)   // fixed seed: the same values every run
    val ops = Seq(AluOp.ADD, AluOp.SUB, AluOp.SLL, AluOp.SRL, AluOp.SRA,
                  AluOp.SLT, AluOp.SLTU, AluOp.XOR, AluOp.OR, AluOp.AND)
    test(new ALU) { c =>
      for (op <- ops; _ <- 0 until 200) {
        val a = rnd.nextLong() & Mask
        val b = rnd.nextLong() & Mask
        check(c, op, a, b, model(op, a, b))
      }
    }
  }
}
