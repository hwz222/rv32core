package rvsc

import chisel3._
import chisel3.util._

// Every port between the core, the memories and the testbench.
// Directions are seen from the core (the side that sends requests).
//
// Chisel helpers used here:
//   Decoupled(T)  valid + ready + bits. A request is taken in the cycle
//                 where valid and ready are both 1.
//   Valid(T)      valid + bits, no ready. The receiver must take it now.
//   Flipped(...)  turn the direction around (resp goes memory -> core).
//
// Sections:
//   1. Memory ports      ImemPort, DmemPort
//   2. Interrupt lines   IrqIO
//   3. Retire record     RetireIO
//   4. Events            Fwd, PerfEvents
//   5. Top levels        CoreIO, LoadReq, SimTopIO

// ===========================================================================
// 1. Memory ports
//
// The core checks every address before it sends a request:
//   misaligned        -> no request, trap (cause 0, 4, 6)
//   outside IMEM/DMEM -> no request, trap (cause 1, 5, 7), see MemMap
// So the memories only see legal, aligned addresses and return no error,
// and a store never writes before its trap is known (traps are in MEM).
//
// Timing: a request taken in cycle t is answered in cycle t+1 (sync read).
// Every taken request gets one answer, stores too. Today's memories are always
// ready; a cache may later hold ready low or answer later, and the core
// simply waits, so these ports do not change.
//
// Store data: the core moves the data to the right bytes and sets wstrb
// (bit i = byte i). Example: sb to address ...02 -> wstrb = 0100, data in
// wdata(23, 16).
// Load data: rdata is always the whole word. The core picks the bytes with
// addr(1, 0) and the load type (lb/lh/lw/lbu/lhu), then extends the sign.
// AMO: the core uses the data port twice, one read and then one write.
// ===========================================================================

class ImemReq extends Bundle {
  val addr = UInt(32.W)                  // byte address of the instruction
}

class ImemResp extends Bundle {
  val inst = UInt(32.W)
}

class ImemPort extends Bundle {
  val req  = Decoupled(new ImemReq)
  val resp = Flipped(Valid(new ImemResp))
}

class DmemReq extends Bundle {
  val addr  = UInt(32.W)                 // byte address, already checked
  val wen   = Bool()                     // 1 = store, 0 = load
  val wstrb = UInt(4.W)                  // byte enables for a store
  val wdata = UInt(32.W)                 // store data, already in place
}

class DmemResp extends Bundle {
  val rdata = UInt(32.W)                 // whole word; no meaning for a store
}

class DmemPort extends Bundle {
  val req  = Decoupled(new DmemReq)
  val resp = Flipped(Valid(new DmemResp))
}

// ===========================================================================
// 2. Interrupt lines
//
// Each line shows up in the mip bit with the same number. The core checks
// them in MEM: an interrupt is taken on the instruction that has just
// reached MEM (no side effect yet) when mstatus.MIE = 1 and
// (mip & mie) != 0. That instruction does not run; mepc = its pc.
// The current testbench ties all lines to 0.
// ===========================================================================

class IrqIO extends Bundle {
  val msip = Bool()                      // software: mip bit 3,  mcause code 3
  val mtip = Bool()                      // timer:    mip bit 7,  mcause code 7
  val meip = Bool()                      // external: mip bit 11, mcause code 11
}

// ===========================================================================
// 3. Retire record: one per retired instruction or taken trap.
//
// The testbench turns it into the same trace line as isa_sim:
//   <n> <pc> <insn> [x<rd>=<val>] [mem<bits>[<addr>]=<val>]
//   <n> <pc> trap cause=<mcause> tval=<tval>
// <n> is counted by the testbench and does not grow on a trap line.
// There is no load field: isa_sim does not print load addresses, and the
// loaded value is checked through x<rd>.
//
// Trap: when trap = 1 the register and memory fields are ignored.
//   full mcause = (interrupt << 31) | cause
//   e.g. timer interrupt = 0x80000007, illegal instruction = 2
//   trapTarget = mtvec base (or base + 4 * cause in vectored mode);
//   0 means no handler, and the testbench stops with exit code 3 like isa_sim.
// ===========================================================================

class RetireIO extends Bundle {
  // Instruction
  val valid      = Bool()                // this cycle has a record
  val pc         = UInt(32.W)
  val insn       = UInt(32.W)

  // Register write (printed only when rdAddr != 0)
  val rdWe       = Bool()
  val rdAddr     = UInt(5.W)
  val rdData     = UInt(32.W)

  // Memory write: store, successful SC, AMO (also used to find tohost)
  val memWe      = Bool()
  val memAddr    = UInt(32.W)
  val memSize    = UInt(2.W)             // 0 = byte, 1 = half, 2 = word
  val memWdata   = UInt(32.W)            // value in the low bytes

  // Trap
  val trap       = Bool()
  val interrupt  = Bool()                // 1 = interrupt, 0 = exception
  val cause      = UInt(4.W)             // code 0..15
  val tval       = UInt(32.W)            // 0 for interrupts
  val trapTarget = UInt(32.W)            // 0 = no handler, stop
}

// ===========================================================================
// 4. Events, sampled every cycle.
//
// The testbench adds them up for the coverage report and the
// cycle breakdown (docs/PERFORMANCE.md). They do not change what the core computes.
//
// Where operands are read (the register file gives the old value while WB
// writes it, so ID forwards from MEM/WB as well):
//   ID  every rs (kept in ID/EX), branch compare  from EX/MEM or MEM/WB
//   EX  ALU operands, store data, JALR base       from EX/MEM or MEM/WB
// Stalls that wait for an operand:
//   stallLoadUse    EX needs a load or CSR result that is still in MEM
//                   (CSR results come in MEM like load data)
//   stallBranchUse  ID (branch) needs an ALU result still in EX, or a
//                   load/CSR result still in EX or MEM
// CSR instructions do not flush (CSR access and the interrupt check are
// both in MEM). JAL jumps in ID, JALR jumps in EX.
// ===========================================================================

object Fwd {
  // Where an operand comes from. Also the select of the ID and EX forward muxes.
  val None  = 0.U(2.W)                   // register file (in EX: value kept in ID/EX)
  val ExMem = 1.U(2.W)                   // EX/MEM pipeline register (ALUOutM)
  val MemWb = 2.U(2.W)                   // MEM/WB pipeline register (ResultW)
}

class PerfEvents extends Bundle {
  // Pipeline state: 0 = IF, 1 = ID, 2 = EX, 3 = MEM, 4 = WB
  val stageValid     = Vec(5, Bool())    // stage holds a real instruction

  // Stalls: why the front of the pipeline is held this cycle
  val stallLoadUse   = Bool()            // EX waits for a load/CSR result
  val stallBranchUse = Bool()            // ID waits for a branch operand
  val stallMulDiv    = Bool()            // multi-cycle MUL/DIV is busy
  val stallImem      = Bool()            // waiting for instruction memory
  val stallDmem      = Bool()            // waiting for data memory (also AMO)

  // Flushes: why younger instructions are thrown away this cycle
  val flushBranch    = Bool()            // taken branch (decided in ID)
  val flushJump      = Bool()            // JAL or JALR
  val flushTrap      = Bool()            // exception, interrupt or MRET

  // Forwarding: where each operand came from (values from object Fwd)
  val idFwdRs1       = UInt(2.W)         // ID: rs1 (to ID/EX and branch compare)
  val idFwdRs2       = UInt(2.W)         // ID: rs2 (to ID/EX and branch compare)
  val exFwdRs1       = UInt(2.W)         // EX: rs1 (ALU, JALR base)
  val exFwdRs2       = UInt(2.W)         // EX: rs2 (ALU, store data)
}

// ===========================================================================
// 5. Top levels
//
// CoreIO: ports of every core (see CoreBase). Clock and reset come from
// Module, not from this bundle.
//
// SimTopIO: the only ports the Verilator testbench sees. In Verilog, nested
// names are joined with "_", e.g. io.load.valid -> io_load_valid.
// Testbench order:
//   reset = 1, hostEn = 1   load the ELF, one word per cycle
//   reset = 0, hostEn = 0   the core runs
//   store to tohost seen    hostEn = 1, read the result through dump
// ===========================================================================

class CoreIO extends Bundle {
  val bootAddr = Input(UInt(32.W))               // first PC after reset (ELF entry)
  val irq      = Input(new IrqIO)
  val imem     = new ImemPort
  val dmem     = new DmemPort
  val retire   = Output(new RetireIO)
  val events   = Output(new PerfEvents)
}

class LoadReq extends Bundle {
  val addr = UInt(32.W)                          // word aligned; MemMap picks IMEM/DMEM
  val data = UInt(32.W)
}

class SimTopIO extends Bundle {
  val bootAddr = Input(UInt(32.W))               // ELF entry
  val irq      = Input(new IrqIO)                // tied to 0 by the testbench
  val hostEn   = Input(Bool())                   // 1 = testbench owns the memories
  val load     = Flipped(Valid(new LoadReq))     // used only when hostEn = 1
  val dumpAddr = Input(UInt(32.W))               // used when hostEn = 1, no load
  val dumpData = Output(UInt(32.W))              // DMEM word, one cycle after dumpAddr
  val retire   = Output(new RetireIO)
  val events   = Output(new PerfEvents)
}
