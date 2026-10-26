package rvsc

import chisel3._
import chisel3.util._

// Immediate generator (ID stage, ImmGen in the diagram).
// Builds the sign-extended 32-bit immediate of one of five formats.
// The sign bit is always inst(31).
// Z: the 5-bit zimm of csrrwi / csrrsi / csrrci (the rs1 field),
// zero-extended (not sign-extended).
class ImmGen extends Module {
  val io = IO(new Bundle {
    val inst    = Input(UInt(Consts.ILEN.W))   // the whole instruction (IF/ID inst)
    val immType = Input(ImmSel())              // format, chosen by the decoder
    val imm     = Output(UInt(Consts.XLEN.W))  // ImmD
  })

  val inst = io.inst
  val sign = inst(31)

  // All five formats; the widths of each part add up to 32.
  val immI = Cat(Fill(20, sign), inst(31, 20))                          // 20 + 12
  val immS = Cat(Fill(20, sign), inst(31, 25), inst(11, 7))             // 20 + 7 + 5
  val immB = Cat(Fill(20, sign), inst(7), inst(30, 25), inst(11, 8),
                 0.U(1.W))                                              // 20 + 1 + 6 + 4 + 1
  val immU = Cat(inst(31, 12), 0.U(12.W))                               // 20 + 12
  val immJ = Cat(Fill(12, sign), inst(19, 12), inst(20), inst(30, 21),
                 0.U(1.W))                                              // 12 + 8 + 1 + 10 + 1
  val immZ = Cat(0.U(27.W), inst(19, 15))                               // 27 + 5, zero-extended

  io.imm := 0.U                          // default
  switch(io.immType) {
    is(ImmSel.I) { io.imm := immI }
    is(ImmSel.S) { io.imm := immS }
    is(ImmSel.B) { io.imm := immB }
    is(ImmSel.U) { io.imm := immU }
    is(ImmSel.J) { io.imm := immJ }
    is(ImmSel.Z) { io.imm := immZ }
  }
}
