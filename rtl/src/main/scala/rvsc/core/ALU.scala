package rvsc

import chisel3._
import chisel3.util._

class ALU extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(Consts.XLEN.W))
    val b   = Input(UInt(Consts.XLEN.W))
    val op  = Input(AluOp())
    val out = Output(UInt(Consts.XLEN.W))
  })

  val a = io.a
  val b = io.b
  // Shifts use only the low 5 bits of b (rs2 or the immediate).
  val shamt = b(Consts.SHAMT_W - 1, 0)

  io.out := 0.U                          // default: result when no op matches
  switch(io.op) {
    is(AluOp.ADD)  { io.out := a + b }
    is(AluOp.SLL)  { io.out := (a << shamt)(Consts.XLEN - 1, 0) }  // << makes it wider
    is(AluOp.SLT)  { io.out := a.asSInt < b.asSInt }               // Bool -> 0 or 1
    is(AluOp.SLTU) { io.out := a < b }
    is(AluOp.XOR)  { io.out := a ^ b }
    is(AluOp.SRL)  { io.out := a >> shamt }
    is(AluOp.OR)   { io.out := a | b }
    is(AluOp.AND)  { io.out := a & b }
    is(AluOp.SUB)  { io.out := a - b }
    is(AluOp.SRA)  { io.out := (a.asSInt >> shamt).asUInt }        // keeps the sign bit
  }
}
