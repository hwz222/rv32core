package rvsc

import chisel3._

// Constants and encodings shared by the core modules (decoder, ALU, Core).
// Rule: a name used by two or more modules lives here; a name used by only
// one module stays in that module's file.
//
// Three kinds of definitions:
//   Scala Int    sizes, used when the hardware is built: UInt(Consts.XLEN.W)
//   UInt         bit patterns to compare with instruction fields: Opcode, Funct3
//   ChiselEnum   select signals passed between modules (AluOp, SrcASel, ...).
//                A port of type AluOp() only accepts AluOp values, so a wrong
//                connection stops the Verilog build. Use .asUInt where a UInt
//                is needed (for example an events port).
//
// Sections:
//   1. Sizes            Consts
//   2. Instruction bits Opcode, Funct3, Funct7 (all instruction encodings
//                       live here, even those only the decoder reads)
//   3. Select signals   AluOp, SrcASel, SrcBSel, ImmSel, WbSel

// ===========================================================================
// 1. Sizes (Scala Int)
// ===========================================================================

object Consts {
  val XLEN       = 32                    // register and data width (RV32)
  val ILEN       = 32                    // instruction width (no compressed)
  val NUM_REGS   = 32                    // x0..x31
  val REG_ADDR_W = 5                     // bits of rs1, rs2, rd
  val SHAMT_W    = 5                     // shift amount: low 5 bits of rs2/imm
  val NOP        = 0x00000013L           // addi x0, x0, 0 (for tests; bubbles use valid = 0)
}

// ===========================================================================
// 2. Instruction bits (UInt patterns)
//
//   31      25 24  20 19  15 14  12 11   7 6      0
//   | funct7  | rs2  | rs1  |funct3|  rd  | opcode |
// ===========================================================================

object Opcode {
  // RV32I
  val LUI      = "b0110111".U(7.W)
  val AUIPC    = "b0010111".U(7.W)
  val JAL      = "b1101111".U(7.W)
  val JALR     = "b1100111".U(7.W)
  val BRANCH   = "b1100011".U(7.W)       // beq bne blt bge bltu bgeu
  val LOAD     = "b0000011".U(7.W)       // lb lh lw lbu lhu
  val STORE    = "b0100011".U(7.W)       // sb sh sw
  val OP_IMM   = "b0010011".U(7.W)       // addi ... srai
  val OP       = "b0110011".U(7.W)       // add ... and (and M: mul ... remu)
  val MISC_MEM = "b0001111".U(7.W)       // fence, fence.i
  val SYSTEM   = "b1110011".U(7.W)       // ecall ebreak mret wfi, csr*
  // A extension
  val AMO      = "b0101111".U(7.W)       // lr.w sc.w amo*.w
}

object Funct3 {
  // Branches (opcode BRANCH)
  val BEQ  = "b000".U(3.W)
  val BNE  = "b001".U(3.W)
  val BLT  = "b100".U(3.W)
  val BGE  = "b101".U(3.W)
  val BLTU = "b110".U(3.W)
  val BGEU = "b111".U(3.W)

  // Loads (opcode LOAD); stores use the same codes for B, H, W
  val LB  = "b000".U(3.W)
  val LH  = "b001".U(3.W)
  val LW  = "b010".U(3.W)
  val LBU = "b100".U(3.W)
  val LHU = "b101".U(3.W)
  val SB  = "b000".U(3.W)
  val SH  = "b001".U(3.W)
  val SW  = "b010".U(3.W)

  // ALU instructions (opcode OP and OP_IMM; I-type names in brackets)
  val ADD_SUB = "b000".U(3.W)            // add, sub  [addi]
  val SLL     = "b001".U(3.W)            // sll       [slli]
  val SLT     = "b010".U(3.W)            // slt       [slti]
  val SLTU    = "b011".U(3.W)            // sltu      [sltiu]
  val XOR     = "b100".U(3.W)            // xor       [xori]
  val SRL_SRA = "b101".U(3.W)            // srl, sra  [srli, srai]
  val OR      = "b110".U(3.W)            // or        [ori]
  val AND     = "b111".U(3.W)            // and       [andi]

  // Others
  val JALR    = "b000".U(3.W)            // the only legal funct3 of jalr
  val FENCE   = "b000".U(3.W)            // fence
  val FENCE_I = "b001".U(3.W)            // fence.i
}

// funct7 = inst(31, 25). For slli/srli/srai it is the top of the immediate.
object Funct7 {
  val NORMAL = "b0000000".U(7.W)         // add, srl, and the other base forms
  val ALT    = "b0100000".U(7.W)         // sub, sra, srai (bit 30 set)
  val MULDIV = "b0000001".U(7.W)         // M extension
}

// A extension: funct5 = inst(31, 27) of opcode AMO (funct3 = 010, word).
// Bits 26:25 are aq / rl: no effect on one hart.
object Funct5 {
  val LR      = "b00010".U(5.W)          // lr.w: load and reserve
  val SC      = "b00011".U(5.W)          // sc.w: store if the reservation holds
  val AMOSWAP = "b00001".U(5.W)          // the 9 AMOs: rd = old, mem = op(old, rs2)
  val AMOADD  = "b00000".U(5.W)
  val AMOXOR  = "b00100".U(5.W)
  val AMOAND  = "b01100".U(5.W)
  val AMOOR   = "b01000".U(5.W)
  val AMOMIN  = "b10000".U(5.W)          // signed
  val AMOMAX  = "b10100".U(5.W)
  val AMOMINU = "b11000".U(5.W)          // unsigned
  val AMOMAXU = "b11100".U(5.W)
  val AMOS = Seq(AMOSWAP, AMOADD, AMOXOR, AMOAND, AMOOR, AMOMIN, AMOMAX, AMOMINU, AMOMAXU)
}

// ===========================================================================
// 3. Select signals (ChiselEnum)
// ===========================================================================

// ALU operation. Each value is {funct7[5], funct3} of the R-type instruction,
// so 1101 in a waveform reads as SRA. The decoder still names the operation
// for every instruction (LUI, AUIPC, loads, stores, JALR use ADD); it does not
// copy instruction bits into this signal. Values must be declared in
// increasing order.
object AluOp extends ChiselEnum {
  val ADD  = Value("b0000".U)
  val SLL  = Value("b0001".U)
  val SLT  = Value("b0010".U)
  val SLTU = Value("b0011".U)
  val XOR  = Value("b0100".U)
  val SRL  = Value("b0101".U)
  val OR   = Value("b0110".U)
  val AND  = Value("b0111".U)
  val SUB  = Value("b1000".U)
  val SRA  = Value("b1101".U)
}

// Exception codes written to mcause, the same as isa_sim/src/memory.h.
object Cause {
  val INSN_MISALIGNED   = 0              // jump target not 4-byte aligned; tval = target
  val INSN_ACCESS_FAULT = 1              // pc outside IMEM; tval = pc
  val ILLEGAL_INSN      = 2              // tval = the instruction
  val BREAKPOINT        = 3              // ebreak; tval = pc
  val LOAD_MISALIGNED   = 4              // tval = address
  val LOAD_ACCESS_FAULT = 5              // address outside DMEM; tval = address
  val STORE_MISALIGNED  = 6              // also AMO / SC; tval = address
  val STORE_ACCESS_FAULT = 7             // tval = address
  val ECALL_FROM_M      = 11             // ecall; tval = 0
}

// M extension operation. The value is the funct3 of the instruction,
// so the decoder copies the bits and a waveform reads directly.
// mulDivOp(2) = 1 means a divide (multi-cycle, Divider); 0 means a multiply
// (one cycle, Multiplier).
object MulDivOp extends ChiselEnum {
  val MUL    = Value("b000".U)           // low 32 bits of rs1 * rs2
  val MULH   = Value("b001".U)           // high 32 bits, signed   x signed
  val MULHSU = Value("b010".U)           // high 32 bits, signed   x unsigned
  val MULHU  = Value("b011".U)           // high 32 bits, unsigned x unsigned
  val DIV    = Value("b100".U)           // signed quotient
  val DIVU   = Value("b101".U)           // unsigned quotient
  val REM    = Value("b110".U)           // signed remainder (sign of rs1)
  val REMU   = Value("b111".U)           // unsigned remainder
}

// ALU input A (SrcA in the diagram)
object SrcASel extends ChiselEnum {
  val Rs1, Pc, Zero = Value              // Pc: AUIPC; Zero: LUI
}

// ALU input B (SrcB in the diagram)
object SrcBSel extends ChiselEnum {
  val Rs2, Imm, Zero = Value             // Zero: CSR register forms (rs1 + 0)
}

// Immediate format made by ImmGen. R-type has no immediate: any value works.
// Z is the CSR format (zimm for csrr*i, 0 for the register forms).
object ImmSel extends ChiselEnum {
  val I, S, B, U, J, Z = Value           // Z: CSR immediate forms, zimm = inst(19, 15)
}

// How a CSR instruction changes the CSR. The value is funct3(1, 0) of
// the instruction, so the decoder copies the bits; NONE (00) is the default
// of every other instruction and of a bubble.
//   RW: new = src        (csrrw,  csrrwi)
//   RS: new = old | src  (csrrs,  csrrsi)   set bits
//   RC: new = old & ~src (csrrc,  csrrci)   clear bits
// src is rs1 or zimm, brought to MEM by the ALU. The write happens only
// when csrWrite is 1 (see CtrlSignals).
object CsrCmd extends ChiselEnum {
  val NONE = Value("b00".U)
  val RW   = Value("b01".U)
  val RS   = Value("b10".U)
  val RC   = Value("b11".U)
}

// Value written back in WB (WbSel in the diagram). Result is the EX/MEM
// result (ALU or pc+4, later the CSR read data); Load is the aligned load data.
object WbSel extends ChiselEnum {
  val Result, Load = Value
}
