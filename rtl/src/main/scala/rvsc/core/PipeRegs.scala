package rvsc

import chisel3._
import chisel3.util._

// The four pipeline registers: their Bundles and the PipeReg module.
// Fields: the pipeline register table in rtl/DESIGN.md, plus the fields only the
// retire record needs, plus the exception
// fields: ID/EX and EX/MEM carry an exception to MEM, MEM/WB carries
// the trap taken in MEM to the retire record.
//
// Each Bundle becomes a register through the PipeReg module at the end of
// this file (one PipeReg per pipeline register in Core).
// valid = 0 means a bubble: it writes nothing, jumps nowhere, retires nothing.
//
// rs1, rs2, rd and funct3 are kept as their own fields (easier to read in
// Core and in a waveform), although they are also bits of inst.
// Field names have no stage suffix: Core gives each field the suffix of the
// stage that reads it (idEx.pc is pcE in EX, exMem.aluOut is aluOutM in MEM).

// IF -> ID. No inst here: IMEM reads synchronously, so its answer
// (io.imem.resp.bits.inst) arrives in ID by itself and IS the IF/ID
// instruction. On a stall IF/ID holds, but the answer would change (pc is
// the IF address, one ahead), so Core sends ifId.pc to IMEM during a stall
// and IMEM answers with the same instruction again. On a flush valid = 0
// and ID ignores the answer.
class IfId extends Bundle {
  val valid = Bool()                    // a fetch request was taken (fire) and not flushed
  val pc    = UInt(Consts.XLEN.W)       // the PC sent with that request
}

// ID -> EX
class IdEx extends Bundle {
  val valid  = Bool()
  val pc     = UInt(Consts.XLEN.W)      // EX: auipc, pc + 4 for jal / jalr
  val inst   = UInt(Consts.ILEN.W)      // carried for the retire record
  val ctrl   = new CtrlSignals          // all control signals from the decoder
  val rs1Val = UInt(Consts.XLEN.W)      // rs1 value after ID forwarding (rs1ValD -> rs1ValE)
  val rs2Val = UInt(Consts.XLEN.W)      // rs2 value after ID forwarding (rs2ValD -> rs2ValE)
  val imm    = UInt(Consts.XLEN.W)      // ImmGen output
  val rs1    = UInt(Consts.REG_ADDR_W.W) // for EX forwarding (hazard unit)
  val rs2    = UInt(Consts.REG_ADDR_W.W)
  val rd     = UInt(Consts.REG_ADDR_W.W)
  val funct3 = UInt(3.W)                // for StoreAlign / LoadAlign later
  // an exception found in ID (Exceptions.scala); only meaningful when valid = 1
  val excValid = Bool()
  val excCause = UInt(4.W)
  val excTval  = UInt(Consts.XLEN.W)
}

// EX -> MEM
class ExMem extends Bundle {
  val valid     = Bool()
  val pc        = UInt(Consts.XLEN.W)
  val inst      = UInt(Consts.ILEN.W)
  val aluOut    = UInt(Consts.XLEN.W)   // aluOutM: ALU result or pc + 4; the address for load / store
  val storeData = UInt(Consts.XLEN.W)   // rs2FwdE (rs2 after EX forwarding), not yet copied to byte lanes
  val rd        = UInt(Consts.REG_ADDR_W.W)
  val funct3    = UInt(3.W)             // size for StoreAlign; size and sign for LoadAlign
  // the control signals MEM and WB still need
  val regWrite  = Bool()
  val wbSel     = WbSel()
  val memWrite  = Bool()                // store: dmem request with wen = 1
  val memRead   = Bool()                // load:  dmem request with wen = 0
  val aluOutNotResult = Bool()          // aluOut is not the rd value (load); for the hazard unit
  // Zicsr: the CSR instruction is done in MEM by CsrFile. The CSR
  // address is inst(31, 20); aluOut is the source value (rs1 or zimm).
  val csr       = Bool()
  val csrCmd    = CsrCmd()
  val csrWrite  = Bool()
  val mret      = Bool()                // mret jumps to mepc in MEM (TrapUnit)
  // A extension, done in MEM
  val lr        = Bool()                // sets the reservation
  val sc        = Bool()                // writes only if the reservation holds
  val amo       = Bool()                // 2 MEM cycles: read, then write op(old, rs2)
  val amoOp     = UInt(5.W)             // funct5
  // an exception from ID, or a bad address found in EX
  val excValid  = Bool()
  val excCause  = UInt(4.W)
  val excTval   = UInt(Consts.XLEN.W)
}

// MEM -> WB. The load data is not stored here: it comes from dmem.resp in WB.
class MemWb extends Bundle {
  val valid     = Bool()
  val pc        = UInt(Consts.XLEN.W)   // retire.pc
  val inst      = UInt(Consts.ILEN.W)   // retire.insn
  val aluOut    = UInt(Consts.XLEN.W)   // aluOutW: value to write back (not a load);
                                        // for a load it is the address:
                                        // aluOutW(1, 0) = addrLow.
                                        // CSR, AMO, SC: the rd value
                                        // made in MEM (old CSR, old word, 0 / 1)
  val memAddr   = UInt(Consts.XLEN.W)   // retire.memAddr only (aluOut is not the
                                        // address for an AMO or SC)
  val storeData = UInt(Consts.XLEN.W)   // retire.memWdata only (AMO: the new word)
  val rd        = UInt(Consts.REG_ADDR_W.W)
  val funct3    = UInt(3.W)             // LoadAlign; funct3(1, 0) = retire.memSize
  val regWrite  = Bool()
  val wbSel     = WbSel()
  val memWrite  = Bool()                // retire.memWe
  // the trap taken in MEM, for the retire record (RetireIO).
  // When trap = 1, regWrite and memWrite are 0: the instruction did nothing.
  val trap       = Bool()
  val interrupt  = Bool()               // 1 = interrupt (mcause bit 31)
  val cause      = UInt(4.W)            // mcause code
  val tval       = UInt(Consts.XLEN.W)
  val trapTarget = UInt(Consts.XLEN.W)  // mtvec; 0 = no handler, the testbench stops
}

// One pipeline register. It can hold any of the four Bundles.
//
// Scala syntax used here:
//   [T <: Data]  T is a type chosen by the user; it must be a Chisel data type
//                (Bool, UInt, a Bundle, ...). new PipeReg(new IdEx) makes T = IdEx.
//   (gen: T)     a sample of that type: it tells Chisel the fields and widths.
//                new IdEx is not hardware, only the shape (like UInt(32.W)).
//   Input(gen) / Output(gen)
//                a whole Bundle as one port; use fields as io.in.pc, io.out.ctrl.
//
// Core makes one per stage:
//   val ifIdReg  = Module(new PipeReg(new IfId))    // stall = stallD, flush = flushD
//   val idExReg  = Module(new PipeReg(new IdEx))    // stall = stallE, flush = flushE
//   val exMemReg = Module(new PipeReg(new ExMem))   // stall = stallM, flush = flushM
//   val memWbReg = Module(new PipeReg(new MemWb))   // stall = flush = false.B
// then drives idExReg.io.in (built in ID) and reads idExReg.io.out (used in EX).
//
// Each cycle, in this order:
//   flush -> becomes a bubble: every field 0, so valid = 0 and every control
//            signal is 0. Flush wins over stall.
//   stall -> keeps its value.
//   else  -> takes io.in.
// After reset it is a bubble too.
// io.out is the value stored at the end of the last cycle: what io.in is now
// shows on io.out in the next cycle.
//
//   cycle   in   stall  flush   out
//   1       A    0      0       bubble
//   2       B    1      0       A
//   3       B    0      0       A
//   4       C    0      1       B
//   5       -                   bubble
class PipeReg[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val stall = Input(Bool())
    val flush = Input(Bool())
    val in    = Input(gen)                // the next value, made by the stage before
    val out   = Output(gen)               // the value the stage after uses
  })

  // 0 read as the Bundle: every field 0 (enum fields too, e.g. AluOp.ADD).
  val bubble = 0.U.asTypeOf(gen)
  // The register, a bubble after reset.
  val r      = RegInit(bubble)

  when(io.flush) {
    r := bubble
  }.elsewhen(!io.stall) {
    r := io.in                            // a whole Bundle at once: field by field
  }
  // stall: r is not assigned this cycle, so it keeps its value
  // (a register keeps its value; a Wire would need a value).

  io.out := r
}
