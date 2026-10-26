package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for TrapUnit. Combinational: poke, then expect.
// One case per row of the priority list, then the pairs where two causes
// meet, then a bubble.
class TrapUnitSpec extends AnyFlatSpec with ChiselScalatestTester {
  import Cause._

  val Pc = 0x00000120L; val Inst = 0x7c0020f3L      // csrrs x1, 0x7c0, x0 (no such CSR)
  val Mtvec = 0x00000400L; val Mepc = 0x00000204L

  case class In(valid: Boolean = true, exc: Boolean = false, excCause: Int = 0, excTval: Long = 0,
                csrIllegal: Boolean = false, mret: Boolean = false,
                irq: Boolean = false, irqCause: Int = 0, mtvec: Long = Mtvec)
  case class Out(trap: Boolean = false, interrupt: Boolean = false, cause: Int = 0, tval: Long = 0,
                 redirect: Boolean = false, target: Long = 0)

  def check(c: TrapUnit, name: String, i: In, o: Out): Unit = {
    c.io.valid.poke(i.valid.B); c.io.pc.poke(Pc.U(32.W)); c.io.inst.poke(Inst.U(32.W))
    c.io.exc.valid.poke(i.exc.B); c.io.exc.cause.poke(i.excCause.U); c.io.exc.tval.poke(i.excTval.U(32.W))
    c.io.csrIllegal.poke(i.csrIllegal.B); c.io.mret.poke(i.mret.B)
    c.io.irqPending.poke(i.irq.B); c.io.irqCause.poke(i.irqCause.U)
    c.io.mtvec.poke(i.mtvec.U(32.W)); c.io.mepc.poke(Mepc.U(32.W))
    c.io.trap.expect(o.trap.B, s"$name: trap")
    c.io.redirect.expect(o.redirect.B, s"$name: redirect")
    if (o.trap) {
      c.io.interrupt.expect(o.interrupt.B, s"$name: interrupt")
      c.io.cause.expect(o.cause.U, s"$name: cause")
      c.io.tval.expect(o.tval.U(32.W), s"$name: tval")
    }
    if (o.redirect) c.io.target.expect(o.target.U(32.W), s"$name: target")
  }

  "TrapUnit" should "decide each case of the priority list" in {
    test(new TrapUnit) { c =>
      check(c, "normal instruction", In(), Out())
      check(c, "exception from ID / EX", In(exc = true, excCause = LOAD_ACCESS_FAULT, excTval = 0x40),
            Out(trap = true, cause = LOAD_ACCESS_FAULT, tval = 0x40, redirect = true, target = Mtvec))
      check(c, "ecall", In(exc = true, excCause = ECALL_FROM_M),
            Out(trap = true, cause = ECALL_FROM_M, tval = 0, redirect = true, target = Mtvec))
      check(c, "illegal CSR access", In(csrIllegal = true),
            Out(trap = true, cause = ILLEGAL_INSN, tval = Inst, redirect = true, target = Mtvec))
      check(c, "mret", In(mret = true), Out(redirect = true, target = Mepc))
      check(c, "timer interrupt (FreeRTOS)", In(irq = true, irqCause = 7),
            Out(trap = true, interrupt = true, cause = 7, tval = 0, redirect = true, target = Mtvec))
      // no handler: still a trap; the target 0 tells the testbench to stop
      check(c, "no handler (mtvec = 0)", In(exc = true, excCause = BREAKPOINT, excTval = Pc, mtvec = 0),
            Out(trap = true, cause = BREAKPOINT, tval = Pc, redirect = true, target = 0))
    }
  }

  it should "follow the priority when causes meet" in {
    test(new TrapUnit) { c =>
      check(c, "interrupt over exception", In(irq = true, irqCause = 7, exc = true, excCause = ILLEGAL_INSN),
            Out(trap = true, interrupt = true, cause = 7, redirect = true, target = Mtvec))
      check(c, "exception over illegal CSR", In(exc = true, excCause = INSN_ACCESS_FAULT, excTval = 0x5000,
            csrIllegal = true), Out(trap = true, cause = INSN_ACCESS_FAULT, tval = 0x5000, redirect = true, target = Mtvec))
      check(c, "exception over mret", In(exc = true, excCause = ILLEGAL_INSN, excTval = 1, mret = true),
            Out(trap = true, cause = ILLEGAL_INSN, tval = 1, redirect = true, target = Mtvec))
      check(c, "illegal CSR over mret", In(csrIllegal = true, mret = true),
            Out(trap = true, cause = ILLEGAL_INSN, tval = Inst, redirect = true, target = Mtvec))
    }
  }

  it should "do nothing for a bubble" in {
    test(new TrapUnit) { c =>
      check(c, "bubble with exception bits", In(valid = false, exc = true, excCause = 2), Out())
      check(c, "bubble with mret", In(valid = false, mret = true), Out())
      check(c, "bubble with interrupt", In(valid = false, irq = true, irqCause = 7), Out())
    }
  }
}
