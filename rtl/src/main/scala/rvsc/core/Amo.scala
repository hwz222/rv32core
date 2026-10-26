package rvsc

import chisel3._
import chisel3.util._

// the small ALU of the AMOs, in MEM. In the second MEM cycle of an AMO
// the old word (DMEM rdata) is here; this makes the word written back.
// No exception can come from here: RISC-V integer operations never trap.
//   op   funct5 of the AMO (Funct5)
//   old  mem[rs1] before the AMO (rd gets this value)
//   src  rs2 (storeDataM)
//   out  the new word for mem[rs1]
class AmoAlu extends Module {
  val io = IO(new Bundle {
    val op  = Input(UInt(5.W))
    val old = Input(UInt(Consts.XLEN.W))
    val src = Input(UInt(Consts.XLEN.W))
    val out = Output(UInt(Consts.XLEN.W))
  })

  val signedLess   = io.old.asSInt < io.src.asSInt
  val unsignedLess = io.old < io.src

  io.out := MuxLookup(io.op, io.src, Seq(    // default: AMOSWAP
    Funct5.AMOADD  -> (io.old + io.src),
    Funct5.AMOXOR  -> (io.old ^ io.src),
    Funct5.AMOAND  -> (io.old & io.src),
    Funct5.AMOOR   -> (io.old | io.src),
    Funct5.AMOMIN  -> Mux(signedLess, io.old, io.src),
    Funct5.AMOMAX  -> Mux(signedLess, io.src, io.old),
    Funct5.AMOMINU -> Mux(unsignedLess, io.old, io.src),
    Funct5.AMOMAXU -> Mux(unsignedLess, io.src, io.old)))
}
