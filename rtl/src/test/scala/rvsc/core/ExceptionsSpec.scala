package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the exception checks (IdExcCheck in ID, ExExcCheck in EX).
// Both are combinational: poke, then expect. Every case says which rule of
// isa_sim it follows.
class ExceptionsSpec extends AnyFlatSpec with ChiselScalatestTester {
  import Cause._

  val ImemPc  = 0x00000100L                // inside IMEM
  val DmemA   = 0x10000100L                // inside DMEM
  val Inst    = 0x12345678L

  // ---------------------------------------------------------------- ID

  case class Id(pc: Long = ImemPc, illegal: Boolean = false, ecall: Boolean = false,
                ebreak: Boolean = false, branchTaken: Boolean = false, jal: Boolean = false,
                target: Long = 0x200L)

  def checkId(c: IdExcCheck, name: String, i: Id, valid: Boolean, cause: Int = 0, tval: Long = 0): Unit = {
    c.io.pc.poke(i.pc.U(32.W)); c.io.inst.poke(Inst.U(32.W)); c.io.illegal.poke(i.illegal.B)
    c.io.ecall.poke(i.ecall.B); c.io.ebreak.poke(i.ebreak.B); c.io.branchTaken.poke(i.branchTaken.B)
    c.io.jal.poke(i.jal.B); c.io.target.poke(i.target.U(32.W))
    c.io.exc.valid.expect(valid.B, s"$name: valid")
    if (valid) {
      c.io.exc.cause.expect(cause.U, s"$name: cause")
      c.io.exc.tval.expect(tval.U(32.W), s"$name: tval")
    }
  }

  "IdExcCheck" should "find each ID exception with its cause and tval" in {
    test(new IdExcCheck) { c =>
      checkId(c, "normal instruction", Id(), valid = false)
      checkId(c, "pc outside IMEM", Id(pc = 0x10000000L), valid = true, INSN_ACCESS_FAULT, 0x10000000L)
      checkId(c, "illegal", Id(illegal = true), valid = true, ILLEGAL_INSN, Inst)
      checkId(c, "ecall", Id(ecall = true), valid = true, ECALL_FROM_M, 0)
      checkId(c, "ebreak", Id(ebreak = true), valid = true, BREAKPOINT, ImemPc)
      checkId(c, "taken branch, target aligned", Id(branchTaken = true, target = 0x204L), valid = false)
      checkId(c, "taken branch, target misaligned", Id(branchTaken = true, target = 0x206L),
              valid = true, INSN_MISALIGNED, 0x206L)
      checkId(c, "jal, target misaligned", Id(jal = true, target = 0x10aL),
              valid = true, INSN_MISALIGNED, 0x10aL)
      // a branch that is not taken does not jump: no exception, even if the target is bad
      checkId(c, "branch not taken, target misaligned", Id(target = 0x206L), valid = false)
    }
  }

  it should "use the isa_sim priority" in {
    test(new IdExcCheck) { c =>
      // fetch fault first: the instruction word is not real
      checkId(c, "fetch fault + illegal", Id(pc = 0x20000000L, illegal = true),
              valid = true, INSN_ACCESS_FAULT, 0x20000000L)
      checkId(c, "fetch fault + jal misaligned", Id(pc = 0x20000000L, jal = true, target = 0x6L),
              valid = true, INSN_ACCESS_FAULT, 0x20000000L)
      // then illegal, before a misaligned jump
      checkId(c, "illegal + jal misaligned", Id(illegal = true, jal = true, target = 0x6L),
              valid = true, ILLEGAL_INSN, Inst)
    }
  }

  // ---------------------------------------------------------------- EX

  case class Ex(inValid: Boolean = false, inCause: Int = 0, inTval: Long = 0,
                memRead: Boolean = false, memWrite: Boolean = false, funct3: Int = 2,
                addr: Long = DmemA, jalr: Boolean = false, jalrTarget: Long = 0x200L,
                amo: Boolean = false, sc: Boolean = false)

  def checkEx(c: ExExcCheck, name: String, e: Ex, valid: Boolean, cause: Int = 0, tval: Long = 0): Unit = {
    c.io.in.valid.poke(e.inValid.B); c.io.in.cause.poke(e.inCause.U); c.io.in.tval.poke(e.inTval.U(32.W))
    c.io.memRead.poke(e.memRead.B); c.io.memWrite.poke(e.memWrite.B); c.io.funct3.poke(e.funct3.U)
    c.io.amo.poke(e.amo.B); c.io.sc.poke(e.sc.B)
    c.io.addr.poke(e.addr.U(32.W)); c.io.jalr.poke(e.jalr.B); c.io.jalrTarget.poke(e.jalrTarget.U(32.W))
    c.io.out.valid.expect(valid.B, s"$name: valid")
    if (valid) {
      c.io.out.cause.expect(cause.U, s"$name: cause")
      c.io.out.tval.expect(tval.U(32.W), s"$name: tval")
    }
  }

  "ExExcCheck" should "find misaligned and out-of-range loads and stores" in {
    test(new ExExcCheck) { c =>
      checkEx(c, "no memory access", Ex(addr = 0x3L), valid = false)
      // alignment by size: lb / lbu 000 100, lh / lhu 001 101, lw 010
      for (f3 <- Seq(0, 4)) checkEx(c, s"byte load f3=$f3", Ex(memRead = true, funct3 = f3, addr = DmemA + 3), valid = false)
      checkEx(c, "lh aligned", Ex(memRead = true, funct3 = 1, addr = DmemA + 2), valid = false)
      checkEx(c, "lh misaligned", Ex(memRead = true, funct3 = 1, addr = DmemA + 1), valid = true, LOAD_MISALIGNED, DmemA + 1)
      checkEx(c, "lhu misaligned", Ex(memRead = true, funct3 = 5, addr = DmemA + 3), valid = true, LOAD_MISALIGNED, DmemA + 3)
      checkEx(c, "lw misaligned", Ex(memRead = true, funct3 = 2, addr = DmemA + 2), valid = true, LOAD_MISALIGNED, DmemA + 2)
      checkEx(c, "lw outside DMEM", Ex(memRead = true, addr = 0x00000100L), valid = true, LOAD_ACCESS_FAULT, 0x100L)
      checkEx(c, "lw just past DMEM", Ex(memRead = true, addr = 0x10200000L), valid = true, LOAD_ACCESS_FAULT, 0x10200000L)
      checkEx(c, "lw last word of DMEM", Ex(memRead = true, addr = 0x101ffffcL), valid = false)
      // misaligned is checked before the range
      checkEx(c, "lw misaligned and outside", Ex(memRead = true, addr = 0x00000102L), valid = true, LOAD_MISALIGNED, 0x102L)
      checkEx(c, "sh misaligned", Ex(memWrite = true, funct3 = 1, addr = DmemA + 1), valid = true, STORE_MISALIGNED, DmemA + 1)
      checkEx(c, "sw outside DMEM", Ex(memWrite = true, addr = 0x20000000L), valid = true, STORE_ACCESS_FAULT, 0x20000000L)
      checkEx(c, "sb anywhere in DMEM", Ex(memWrite = true, funct3 = 0, addr = DmemA + 1), valid = false)
    }
  }

  it should "find a misaligned JALR target and keep an exception from ID" in {
    test(new ExExcCheck) { c =>
      checkEx(c, "jalr aligned", Ex(jalr = true, jalrTarget = 0x204L), valid = false)
      checkEx(c, "jalr misaligned", Ex(jalr = true, jalrTarget = 0x206L), valid = true, INSN_MISALIGNED, 0x206L)
      // the exception from ID wins: it belongs to an older check of the same instruction
      checkEx(c, "ID illegal kept", Ex(inValid = true, inCause = ILLEGAL_INSN, inTval = Inst, memRead = true, addr = 0x1L),
              valid = true, ILLEGAL_INSN, Inst)
      checkEx(c, "ID fault kept over jalr", Ex(inValid = true, inCause = INSN_ACCESS_FAULT, inTval = 0x20000000L,
              jalr = true, jalrTarget = 0x206L), valid = true, INSN_ACCESS_FAULT, 0x20000000L)
    }
  }

  it should "check lr.w, sc.w and the AMOs like isa_sim" in {
    test(new ExExcCheck) { c =>
      // lr.w is a load: 4 / 5
      checkEx(c, "lr.w aligned", Ex(memRead = true, addr = DmemA + 4), valid = false)
      checkEx(c, "lr.w misaligned", Ex(memRead = true, addr = DmemA + 2), valid = true, LOAD_MISALIGNED, DmemA + 2)
      checkEx(c, "lr.w outside", Ex(memRead = true, addr = 0x20000000L), valid = true, LOAD_ACCESS_FAULT, 0x20000000L)
      // an AMO counts as a store for both checks, although it also reads
      checkEx(c, "amo aligned", Ex(amo = true, addr = DmemA + 8), valid = false)
      checkEx(c, "amo misaligned", Ex(amo = true, addr = DmemA + 1), valid = true, STORE_MISALIGNED, DmemA + 1)
      checkEx(c, "amo outside", Ex(amo = true, addr = 0x20000000L), valid = true, STORE_ACCESS_FAULT, 0x20000000L)
      checkEx(c, "amo misaligned and outside", Ex(amo = true, addr = 0x20000002L), valid = true, STORE_MISALIGNED, 0x20000002L)
      // sc.w: only alignment here; outside DMEM is checked in MEM (reservation)
      checkEx(c, "sc misaligned", Ex(sc = true, addr = DmemA + 2), valid = true, STORE_MISALIGNED, DmemA + 2)
      checkEx(c, "sc outside: not here", Ex(sc = true, addr = 0x20000000L), valid = false)
      // an exception from ID is kept
      checkEx(c, "ID illegal kept over amo", Ex(inValid = true, inCause = ILLEGAL_INSN, inTval = Inst,
              amo = true, addr = 0x1L), valid = true, ILLEGAL_INSN, Inst)
    }
  }
}
