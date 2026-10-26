package rvsc

import chisel3._
import chisel3.util._

// Branch compare (ID stage, cmp in the diagram).
// Says if the branch condition holds for the two forwarded operands.
// It does not look at BranchD: Core makes PCSrcD = (branch && taken && !stall) || jal.
class BranchCmp extends Module {
  val io = IO(new Bundle {
    val a      = Input(UInt(Consts.XLEN.W))   // rs1 after ForwardAD
    val b      = Input(UInt(Consts.XLEN.W))   // rs2 after ForwardBD
    val funct3 = Input(UInt(3.W))             // inst(14, 12): which branch
    val taken  = Output(Bool())
  })

  // The six branches need only three compares.
  val equal = io.a === io.b
  val lt    = io.a.asSInt < io.b.asSInt       // signed:   blt, bge
  val ltu   = io.a < io.b                     // unsigned: bltu, bgeu

  // funct3(2, 1) picks the compare and funct3(0) turns it around:
  //   000 beq  equal    001 bne  !equal
  //   100 blt  lt       101 bge  !lt
  //   110 bltu ltu      111 bgeu !ltu
  // funct3 010 / 011 are not branches (the decoder marks them illegal).
  val flip = io.funct3(0)
  io.taken := false.B                         // default, also for 010 / 011
  switch(io.funct3(2, 1)) {
    is("b00".U) { io.taken := equal ^ flip }
    is("b10".U) { io.taken := lt ^ flip }
    is("b11".U) { io.taken := ltu ^ flip }
  }
}
