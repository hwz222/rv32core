package rvsc

import chisel3._
import chisel3.util._

// Decoder (ID stage, Control unit in the diagram).
// Turns one instruction into the control signals of the control-signal
// instruction table (37 RV32I instructions, plus the 8 of M,
// the 6 of Zicsr, ecall / ebreak / mret / wfi, and the 11 of A).

// All control signals of one instruction. Made in ID and kept in ID/EX;
// the later pipeline registers keep only the fields their stages need.
class CtrlSignals extends Bundle {
  // write back
  val regWrite    = Bool()               // RegWriteD: writes rd
  val wbSel       = WbSel()              // WbSelD: Result or Load
  // memory
  val memWrite    = Bool()               // MemWriteD: store
  val memRead     = Bool()               // MemReadD: load
  // jumps and branches
  val link        = Bool()               // LinkD: EX result is pc+4 (jal, jalr)
  val jalr        = Bool()               // JalrD: jumps in EX (jalr only)
  val branch      = Bool()               // BranchD: conditional branch, decided in ID
  val jal         = Bool()               // JalD: jumps in ID (jal only)
  // ALU and immediate
  val aluOp       = AluOp()              // AluOpD
  val srcA        = SrcASel()            // SrcAD: Rs1, Pc or Zero
  val srcB        = SrcBSel()            // SrcBD: Rs2 or Imm
  val immSel      = ImmSel()             // format for ImmGen
  // M extension
  val mulDiv      = Bool()               // MulDivD: the EX result comes from Multiplier / Divider
  val mulDivOp    = MulDivOp()           // MulDivOpD: which of the 8 operations (= funct3)
  // Zicsr. The CSR is read and written in MEM by CsrFile.
  val csr         = Bool()               // CsrD: a CSR instruction
  val csrCmd      = CsrCmd()             // CsrCmdD: RW, RS or RC (= funct3(1, 0))
  val csrWrite    = Bool()               // CsrWriteD: really writes the CSR. RW always;
                                         //   RS / RC only when the rs1 field is not 0
                                         //   (x0 or zimm = 0: read only, so a read-only
                                         //   CSR such as cycle can be read)
  // for the hazard unit
  // aluOut is NOT the value written to rd. The real value is ready only in
  // WB (resultW): load: data read from memory; CSR: the old CSR value;
  // AMO: the old memory value. Hazard unit: no forward from aluOutM,
  // stall when the next instruction needs it. 0 = normal, so a bubble is safe.
  val aluOutNotResult = Bool()
                                         //   RV32I: loads. + csr*. + amo, lr, sc
  val readsRs1    = Bool()               // really reads rs1 (not lui, auipc, jal, fence)
  val readsRs2    = Bool()               // really reads rs2 (R-type, store, branch only)
  // for traps
  val ecall       = Bool()               // EcallD: ecall (an exception, cause 11)
  val ebreak      = Bool()               // EbreakD: ebreak (an exception, cause 3)
  val mret        = Bool()               // MretD: return from a trap, in MEM: pc <- mepc
  val illegalInst = Bool()               // not a legal RV32I instruction (same rules as isa_sim)
  // A extension. Address = rs1 + 0 (srcB Zero), word only.
  val lr          = Bool()               // LrD: lr.w, a load that sets the reservation (MEM)
  val sc          = Bool()               // ScD: sc.w, writes only if the reservation holds (MEM)
  val amo         = Bool()               // AmoD: one of the 9 AMOs, 2 cycles in MEM
  val amoOp       = UInt(5.W)            // AmoOpD: which AMO (= funct5)
}

class Decoder extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(Consts.ILEN.W))   // IF/ID inst
    val ctrl = Output(new CtrlSignals)
  })

  val opcode = io.inst(6, 0)
  val funct3 = io.inst(14, 12)
  val funct7 = io.inst(31, 25)
  val alt    = funct7 === Funct7.ALT     // bit 30: sub / sra / srai

  // ALU operation from funct3. useAlt picks SUB instead of ADD and SRA
  // instead of SRL. OP passes alt; OP_IMM passes alt only for srli/srai,
  // because in addi etc. bit 30 is part of the immediate.
  def aluOpOf(useAlt: Bool): AluOp.Type = {
    val op = WireDefault(AluOp.ADD)
    switch(funct3) {
      is(Funct3.ADD_SUB) { op := Mux(useAlt, AluOp.SUB, AluOp.ADD) }
      is(Funct3.SLL)     { op := AluOp.SLL }
      is(Funct3.SLT)     { op := AluOp.SLT }
      is(Funct3.SLTU)    { op := AluOp.SLTU }
      is(Funct3.XOR)     { op := AluOp.XOR }
      is(Funct3.SRL_SRA) { op := Mux(useAlt, AluOp.SRA, AluOp.SRL) }
      is(Funct3.OR)      { op := AluOp.OR }
      is(Funct3.AND)     { op := AluOp.AND }
    }
    op
  }

  // Default: an unknown instruction. Every field is 0 (so it writes nothing,
  // like a nop) and illegalInst = 1. Each legal case below sets illegalInst
  // to false.B, so a missing case stays illegal. Fields not set below keep
  // the default; enum fields are 0 too: AluOp.ADD, SrcASel.Rs1, SrcBSel.Rs2,
  // ImmSel.I, WbSel.Result.
  io.ctrl             := 0.U.asTypeOf(new CtrlSignals)
  io.ctrl.illegalInst := true.B
  val c = io.ctrl

  switch(opcode) {
    is(Opcode.OP) {                      // add sub sll slt sltu xor srl sra or and
      // funct7 NORMAL: all 8 funct3; ALT: only sub and sra
      when(funct7 === Funct7.NORMAL ||
           (alt && (funct3 === Funct3.ADD_SUB || funct3 === Funct3.SRL_SRA))) {
        c.illegalInst := false.B
        c.regWrite    := true.B
        c.aluOp       := aluOpOf(alt)
        c.readsRs1    := true.B
        c.readsRs2    := true.B
      }
      // funct7 MULDIV: mul mulh mulhsu mulhu div divu rem remu. Like
      // R-type, but the result comes from Multiplier / Divider in EX. The
      // ALU input selects stay Rs1 / Rs2 (their values feed both units).
      when(funct7 === Funct7.MULDIV) {
        c.illegalInst := false.B
        c.regWrite    := true.B
        c.mulDiv      := true.B
        c.mulDivOp    := MulDivOp(funct3) // funct3 bits -> enum value
        c.readsRs1    := true.B
        c.readsRs2    := true.B
      }
    }
    is(Opcode.OP_IMM) {                  // addi slti sltiu xori ori andi slli srli srai
      val shiftOk = Mux(funct3 === Funct3.SLL, funct7 === Funct7.NORMAL,
                    Mux(funct3 === Funct3.SRL_SRA, funct7 === Funct7.NORMAL || alt,
                        true.B))         // other funct3: bits 31:25 are immediate bits
      when(shiftOk) {
        c.illegalInst := false.B
        c.regWrite    := true.B
        c.aluOp       := aluOpOf(alt && funct3 === Funct3.SRL_SRA)
        c.srcB        := SrcBSel.Imm
        c.readsRs1    := true.B
      }
    }
    is(Opcode.LUI) {                     // rd = imm (0 + imm)
      c.illegalInst := false.B
      c.regWrite    := true.B
      c.srcA        := SrcASel.Zero
      c.srcB        := SrcBSel.Imm
      c.immSel      := ImmSel.U
    }
    is(Opcode.AUIPC) {                   // rd = pc + imm
      c.illegalInst := false.B
      c.regWrite    := true.B
      c.srcA        := SrcASel.Pc
      c.srcB        := SrcBSel.Imm
      c.immSel      := ImmSel.U
    }
    is(Opcode.LOAD) {                    // address = rs1 + imm
      switch(funct3) {
        is(Funct3.LB, Funct3.LH, Funct3.LW, Funct3.LBU, Funct3.LHU) {
          c.illegalInst := false.B
          c.regWrite    := true.B
          c.wbSel       := WbSel.Load
          c.memRead     := true.B
          c.srcB        := SrcBSel.Imm
          c.aluOutNotResult   := true.B
          c.readsRs1    := true.B
        }
      }
    }
    is(Opcode.STORE) {                   // address = rs1 + imm, data = rs2
      switch(funct3) {
        is(Funct3.SB, Funct3.SH, Funct3.SW) {
          c.illegalInst := false.B
          c.memWrite    := true.B
          c.srcB        := SrcBSel.Imm
          c.immSel      := ImmSel.S
          c.readsRs1    := true.B
          c.readsRs2    := true.B
        }
      }
    }
    is(Opcode.BRANCH) {                  // compare in ID, target = pc + imm
      switch(funct3) {
        is(Funct3.BEQ, Funct3.BNE, Funct3.BLT, Funct3.BGE, Funct3.BLTU, Funct3.BGEU) {
          c.illegalInst := false.B
          c.branch      := true.B
          c.immSel      := ImmSel.B
          c.readsRs1    := true.B
          c.readsRs2    := true.B
        }
      }
    }
    is(Opcode.JAL) {                     // jump in ID to pc + imm, rd = pc + 4
      c.illegalInst := false.B
      c.regWrite    := true.B
      c.link        := true.B
      c.jal         := true.B
      c.immSel      := ImmSel.J
    }
    is(Opcode.JALR) {                    // jump in EX to (rs1 + imm) & ~1, rd = pc + 4
      when(funct3 === Funct3.JALR) {
        c.illegalInst := false.B
        c.regWrite    := true.B
        c.link        := true.B
        c.jalr        := true.B
        c.srcB        := SrcBSel.Imm
        c.readsRs1    := true.B
      }
    }
    is(Opcode.MISC_MEM) {                // fence, fence.i: a nop on this core
      when(funct3 === Funct3.FENCE || funct3 === Funct3.FENCE_I) {
        c.illegalInst := false.B
      }
    }
    is(Opcode.SYSTEM) {
      // funct3 000: ecall, ebreak, mret, wfi, told apart by the WHOLE word
      // (any other bit set -> illegal), like isa_sim. wfi is a nop.
      // Zicsr: funct3 001 010 011 101 110 111. The CSR address is checked
      // later, in MEM, by CsrFile.
      when(funct3 =/= 0.U && funct3 =/= 4.U) {
        c.illegalInst     := false.B
        c.regWrite        := true.B
        c.csr             := true.B
        c.csrCmd          := CsrCmd(funct3(1, 0))
        // RW always writes; RS / RC write only when the rs1 field is not 0
        c.csrWrite        := funct3(1, 0) === 1.U || io.inst(19, 15) =/= 0.U
        c.aluOutNotResult := true.B      // the rd value is read in MEM, like a load
        // aluOp = ADD (default): the ALU brings the source value to MEM
        when(funct3(2)) {                // immediate form: 0 + zimm
          c.srcA   := SrcASel.Zero
          c.srcB   := SrcBSel.Imm
          c.immSel := ImmSel.Z
        }.otherwise {                    // register form: rs1 + 0
          c.srcA     := SrcASel.Rs1
          c.srcB     := SrcBSel.Zero
          c.readsRs1 := true.B
        }
      }.elsewhen(io.inst === "h00000073".U) {   // ecall
        c.illegalInst := false.B
        c.ecall       := true.B
      }.elsewhen(io.inst === "h00100073".U) {   // ebreak
        c.illegalInst := false.B
        c.ebreak      := true.B
      }.elsewhen(io.inst === "h30200073".U) {   // mret
        c.illegalInst := false.B
        c.mret        := true.B
      }.elsewhen(io.inst === "h10500073".U) {   // wfi: a nop (no interrupts to wait for)
        c.illegalInst := false.B
      }
    }
    is(Opcode.AMO) {
      // A extension, like isa_sim: funct3 must be 010 (word); lr.w
      // needs the rs2 field 0. All three kinds give rd its value in MEM or
      // WB only (aluOutNotResult), and use rs1 + 0 as the address.
      val funct5  = io.inst(31, 27)
      val rs2Zero = io.inst(24, 20) === 0.U
      when(funct3 === Funct3.LW) {
        when(funct5 === Funct5.LR && rs2Zero) {
          c.illegalInst     := false.B
          c.lr              := true.B
          c.regWrite        := true.B
          c.wbSel           := WbSel.Load  // read like lw
          c.memRead         := true.B
          c.srcB            := SrcBSel.Zero
          c.aluOutNotResult := true.B
          c.readsRs1        := true.B
        }.elsewhen(funct5 === Funct5.SC) {
          c.illegalInst     := false.B
          c.sc              := true.B
          c.regWrite        := true.B      // rd = 0 / 1, put in aluOut in MEM
          c.srcB            := SrcBSel.Zero
          c.aluOutNotResult := true.B
          c.readsRs1        := true.B
          c.readsRs2        := true.B      // store data
        }.elsewhen(Funct5.AMOS.map(_ === funct5).reduce(_ || _)) {
          c.illegalInst     := false.B
          c.amo             := true.B
          c.amoOp           := funct5
          c.regWrite        := true.B      // rd = old value, put in aluOut in MEM
          c.srcB            := SrcBSel.Zero
          c.aluOutNotResult := true.B
          c.readsRs1        := true.B
          c.readsRs2        := true.B      // operand of the AMO
        }
      }
    }
  }
}
