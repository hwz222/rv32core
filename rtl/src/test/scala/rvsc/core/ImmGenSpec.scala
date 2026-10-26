package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for ImmGen.
//   1. Real instructions: encodings made by the RISC-V assembler (gcc +
//      objdump), with the immediate each one must give.
//   2. Random instruction words, checked against a small model in Scala.
// ImmGen is combinational: poke, then expect at once.
class ImmGenSpec extends AnyFlatSpec with ChiselScalatestTester {

  val Mask = 0xffffffffL

  def check(c: ImmGen, inst: Long, sel: ImmSel.Type, want: Long): Unit = {
    c.io.inst.poke(inst.U(32.W))
    c.io.immType.poke(sel)
    c.io.imm.expect(want.U(32.W), f"inst=0x$inst%08x sel=$sel")
  }

  // ----------------------------------------------------------- real instructions

  "ImmGen" should "decode the immediates of real instructions" in {
    // (encoding, format, immediate, assembly)
    val cases = Seq(
      (0xfff00093L, ImmSel.I, 0xffffffffL, "addi x1, x0, -1"),
      (0x7ff00093L, ImmSel.I, 0x000007ffL, "addi x1, x0, 2047"),
      (0x40315093L, ImmSel.I, 0x00000403L, "srai x1, x2, 3 (bit 10 marks srai)"),
      (0xfe512e23L, ImmSel.S, 0xfffffffcL, "sw x5, -4(x2)"),
      (0x7e512fa3L, ImmSel.S, 0x000007ffL, "sw x5, 2047(x2)"),
      (0xfe000ce3L, ImmSel.B, 0xfffffff8L, "beq x0, x0, -8"),
      (0x7e209fe3L, ImmSel.B, 0x00000ffeL, "bne x1, x2, +4094"),
      (0x123450b7L, ImmSel.U, 0x12345000L, "lui x1, 0x12345"),
      (0xfffff0b7L, ImmSel.U, 0xfffff000L, "lui x1, 0xfffff"),
      (0x80000097L, ImmSel.U, 0x80000000L, "auipc x1, 0x80000"),
      (0xffdff06fL, ImmSel.J, 0xfffffffcL, "jal x0, -4"),
      (0x7ffff0efL, ImmSel.J, 0x000ffffeL, "jal x1, +1048574"),
      (0x3402d0f3L, ImmSel.Z, 0x00000005L, "csrrwi x1, mscratch, 5"),
      (0x340fe0f3L, ImmSel.Z, 0x0000001fL, "csrrsi x1, mscratch, 31 (zero-extended, not -1)"),
      (0xfff060f3L, ImmSel.Z, 0x00000000L, "csrrsi x1, 0xfff, 0 (bit 31 set: still 0)")
    )
    test(new ImmGen) { c =>
      for ((inst, sel, want, _) <- cases) check(c, inst, sel, want)
    }
  }

  // ----------------------------------------------------------- random

  // Bits hi..lo of x, moved down to bit 0.
  def bits(x: Long, hi: Int, lo: Int): Long = (x >>> lo) & ((1L << (hi - lo + 1)) - 1)

  // Sign-extends the low n bits of x to 32 bits.
  def sext(x: Long, n: Int): Long = ((x << (64 - n)) >> (64 - n)) & Mask

  def model(inst: Long, sel: ImmSel.Type): Long = {
    if (sel == ImmSel.I) sext(bits(inst, 31, 20), 12)
    else if (sel == ImmSel.S) sext((bits(inst, 31, 25) << 5) | bits(inst, 11, 7), 12)
    else if (sel == ImmSel.B)
      sext((bits(inst, 31, 31) << 12) | (bits(inst, 7, 7) << 11) |
           (bits(inst, 30, 25) << 5) | (bits(inst, 11, 8) << 1), 13)
    else if (sel == ImmSel.U) bits(inst, 31, 12) << 12
    else if (sel == ImmSel.Z) bits(inst, 19, 15)
    else // J
      sext((bits(inst, 31, 31) << 20) | (bits(inst, 19, 12) << 12) |
           (bits(inst, 20, 20) << 11) | (bits(inst, 30, 21) << 1), 21)
  }

  it should "match the Scala model on random instruction words" in {
    val rnd = new scala.util.Random(2)   // fixed seed: the same values every run
    val sels = Seq(ImmSel.I, ImmSel.S, ImmSel.B, ImmSel.U, ImmSel.J, ImmSel.Z)
    test(new ImmGen) { c =>
      for (sel <- sels; _ <- 0 until 300) {
        val inst = rnd.nextLong() & Mask
        check(c, inst, sel, model(inst, sel))
      }
    }
  }

  it should "give an even offset for B and J" in {
    val rnd = new scala.util.Random(3)
    test(new ImmGen) { c =>
      for (sel <- Seq(ImmSel.B, ImmSel.J); _ <- 0 until 100) {
        c.io.inst.poke((rnd.nextLong() & Mask).U(32.W))
        c.io.immType.poke(sel)
        assert((c.io.imm.peek().litValue & 1) == 0, s"bit 0 must be 0 for $sel")
      }
    }
  }
}
