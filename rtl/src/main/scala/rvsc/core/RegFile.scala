package rvsc

import chisel3._
import chisel3.util._

// Register file x0..x31 (ID stage reads, WB stage writes).
// Two read ports (rs1, rs2) and one write port (rd).
//
// Rules:
//   - Read is combinational: rdata follows raddr in the same cycle.
//   - Write happens at the clock edge: with we = 1, wdata is stored at the
//     END of this cycle.
//   - Read first, then write: a read of the register being written in the
//     same cycle gives the OLD value. No bypass here; ID forwarding picks
//     ResultW for that case (rtl/DESIGN.md, section 3.1).
//   - x0 is always 0: writes to x0 are dropped, and reads of x0 give 0.
//   - Reset sets all 32 registers to 0, like isa_sim (hart.h: x_[32] = {}),
//     so both start from the same state.
//
//   e.g. cycle 5: we = 1, waddr = 5, wdata = 0x99, raddr1 = 5
//        cycle 5: rdata1 = old x5        cycle 6: rdata1 = 0x99
class RegFile extends Module {
  val io = IO(new Bundle {
    // read ports (ID)
    val raddr1 = Input(UInt(Consts.REG_ADDR_W.W))    // rs1 = inst(19, 15)
    val raddr2 = Input(UInt(Consts.REG_ADDR_W.W))    // rs2 = inst(24, 20)
    val rdata1 = Output(UInt(Consts.XLEN.W))         // RD1, to the ID forward mux (ForwardAD)
    val rdata2 = Output(UInt(Consts.XLEN.W))         // RD2, to the ID forward mux (ForwardBD)
    // write port (WB)
    val we     = Input(Bool())                       // RegWriteW (valid already included)
    val waddr  = Input(UInt(Consts.REG_ADDR_W.W))    // rdW
    val wdata  = Input(UInt(Consts.XLEN.W))          // ResultW
  })

  // The 32 registers, all 0 after reset.
  val regs = RegInit(VecInit(Seq.fill(Consts.NUM_REGS)(0.U(Consts.XLEN.W))))

  // Write at the end of the cycle; a write to x0 is dropped.
  when(io.we && io.waddr =/= 0.U) {
    regs(io.waddr) := io.wdata
  }

  // Read now (the old value if the same register is written this cycle).
  io.rdata1 := Mux(io.raddr1 === 0.U, 0.U, regs(io.raddr1))
  io.rdata2 := Mux(io.raddr2 === 0.U, 0.U, regs(io.raddr2))
}
