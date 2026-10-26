package rvsc

import chisel3._
import chisel3.util._

// 32 x 32 multiplier for mul, mulh, mulhsu, mulhu. Combinational: the
// result is ready in the same cycle (EX), like the ALU. No Chisel `*`: the
// product is built from partial products and an adder tree, with only the
// operations the ALU already uses (+, -, shifts, muxes, Cat / Fill).
//
// Inputs come after EX forwarding (rs1FwdE, rs2FwdE), the same as the ALU.
//
// Signedness per operation (the instruction decides how each input is read):
//   op      a        b        output
//   MUL     any      any      product(31, 0)   (same for signed / unsigned)
//   MULH    signed   signed   product(63, 32)
//   MULHSU  signed   unsigned product(63, 32)
//   MULHU   unsigned unsigned product(63, 32)
//
// Algorithm (partial products + adder tree), 4-bit example a = 1011, b = 0110:
//   b(0) = 0 :   0000
//   b(1) = 1 :  1011_       (a << 1)
//   b(2) = 1 : 1011__       (a << 2)
//   b(3) = 0 : 0000
//   sum       01000010  = 11 * 6 = 66
//
//   1. Sign-extend a to 64 bits when a is signed (aExt), else zero-extend.
//      Then every partial product  pp(i) = Mux(b(i), aExt << i, 0)  already
//      carries the sign of a.
//   2. For signed b, bit 31 has weight -2^31 (two's complement), so the last
//      partial product pp(31) is SUBTRACTED, not added. For unsigned b it is
//      added like the others.
//   3. Adder tree: add the 32 partial products in pairs, 32 -> 16 -> 8 -> 4
//      -> 2 -> 1 (5 levels of 64-bit adders). Keep everything 64 bits wide
//      (the high bits beyond 64 are not needed).
//   4. out = product(31, 0) for MUL, product(63, 32) for the other three.
//
// Scala help: a Seq of 32 partial products can be made with
//   val pp = (0 until 32).map(i => ...)
// and a tree with a recursive function that adds pairs until one is left,
// or with pp.reduce(_ + _) (that is a chain, depth 31: correct, but a tree
// is the point of this exercise).
class Multiplier extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(Consts.XLEN.W))     // rs1FwdE
    val b   = Input(UInt(Consts.XLEN.W))     // rs2FwdE
    val op  = Input(MulDivOp())                  // MUL, MULH, MULHSU or MULHU (the divide values are not used)
    val out = Output(UInt(Consts.XLEN.W))    // low or high 32 bits of the product
  })

  // 1. Which inputs are signed. MUL: the low 32 bits are the same either way.
  val aSigned = io.op === MulDivOp.MULH || io.op === MulDivOp.MULHSU
  val bSigned = io.op === MulDivOp.MULH
  // 2. a extended to 64 bits (sign bits when a is signed), so every partial
  //    product carries the sign of a.
  val aSignBit = aSigned && io.a(31)
  val aExt     = Cat(Fill(32, aSignBit), io.a)
  // 3. The 32 partial products, each 64 bits.
  val pp = (0 until 32).map{ i=>
    val shifted = (aExt << i)(63, 0)
    Mux(io.b(i), shifted, 0.U(64.W))
  }
  // 4. Adder tree over pp(0) .. pp(30); then add pp(31), or subtract it when
  //    b is signed (bit 31 weighs -2^31). All sums are 64 bits (mod 2^64).
  def buildTree(nodes: Seq[UInt]): UInt = {
    if (nodes.length == 1) {
      nodes.head
    } else {
      val nextLevel = nodes.grouped(2).map {
        case Seq(x, y) => (x + y)(63, 0)
        case Seq(x)    => x
      }.toSeq
      buildTree(nextLevel)
    }
  }
  val sum0to30 = buildTree(pp.take(31))
  val product = Mux(bSigned, (sum0to30 - pp(31))(63, 0), (sum0to30 + pp(31))(63, 0))
  // 5. MUL: low half; MULH, MULHSU, MULHU: high half.
  io.out := Mux(io.op === MulDivOp.MUL, product(31, 0), product(63, 32))
}
