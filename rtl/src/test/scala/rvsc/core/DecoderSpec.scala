package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the decoder.
//   1. One real encoding of each RV32I instruction (37, plus fence.i, plus the 8 of M,
//      plus the 6 of Zicsr with x0 / zimm = 0 cases, plus the 11 of A),
//      made by the RISC-V assembler: every control field must match the
//      instruction table in Decoder.scala.
//   2. Every opcode / funct3 / funct7 combination (128 x 8 x 128): illegalInst
//      must follow the same rules as isa_sim/src/hart.cpp, and an illegal
//      instruction must do nothing (no register or memory write, no jump).
class DecoderSpec extends AnyFlatSpec with ChiselScalatestTester {

  // Expected control values. None = not used by this instruction, not checked.
  case class Exp(
    regWrite: Boolean = false, wbSel: WbSel.Type = WbSel.Result,
    memWrite: Boolean = false, memRead: Boolean = false,
    link: Boolean = false, jalr: Boolean = false,
    branch: Boolean = false, jal: Boolean = false,
    aluOp: Option[AluOp.Type] = None, srcA: Option[SrcASel.Type] = None,
    srcB: Option[SrcBSel.Type] = None, immSel: Option[ImmSel.Type] = None,
    aluOutNotResult: Boolean = false, readsRs1: Boolean = false, readsRs2: Boolean = false,
    mulDiv: Boolean = false, mulDivOp: Option[MulDivOp.Type] = None,
    csr: Boolean = false, csrCmd: CsrCmd.Type = CsrCmd.NONE, csrWrite: Boolean = false,
    ecall: Boolean = false, ebreak: Boolean = false, mret: Boolean = false,
    lr: Boolean = false, sc: Boolean = false, amo: Boolean = false, amoOp: Option[Int] = None)

  def check(c: Decoder, name: String, inst: Long, e: Exp): Unit = {
    c.io.inst.poke(inst.U(32.W))
    val k = c.io.ctrl
    val msg = f"$name (0x$inst%08x)"
    k.illegalInst.expect(false.B, msg)
    k.regWrite.expect(e.regWrite.B, msg)
    k.wbSel.expect(e.wbSel, msg)
    k.memWrite.expect(e.memWrite.B, msg)
    k.memRead.expect(e.memRead.B, msg)
    k.link.expect(e.link.B, msg)
    k.jalr.expect(e.jalr.B, msg)
    k.branch.expect(e.branch.B, msg)
    k.jal.expect(e.jal.B, msg)
    e.aluOp.foreach(v => k.aluOp.expect(v, msg))
    e.srcA.foreach(v => k.srcA.expect(v, msg))
    e.srcB.foreach(v => k.srcB.expect(v, msg))
    e.immSel.foreach(v => k.immSel.expect(v, msg))
    k.aluOutNotResult.expect(e.aluOutNotResult.B, msg)
    k.readsRs1.expect(e.readsRs1.B, msg)
    k.readsRs2.expect(e.readsRs2.B, msg)
    k.mulDiv.expect(e.mulDiv.B, msg)
    e.mulDivOp.foreach(v => k.mulDivOp.expect(v, msg))
    k.csr.expect(e.csr.B, msg)
    k.csrCmd.expect(e.csrCmd, msg)
    k.csrWrite.expect(e.csrWrite.B, msg)
    k.ecall.expect(e.ecall.B, msg)
    k.ebreak.expect(e.ebreak.B, msg)
    k.mret.expect(e.mret.B, msg)
    k.lr.expect(e.lr.B, msg)
    k.sc.expect(e.sc.B, msg)
    k.amo.expect(e.amo.B, msg)
    e.amoOp.foreach(v => k.amoOp.expect(v.U, msg))
  }

  // A extension: funct5 | aq rl | rs2 | rs1 | 010 | rd | 0101111
  def aInst(f5: Int, rd: Int, rs1: Int, rs2: Int, aqrl: Int = 0): Long =
    (f5.toLong << 27) | (aqrl.toLong << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) |
    (2L << 12) | (rd.toLong << 7) | 0x2fL
  val lrExp = Exp(regWrite = true, wbSel = WbSel.Load, memRead = true, srcB = Some(SrcBSel.Zero),
                  aluOutNotResult = true, readsRs1 = true, lr = true)
  val scExp = Exp(regWrite = true, srcB = Some(SrcBSel.Zero), aluOutNotResult = true,
                  readsRs1 = true, readsRs2 = true, sc = true)
  def amoExp(f5: Int) = Exp(regWrite = true, srcB = Some(SrcBSel.Zero), aluOutNotResult = true,
                            readsRs1 = true, readsRs2 = true, amo = true, amoOp = Some(f5))

  // One row per instruction class.
  def rType(op: AluOp.Type) = Exp(regWrite = true, aluOp = Some(op), srcA = Some(SrcASel.Rs1),
                                  srcB = Some(SrcBSel.Rs2), readsRs1 = true, readsRs2 = true)
  def iAlu(op: AluOp.Type) = Exp(regWrite = true, aluOp = Some(op), srcA = Some(SrcASel.Rs1),
                                 srcB = Some(SrcBSel.Imm), immSel = Some(ImmSel.I), readsRs1 = true)
  val load = Exp(regWrite = true, wbSel = WbSel.Load, memRead = true, aluOp = Some(AluOp.ADD),
                 srcA = Some(SrcASel.Rs1), srcB = Some(SrcBSel.Imm), immSel = Some(ImmSel.I),
                 aluOutNotResult = true, readsRs1 = true)
  val store = Exp(memWrite = true, aluOp = Some(AluOp.ADD), srcA = Some(SrcASel.Rs1),
                  srcB = Some(SrcBSel.Imm), immSel = Some(ImmSel.S), readsRs1 = true, readsRs2 = true)
  val branch = Exp(branch = true, immSel = Some(ImmSel.B), readsRs1 = true, readsRs2 = true)
  val nop = Exp()
  // Zicsr: the rd value is read in MEM (aluOutNotResult); the ALU brings
  // rs1 + 0 or 0 + zimm to MEM.
  def csrReg(cmd: CsrCmd.Type, write: Boolean) =
    Exp(regWrite = true, aluOp = Some(AluOp.ADD), srcA = Some(SrcASel.Rs1), srcB = Some(SrcBSel.Zero),
        aluOutNotResult = true, readsRs1 = true, csr = true, csrCmd = cmd, csrWrite = write)
  def csrImm(cmd: CsrCmd.Type, write: Boolean) =
    Exp(regWrite = true, aluOp = Some(AluOp.ADD), srcA = Some(SrcASel.Zero), srcB = Some(SrcBSel.Imm),
        immSel = Some(ImmSel.Z), aluOutNotResult = true, csr = true, csrCmd = cmd, csrWrite = write)
  // M: R-type operands, result from Multiplier / Divider
  def mType(op: MulDivOp.Type) = Exp(regWrite = true, srcA = Some(SrcASel.Rs1), srcB = Some(SrcBSel.Rs2),
                                 readsRs1 = true, readsRs2 = true, mulDiv = true, mulDivOp = Some(op))

  // Encodings from riscv64-unknown-elf-gcc + objdump (-M no-aliases).
  val legal = Seq(
    ("add",    0x003100b3L, rType(AluOp.ADD)),
    ("sub",    0x403100b3L, rType(AluOp.SUB)),
    ("sll",    0x003110b3L, rType(AluOp.SLL)),
    ("slt",    0x003120b3L, rType(AluOp.SLT)),
    ("sltu",   0x003130b3L, rType(AluOp.SLTU)),
    ("xor",    0x003140b3L, rType(AluOp.XOR)),
    ("srl",    0x003150b3L, rType(AluOp.SRL)),
    ("sra",    0x403150b3L, rType(AluOp.SRA)),
    ("or",     0x003160b3L, rType(AluOp.OR)),
    ("and",    0x003170b3L, rType(AluOp.AND)),
    ("mul",    0x023100b3L, mType(MulDivOp.MUL)),
    ("mulh",   0x023110b3L, mType(MulDivOp.MULH)),
    ("mulhsu", 0x023120b3L, mType(MulDivOp.MULHSU)),
    ("mulhu",  0x023130b3L, mType(MulDivOp.MULHU)),
    ("div",    0x023140b3L, mType(MulDivOp.DIV)),
    ("divu",   0x023150b3L, mType(MulDivOp.DIVU)),
    ("rem",    0x023160b3L, mType(MulDivOp.REM)),
    ("remu",   0x023170b3L, mType(MulDivOp.REMU)),
    ("addi -1",    0xfff10093L, iAlu(AluOp.ADD)),
    ("addi 1024",  0x40010093L, iAlu(AluOp.ADD)),   // funct7 field = 0100000, still ADD
    ("slti",       0xffb12093L, iAlu(AluOp.SLT)),
    ("sltiu",      0x00713093L, iAlu(AluOp.SLTU)),
    ("xori",       0xfff14093L, iAlu(AluOp.XOR)),
    ("ori",        0x07f16093L, iAlu(AluOp.OR)),
    ("andi -2048", 0x80017093L, iAlu(AluOp.AND)),
    ("slli",       0x01f11093L, iAlu(AluOp.SLL)),
    ("srli",       0x00315093L, iAlu(AluOp.SRL)),
    ("srai",       0x40315093L, iAlu(AluOp.SRA)),
    ("lui",   0x123450b7L, Exp(regWrite = true, aluOp = Some(AluOp.ADD), srcA = Some(SrcASel.Zero),
                               srcB = Some(SrcBSel.Imm), immSel = Some(ImmSel.U))),
    ("auipc", 0x80000097L, Exp(regWrite = true, aluOp = Some(AluOp.ADD), srcA = Some(SrcASel.Pc),
                               srcB = Some(SrcBSel.Imm), immSel = Some(ImmSel.U))),
    ("lb",  0xffc10083L, load),
    ("lh",  0x00211083L, load),
    ("lw",  0x00812083L, load),
    ("lbu", 0x00114083L, load),
    ("lhu", 0x00615083L, load),
    ("sb",  0xfe310fa3L, store),
    ("sh",  0x00311123L, store),
    ("sw",  0x00312223L, store),
    ("beq",  0x00208063L, branch),
    ("bne",  0xfe209ee3L, branch),
    ("blt",  0xfe20cce3L, branch),
    ("bge",  0xfe20dae3L, branch),
    ("bltu", 0xfe20e8e3L, branch),
    ("bgeu", 0xfe20f6e3L, branch),
    ("jal",  0xfe9ff0efL, Exp(regWrite = true, link = true, jal = true, immSel = Some(ImmSel.J))),
    ("jalr", 0x004100e7L, Exp(regWrite = true, link = true, jalr = true, aluOp = Some(AluOp.ADD),
                              srcA = Some(SrcASel.Rs1), srcB = Some(SrcBSel.Imm),
                              immSel = Some(ImmSel.I), readsRs1 = true)),
    ("csrrw x1, mscratch, x2",  0x340110f3L, csrReg(CsrCmd.RW, true)),
    ("csrrs x1, mscratch, x2",  0x340120f3L, csrReg(CsrCmd.RS, true)),
    ("csrrc x1, mscratch, x2",  0x340130f3L, csrReg(CsrCmd.RC, true)),
    ("csrrs x1, mscratch, x0",  0x340020f3L, csrReg(CsrCmd.RS, false)),   // read only
    ("csrrc x1, mscratch, x0",  0x340030f3L, csrReg(CsrCmd.RC, false)),   // read only
    ("csrrw x0, mstatus, x3",   0x30019073L, csrReg(CsrCmd.RW, true)),    // rd = x0: still writes
    ("csrrs t0, cycle, x0",     0xc00022f3L, csrReg(CsrCmd.RS, false)),   // csrr t0, cycle
    ("csrrwi x1, mscratch, 5",  0x3402d0f3L, csrImm(CsrCmd.RW, true)),
    ("csrrsi x1, mscratch, 31", 0x340fe0f3L, csrImm(CsrCmd.RS, true)),
    ("csrrci x1, mscratch, 1",  0x3400f0f3L, csrImm(CsrCmd.RC, true)),
    ("csrrsi x1, mscratch, 0",  0x340060f3L, csrImm(CsrCmd.RS, false)),   // zimm = 0: read only
    ("csrrci x1, mscratch, 0",  0x340070f3L, csrImm(CsrCmd.RC, false)),
    ("ecall",   0x00000073L, Exp(ecall = true)),     // exceptions and mret
    ("ebreak",  0x00100073L, Exp(ebreak = true)),
    ("mret",    0x30200073L, Exp(mret = true)),
    ("wfi",     0x10500073L, nop),                   // a nop: no interrupts
    ("fence",   0x0ff0000fL, nop),
    ("fence.i", 0x0000100fL, nop),
    ("lr.w t0, (a0)",           aInst(0x02, 5, 10, 0),      lrExp),       // A extension
    ("lr.w.aqrl t0, (a0)",      aInst(0x02, 5, 10, 0, 3),   lrExp),       // aq / rl: no effect
    ("sc.w t1, t0, (a0)",       aInst(0x03, 6, 10, 5),      scExp),
    ("amoswap.w s4, t5, (a0)",  aInst(0x01, 20, 10, 30),    amoExp(0x01)),
    ("amoadd.w s5, t5, (a0)",   aInst(0x00, 21, 10, 30),    amoExp(0x00)),
    ("amoxor.w s6, t5, (a0)",   aInst(0x04, 22, 10, 30),    amoExp(0x04)),
    ("amoand.w s7, t5, (a0)",   aInst(0x0c, 23, 10, 30),    amoExp(0x0c)),
    ("amoor.w s8, t6, (a0)",    aInst(0x08, 24, 10, 31),    amoExp(0x08)),
    ("amomin.w s9, t5, (a0)",   aInst(0x10, 25, 10, 30),    amoExp(0x10)),
    ("amomax.w s11, t5, (a0)",  aInst(0x14, 27, 10, 30),    amoExp(0x14)),
    ("amominu.w a2, t6, (a0)",  aInst(0x18, 12, 10, 31),    amoExp(0x18)),
    ("amomaxu.w a3, t6, (a0)",  aInst(0x1c, 13, 10, 31, 2), amoExp(0x1c))
  )

  "Decoder" should "give the control values of every RV32I, M, Zicsr and A instruction" in {
    test(new Decoder) { c =>
      for ((name, inst, e) <- legal) check(c, name, inst, e)
    }
  }

  // ----------------------------------------------------------- illegal rules

  // The rules of isa_sim/src/hart.cpp for RV32IMA + Zicsr (SYSTEM funct3 000 is legal only
  // for the four words below, 100 never; the CSR address is checked later, in MEM).
  val systemWords = Set(0x00000073L, 0x00100073L, 0x30200073L, 0x10500073L)  // ecall ebreak mret wfi
  def isLegal(opcode: Int, f3: Int, f7: Int, inst: Long): Boolean = opcode match {
    case 0x33 => f7 == 0x00 || (f7 == 0x20 && (f3 == 0 || f3 == 5)) || f7 == 0x01  // OP, M
    case 0x13 => f3 match {                                                   // OP_IMM
      case 1 => f7 == 0x00
      case 5 => f7 == 0x00 || f7 == 0x20
      case _ => true
    }
    case 0x37 | 0x17 | 0x6f => true                                           // LUI AUIPC JAL
    case 0x03 => Set(0, 1, 2, 4, 5).contains(f3)                              // LOAD
    case 0x23 => f3 <= 2                                                      // STORE
    case 0x63 => f3 != 2 && f3 != 3                                           // BRANCH
    case 0x67 => f3 == 0                                                      // JALR
    case 0x0f => f3 <= 1                                                      // MISC_MEM
    case 0x73 => if (f3 == 0) systemWords.contains(inst) else f3 != 4         // SYSTEM
    case 0x2f => f3 == 2 && (f7 >> 2 match {                                 // AMO
      case 0x02 => ((inst >> 20) & 0x1f) == 0                                 // lr.w: rs2 = 0
      case f5   => Set(0x03, 0x01, 0x00, 0x04, 0x0c, 0x08, 0x10, 0x14, 0x18, 0x1c).contains(f5)
    })
    case _    => false
  }

  it should "follow the isa_sim legality rules for every opcode, funct3, funct7" in {
    val rnd = new scala.util.Random(4)
    test(new Decoder) { c =>
      for (opcode <- 0 until 128; f3 <- 0 until 8; f7 <- 0 until 128) {
        // rd, rs1, rs2 are random: they must not change the result
        val inst = (f7.toLong << 25) | (rnd.nextInt(1 << 10).toLong << 15) |
                   (f3.toLong << 12) | (rnd.nextInt(32).toLong << 7) | opcode
        c.io.inst.poke(inst.U(32.W))
        val legal = isLegal(opcode, f3, f7, inst)
        val msg = f"inst=0x$inst%08x opcode=0x$opcode%02x funct3=$f3 funct7=0x$f7%02x"
        c.io.ctrl.illegalInst.expect((!legal).B, msg)
        if (opcode == 0x13 && f3 == 0)     // addi: bits 31:25 are immediate, never SUB
          c.io.ctrl.aluOp.expect(AluOp.ADD, msg)
        if (!legal) {                      // an illegal instruction does nothing
          c.io.ctrl.regWrite.expect(false.B, msg)
          c.io.ctrl.memWrite.expect(false.B, msg)
          c.io.ctrl.memRead.expect(false.B, msg)
          c.io.ctrl.branch.expect(false.B, msg)
          c.io.ctrl.jal.expect(false.B, msg)
          c.io.ctrl.jalr.expect(false.B, msg)
          c.io.ctrl.mulDiv.expect(false.B, msg)
          c.io.ctrl.csr.expect(false.B, msg)
          c.io.ctrl.ecall.expect(false.B, msg)
          c.io.ctrl.ebreak.expect(false.B, msg)
          c.io.ctrl.mret.expect(false.B, msg)
          c.io.ctrl.lr.expect(false.B, msg)
          c.io.ctrl.sc.expect(false.B, msg)
          c.io.ctrl.amo.expect(false.B, msg)
        }
        if (opcode != 0x73)                // only SYSTEM is a CSR instruction
          c.io.ctrl.csr.expect(false.B, msg)
        if (opcode == 0x33 && f7 != 0x01)  // only funct7 0000001 is M
          c.io.ctrl.mulDiv.expect(false.B, msg)
        if (opcode != 0x2f) {              // only AMO is A
          c.io.ctrl.lr.expect(false.B, msg)
          c.io.ctrl.sc.expect(false.B, msg)
          c.io.ctrl.amo.expect(false.B, msg)
        }
      }
    }
  }

  it should "mark all-zero, all-one and near-miss SYSTEM words illegal" in {
    test(new Decoder) { c =>
      // ecall with rd = x1, ebreak with rs1 = x1, mret with bit 31 set, sret, uret, sfence.vma
      for (inst <- Seq(0x00000000L, 0xffffffffL, 0x000000f3L, 0x00108073L, 0xb0200073L,
                       0x10200073L, 0x00200073L, 0x12000073L)) {
        c.io.inst.poke(inst.U(32.W))
        c.io.ctrl.illegalInst.expect(true.B, f"0x$inst%08x")
        c.io.ctrl.regWrite.expect(false.B, f"0x$inst%08x")
      }
    }
  }
}
