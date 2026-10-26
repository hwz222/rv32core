package rvsc

import chisel3._
import chisel3.util._

// the commit point. TrapUnit looks at the instruction in MEM and decides
// whether the flow of instructions changes this cycle:
//   trap     the instruction does not run; save it in mepc / mcause / mtval
//            (CsrFile) and jump to the handler (mtvec)
//   mret     the instruction runs; it jumps to mepc
// Either one is a redirect: the PC gets `target`, and the three younger
// instructions (IF, ID, EX) are cleared (flushD, flushE, flushM).
//
// Priority (first match wins); nothing happens for a bubble (valid = 0):
//   1 interrupt  irqPending (tied to false for now)   trap, interrupt = 1, cause = irqCause
//   2 exception  exc.valid  (found in ID or EX)       trap, cause = exc.cause, tval = exc.tval
//   3 CSR access csrIllegal (found by CsrFile in MEM) trap, cause 2 (illegal), tval = inst
//   4 mret       mret                                 no trap, redirect to mepc
// target = mtvec for a trap (direct mode: all traps go to the same place),
//          mepc  for mret.
// mtvec = 0 means "no handler": the trap is still taken; the testbench sees
// trapTarget = 0 in the retire record and stops, like isa_sim.
//
// Core uses the outputs:
//   CsrFile: trap, cause (as 32-bit mcause: interrupt in bit 31), tval,
//            epc = pc, mret
//   PC mux:  redirect -> target (highest priority)
//   HazardUnit: redirectM -> flushD, flushE, flushM;  Divider: kill
//   MEM:     no DMEM request, no CSR write, no rd write when trap
//   MEM/WB:  trap, interrupt, cause, tval, trapTarget = target (retire in WB)
class TrapUnit extends Module {
  val io = IO(new Bundle {
    // the instruction in MEM
    val valid      = Input(Bool())                 // validM
    val pc         = Input(UInt(Consts.XLEN.W))    // pcM
    val inst       = Input(UInt(Consts.ILEN.W))    // instM
    val exc        = Input(new ExcInfo)            // excValidM, excCauseM, excTvalM
    val csrIllegal = Input(Bool())                 // csrFile.io.illegal && csrM
    val mret       = Input(Bool())                 // mretM
    // Interrupt inputs, for a future timer / software interrupt; tied to 0 now
    val irqPending = Input(Bool())
    val irqCause   = Input(UInt(4.W))              // 3 software, 7 timer, 11 external
    // from CsrFile
    val mtvec      = Input(UInt(Consts.XLEN.W))
    val mepc       = Input(UInt(Consts.XLEN.W))
    // decisions
    val trap       = Output(Bool())                // take a trap at this clock edge
    val interrupt  = Output(Bool())                // the trap is an interrupt (mcause bit 31)
    val cause      = Output(UInt(4.W))             // mcause code
    val tval       = Output(UInt(Consts.XLEN.W))
    val redirect   = Output(Bool())                // trap or mret: new PC, clear IF, ID, EX
    val target     = Output(UInt(Consts.XLEN.W))   // mtvec (trap) or mepc (mret)
  })

  // Defaults: nothing happens (normal instruction or bubble).
  io.trap      := false.B
  io.interrupt := false.B
  io.cause     := 0.U
  io.tval      := 0.U

  when(io.valid) {
    when(io.irqPending) {
      io.trap      := true.B
      io.interrupt := true.B
      io.cause     := io.irqCause
    }.elsewhen(io.exc.valid) {
      io.trap  := true.B
      io.cause := io.exc.cause
      io.tval  := io.exc.tval
    }.elsewhen(io.csrIllegal) {
      io.trap  := true.B
      io.cause := Cause.ILLEGAL_INSN.U
      io.tval  := io.inst
    }
  }

  // mret only matters when there is no trap: a trap wins over it.
  val mretTaken = io.valid && io.mret && !io.trap
  io.redirect := io.trap || mretTaken
  io.target   := Mux(io.trap, io.mtvec, io.mepc)
}
