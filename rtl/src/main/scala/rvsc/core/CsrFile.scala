package rvsc

import chisel3._
import chisel3.util._

// the machine-mode CSRs. Read and written in MEM by the CSR instruction
// that is there (rtl/DESIGN.md, section 5.2).
// Every rule below follows isa_sim/src/hart.cpp (csr_read, csr_write, the
// counter update at the end of step()), so the lockstep trace matches.
//
// One CSR instruction per cycle, in MEM:
//   rdata   = the old value (always read; no CSR here has a read side effect)
//   illegal = the CSR does not exist, or it is written and it is read-only
//   new     = RW: wdata   RS: old | wdata   RC: old & ~wdata
//   it is written at the clock edge when valid && write && !illegal
// An illegal access does not write; Core marks the exception and TrapUnit
// turns it into a trap (cause 2).
//
// CSRs (address: what is stored / what a read gives / what a write does)
//   mstatus  0x300  MIE (bit 3), MPIE (bit 7) / those | MPP = 11 (0x1800) / keeps bits 3, 7
//   misa     0x301  - / 0x40001101 (RV32 A I M) / ignored (WARL)
//   mie      0x304  - / 0 / ignored (FreeRTOS: MSIE MTIE MEIE)
//   mtvec    0x305  bits 31:2 / value, MODE = 0 / low 2 bits cleared
//   mstatush 0x310  - / 0 / ignored
//   mscratch 0x340  32 bits / value / value
//   mepc     0x341  bits 31:2 / value / low 2 bits cleared
//   mcause   0x342  32 bits / value / value
//   mtval    0x343  32 bits / value / value
//   mip      0x344  - / 0 / ignored (FreeRTOS: MSIP MTIP MEIP from irq)
//   mcycle   0xB00 / mcycleh   0xB80   64-bit cycle counter, low / high half, writable
//   minstret 0xB02 / minstreth 0xB82   64-bit retired-instruction counter, writable
//   cycle 0xC00, time 0xC01, instret 0xC02 (and 0xC80..0xC82 high halves): read-only
//            copies (time = cycle: no timer device)
//   hpm counters / events: 0xB03-0xB1F, 0xB83-0xB9F, 0x323-0x33F: read 0, write ignored
//            0xC03-0xC1F, 0xC83-0xC9F: read-only 0
//   mvendorid 0xF11, marchid 0xF12, mimpid 0xF13, mhartid 0xF14, mconfigptr 0xF15:
//            read-only 0
//   any other address: does not exist -> illegal
// Read-only = address bits 11:10 are 11 (0xC.., 0xF..).
//
// Counters (the same as isa_sim):
//   mcycle   +1 every cycle.
//   minstret +1 for each instruction that retires in WB (io.retire).
//   A write sets the counter at the clock edge (the write wins over the +1).
//   The instruction that wrote minstret does not count itself: it retires in
//   WB the next cycle, and that one +1 is skipped.
//   Reading minstret (or instret) in MEM: the instruction in WB retires in
//   this same cycle but is counted only at the clock edge, so the read value
//   is minstret + (io.retire ? 1 : 0); without this it is 1 lower than isa_sim.
//   (mcycle values are not compared by the lockstep: isa_sim has no cycles.)

// CSR numbers.
object CsrAddr {
  val MSTATUS    = 0x300
  val MISA       = 0x301
  val MIE        = 0x304
  val MTVEC      = 0x305
  val MSTATUSH   = 0x310
  val MSCRATCH   = 0x340
  val MEPC       = 0x341
  val MCAUSE     = 0x342
  val MTVAL      = 0x343
  val MIP        = 0x344
  val MCYCLE     = 0xb00
  val MINSTRET   = 0xb02
  val MCYCLEH    = 0xb80
  val MINSTRETH  = 0xb82
  val CYCLE      = 0xc00
  val TIME       = 0xc01
  val INSTRET    = 0xc02
  val CYCLEH     = 0xc80
  val TIMEH      = 0xc81
  val INSTRETH   = 0xc82
  val MVENDORID  = 0xf11
  val MARCHID    = 0xf12
  val MIMPID     = 0xf13
  val MHARTID    = 0xf14
  val MCONFIGPTR = 0xf15

  val MISA_VALUE  = 0x40001101L          // MXL = 1 (32-bit); A (bit 0), I (bit 8), M (bit 12)
  val MSTATUS_MPP = 0x1800               // MPP = 11 (M-mode), always read as set
}

class CsrFile extends Module {
  val io = IO(new Bundle {
    // ---- the CSR instruction in MEM
    val valid   = Input(Bool())          // a CSR instruction in MEM that will commit
    val addr    = Input(UInt(12.W))      // inst(31, 20)
    val cmd     = Input(CsrCmd())        // RW, RS, RC
    val write   = Input(Bool())          // csrWrite (RS / RC with rs1 field 0: read only)
    val wdata   = Input(UInt(Consts.XLEN.W))   // rs1 or zimm, brought by the ALU (aluOutM)
    val rdata   = Output(UInt(Consts.XLEN.W))  // old value -> rd
    val illegal = Output(Bool())         // no such CSR, or a write to a read-only one

    // ---- counters
    val retire  = Input(Bool())          // an instruction retires in WB this cycle

    // ---- trap and mret (from TrapUnit).
    val trap    = Input(Bool())          // take a trap at this clock edge
    val cause   = Input(UInt(Consts.XLEN.W))   // bit 31 = interrupt (FreeRTOS)
    val tval    = Input(UInt(Consts.XLEN.W))
    val epc     = Input(UInt(Consts.XLEN.W))   // pc of the instruction in MEM
    val mret    = Input(Bool())
    val mtvec   = Output(UInt(Consts.XLEN.W))  // trap target
    val mepc    = Output(UInt(Consts.XLEN.W))  // mret target

    // ---- FreeRTOS (rtos branch). Core ties irq to 0 and leaves irqPending unused.
    val irq        = Input(new IrqIO)    // msip, mtip, meip -> mip
    val irqPending = Output(Bool())      // mstatus.MIE && (mip & mie) != 0
  })

  // (1) The stored state, all 0 after reset. A trailing _ marks a register,
  // so mtvec_ (the register) and io.mtvec (the output port) are told apart.
  val mie_       = RegInit(false.B)      // mstatus bit 3: interrupts enabled
  val mpie_      = RegInit(false.B)      // mstatus bit 7: MIE before the last trap
  val mtvec_     = RegInit(0.U(Consts.XLEN.W))   // bits 1:0 stay 0 (direct mode)
  val mepc_      = RegInit(0.U(Consts.XLEN.W))   // bits 1:0 stay 0
  val mcause_    = RegInit(0.U(Consts.XLEN.W))
  val mtval_     = RegInit(0.U(Consts.XLEN.W))
  val mscratch_  = RegInit(0.U(Consts.XLEN.W))
  val mcycle_    = RegInit(0.U(64.W))    // mcycle (low half), mcycleh (high half)
  val minstret_  = RegInit(0.U(64.W))    // minstret (low half), minstreth (high half)
  val skipRetire = RegInit(false.B)      // the instruction now in WB wrote minstret:
                                         // do not count it (set when minstret / minstreth
                                         // is written, used and cleared the next cycle)

  // (2) The read value `old` and whether the CSR exists.
  def is(a: Int): Bool = io.addr === a.U                              // one address
  def in(lo: Int, hi: Int): Bool = io.addr >= lo.U && io.addr <= hi.U  // a range

  // minstret as seen in MEM: the instruction in WB retires this cycle but is
  // counted only at the clock edge, so add it here (see the header).
  val minstretSeen = minstret_ + io.retire

  val old    = WireDefault(0.U(Consts.XLEN.W))
  val exists = WireDefault(true.B)

  when(is(CsrAddr.MSTATUS)) {
    // bit 7 = MPIE, bit 3 = MIE; MPP (bits 12:11) always reads as 11
    old := Cat(mpie_, 0.U(3.W), mie_, 0.U(3.W)) | CsrAddr.MSTATUS_MPP.U
  }.elsewhen(is(CsrAddr.MISA)) {
    old := CsrAddr.MISA_VALUE.U
  }.elsewhen(is(CsrAddr.MTVEC)) {
    old := mtvec_
  }.elsewhen(is(CsrAddr.MSCRATCH)) {
    old := mscratch_
  }.elsewhen(is(CsrAddr.MEPC)) {
    old := mepc_
  }.elsewhen(is(CsrAddr.MCAUSE)) {
    old := mcause_
  }.elsewhen(is(CsrAddr.MTVAL)) {
    old := mtval_
  }.elsewhen(is(CsrAddr.MIE) || is(CsrAddr.MIP) || is(CsrAddr.MSTATUSH) ||
             in(CsrAddr.MVENDORID, CsrAddr.MCONFIGPTR)) {
    old := 0.U                           // exist, read 0 (FreeRTOS: mie, mip)
  }.elsewhen(is(CsrAddr.MCYCLE) || is(CsrAddr.CYCLE) || is(CsrAddr.TIME)) {
    old := mcycle_(31, 0)                // time = cycle: no timer device
  }.elsewhen(is(CsrAddr.MCYCLEH) || is(CsrAddr.CYCLEH) || is(CsrAddr.TIMEH)) {
    old := mcycle_(63, 32)
  }.elsewhen(is(CsrAddr.MINSTRET) || is(CsrAddr.INSTRET)) {
    old := minstretSeen(31, 0)
  }.elsewhen(is(CsrAddr.MINSTRETH) || is(CsrAddr.INSTRETH)) {
    old := minstretSeen(63, 32)
  }.elsewhen(in(0xb03, 0xb1f) || in(0xb83, 0xb9f) ||    // mhpmcounter3-31(h)
             in(0xc03, 0xc1f) || in(0xc83, 0xc9f) ||    // hpmcounter3-31(h)
             in(0x323, 0x33f)) {                         // mhpmevent3-31
    old := 0.U                           // exist, count nothing
  }.otherwise {
    exists := false.B
  }

  // (3) Illegal: the CSR does not exist, or the instruction writes it and it
  // is read-only (address bits 11:10 = 11). Reading a read-only CSR is fine.
  val illegal = !exists || (io.write && io.addr(11, 10) === 3.U)

  // (4) The new value.
  val newValue = MuxLookup(io.cmd.asUInt, io.wdata, Seq(
    CsrCmd.RS.asUInt -> (old | io.wdata),          // set bits
    CsrCmd.RC.asUInt -> (old & ~io.wdata)))        // clear bits

  // (6) Counters. Written before (5): when the CSR instruction writes a
  // counter in the same cycle, its write comes later and wins ("last := wins").
  mcycle_ := mcycle_ + 1.U
  when(io.retire && !skipRetire) {
    minstret_ := minstret_ + 1.U
  }
  skipRetire := false.B                  // used for one cycle only

  // (5) The write, at the clock edge.
  when(io.valid && io.write && !illegal) {
    when(is(CsrAddr.MSTATUS))   { mie_ := newValue(3); mpie_ := newValue(7) }
    when(is(CsrAddr.MTVEC))     { mtvec_ := Cat(newValue(31, 2), 0.U(2.W)) }
    when(is(CsrAddr.MEPC))      { mepc_ := Cat(newValue(31, 2), 0.U(2.W)) }
    when(is(CsrAddr.MSCRATCH))  { mscratch_ := newValue }
    when(is(CsrAddr.MCAUSE))    { mcause_ := newValue }
    when(is(CsrAddr.MTVAL))     { mtval_ := newValue }
    when(is(CsrAddr.MCYCLE))    { mcycle_ := Cat(mcycle_(63, 32), newValue) }
    when(is(CsrAddr.MCYCLEH))   { mcycle_ := Cat(newValue, mcycle_(31, 0)) }
    when(is(CsrAddr.MINSTRET))  { minstret_ := Cat(minstret_(63, 32), newValue); skipRetire := true.B }
    when(is(CsrAddr.MINSTRETH)) { minstret_ := Cat(newValue, minstret_(31, 0)); skipRetire := true.B }
    // misa, mie, mip, mstatush, hpm counters and events: writes are ignored (WARL)
  }

  // (7) Trap and mret, at the clock edge. Written after (5): Core never
  // sends a CSR write in the same cycle as a trap (the trapping instruction
  // does not commit), but if both came, the trap wins ("last := wins").
  // Same as isa_sim: hart.cpp trap() and the MRET case.
  when(io.trap) {
    mepc_   := Cat(io.epc(31, 2), 0.U(2.W))   // same mask as a write to mepc
    mcause_ := io.cause                       // bit 31 = interrupt (FreeRTOS)
    mtval_  := io.tval
    mpie_   := mie_                           // remember MIE
    mie_    := false.B                        // interrupts off in the handler
  }.elsewhen(io.mret) {
    mie_  := mpie_
    mpie_ := true.B
  }

  io.rdata      := old
  io.illegal    := illegal
  io.mtvec      := mtvec_                // trap target
  io.mepc       := mepc_                 // mret target
  io.irqPending := false.B               // FreeRTOS
}
