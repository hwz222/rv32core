package rvsc

import chisel3._
import chisel3.util._

// finding exceptions. An exception is only RECORDED in ID and EX (in the
// excValid / excCause / excTval fields of ID/EX and EX/MEM); it takes effect
// in MEM (TrapUnit), so the trap is precise. All rules follow isa_sim
// (hart.cpp step(), memory.h fetch / load / store).
//
// An instruction with an exception must do nothing else:
//   - found in ID: Core sends its control signals to ID/EX as 0 (like a
//     bubble, but valid = 1 so it reaches MEM and traps), and it does not
//     jump (Core ANDs takenD and jalD for the hazard unit with !exc.valid).
//   - found in EX: a JALR does not jump (Core: jalrE needs !exc.valid);
//     a bad load / store is stopped in MEM, where its DMEM request is sent.
// Only the jump is blocked, never a stall: during a stall the operands are
// not ready yet, so exc.valid may be wrong in that cycle. A jump only happens
// when there is no stall, and then exc.valid is right.

// One exception: valid, mcause code, mtval.
class ExcInfo extends Bundle {
  val valid = Bool()
  val cause = UInt(4.W)
  val tval  = UInt(Consts.XLEN.W)
}

// ID: the exceptions known after decode and the branch compare.
// Priority (first match wins), like isa_sim:
//   1 fetch access fault  pc outside IMEM            cause 1, tval = pc
//     (the instruction word is not real: nothing else counts)
//   2 illegal instruction illegal                    cause 2, tval = inst
//   3 ecall               ecall                      cause 11, tval = 0
//   4 ebreak              ebreak                     cause 3, tval = pc
//   5 jump misaligned     (branchTaken || jal) and target(1) = 1
//                                                    cause 0, tval = target
// (target(0) is always 0: the B and J immediates are even and pc is aligned,
// so only bit 1 can make a target misaligned.)
class IdExcCheck extends Module {
  val io = IO(new Bundle {
    val pc          = Input(UInt(Consts.XLEN.W))   // pcD
    val inst        = Input(UInt(Consts.ILEN.W))   // instD
    val illegal     = Input(Bool())                // ctrlD.illegalInst
    val ecall       = Input(Bool())                // ctrlD.ecall
    val ebreak      = Input(Bool())                // ctrlD.ebreak
    val branchTaken = Input(Bool())                // ctrlD.branch && takenD
    val jal         = Input(Bool())                // ctrlD.jal
    val target      = Input(UInt(Consts.XLEN.W))   // pcBranchD
    val exc         = Output(new ExcInfo)
  })

  val fetchFault = !MemMap.inImem(io.pc)
  val misaligned = io.target(1)          // bit 0 is always 0 for B and J targets
  val jumping    = io.branchTaken || io.jal

  io.exc.valid   := fetchFault || io.illegal || io.ecall || io.ebreak || (jumping && misaligned)
  io.exc.cause   := 0.U
  io.exc.tval    := 0.U
  when(fetchFault) {
    io.exc.cause := Cause.INSN_ACCESS_FAULT.U
    io.exc.tval  := io.pc
  }.elsewhen(io.illegal) {
    io.exc.cause := Cause.ILLEGAL_INSN.U
    io.exc.tval  := io.inst
  }.elsewhen(io.ecall) {
    io.exc.cause := Cause.ECALL_FROM_M.U
    io.exc.tval  := 0.U
  }.elsewhen(io.ebreak) {
    io.exc.cause := Cause.BREAKPOINT.U
    io.exc.tval  := io.pc
  }.elsewhen(jumping && misaligned) {
    io.exc.cause := Cause.INSN_MISALIGNED.U
    io.exc.tval  := io.target
  }
}

// EX: the exceptions known after the ALU, added to what ID found.
// Priority:
//   1 an exception from ID (in.valid)               kept as it is (older cause)
//   2 load  misaligned   memRead  and addr not aligned to the size   cause 4
//   3 load  access fault memRead  and addr outside DMEM              cause 5
//   4 store misaligned   memWrite, amo or sc and addr not aligned    cause 6
//   5 store access fault memWrite or amo and addr outside DMEM       cause 7
//   6 jalr misaligned    jalr and jalrTarget(1) = 1                  cause 0
//   tval = addr for 2-5, jalrTarget for 6.
// Size from funct3(1, 0): 00 byte (always aligned), 01 half (addr(0) = 0),
// 10 word (addr(1, 0) = 0). Misaligned is checked before the range, like
// isa_sim.
// A extension, like isa_sim: lr.w is a load (memRead, word). An AMO
// counts as a store for both checks, although it also reads. sc.w is only
// checked for alignment here: its access fault happens only when the
// reservation holds, and the reservation is in MEM, so Core checks it there.
class ExExcCheck extends Module {
  val io = IO(new Bundle {
    val in          = Input(new ExcInfo)           // idEx.exc*
    val memRead     = Input(Bool())                // memReadE
    val memWrite    = Input(Bool())                // memWriteE
    val amo         = Input(Bool())                // amoE
    val sc          = Input(Bool())                // scE
    val funct3      = Input(UInt(3.W))             // funct3E
    val addr        = Input(UInt(Consts.XLEN.W))   // aluRawE = rs1 + imm
    val jalr        = Input(Bool())                // idEx.ctrl.jalr
    val jalrTarget  = Input(UInt(Consts.XLEN.W))   // pcJalrE (bit 0 already cleared)
    val out         = Output(new ExcInfo)
  })

  val size       = io.funct3(1, 0)        // 00 byte, 01 half, 10 word
  val misaligned = (size === 1.U && io.addr(0)) || (size === 2.U && io.addr(1, 0) =/= 0.U)
  val outside    = !MemMap.inDmem(io.addr)

  // Default: what ID found (or no exception). A new one is only added when
  // ID found none.
  io.out := io.in
  when(!io.in.valid) {
    when(io.memRead && misaligned) {
      io.out.valid := true.B
      io.out.cause := Cause.LOAD_MISALIGNED.U
      io.out.tval  := io.addr
    }.elsewhen(io.memRead && outside) {
      io.out.valid := true.B
      io.out.cause := Cause.LOAD_ACCESS_FAULT.U
      io.out.tval  := io.addr
    }.elsewhen((io.memWrite || io.amo || io.sc) && misaligned) {
      io.out.valid := true.B
      io.out.cause := Cause.STORE_MISALIGNED.U
      io.out.tval  := io.addr
    }.elsewhen((io.memWrite || io.amo) && outside) {
      io.out.valid := true.B
      io.out.cause := Cause.STORE_ACCESS_FAULT.U
      io.out.tval  := io.addr
    }.elsewhen(io.jalr && io.jalrTarget(1)) {
      io.out.valid := true.B
      io.out.cause := Cause.INSN_MISALIGNED.U
      io.out.tval  := io.jalrTarget
    }
  }
}
