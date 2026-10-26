package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the CSR file. The expected values follow isa_sim
// (hart.cpp: csr_read, csr_write, the counter update in step()).
// One "cycle" here = poke the inputs, check rdata / illegal (combinational),
// then step the clock (the write and the counters happen at the edge).
//   1. Reset values and read-only constants.
//   2. RW / RS / RC, and RS / RC with write = 0 (read only).
//   3. Write masks: mstatus, mtvec, mepc.
//   4. Illegal: no such CSR; a write to a read-only CSR; valid = 0 writes nothing.
//   5. hpm counters and events: read 0, writes ignored (legal where writable).
//   6. Counters: mcycle every cycle; aliases; minstret, its read compensation,
//      writes, and "the writing instruction does not count itself".
//   7. 64-bit halves.
class CsrFileSpec extends AnyFlatSpec with ChiselScalatestTester {
  import CsrAddr._

  def init(c: CsrFile): Unit = {
    c.io.valid.poke(false.B); c.io.write.poke(false.B); c.io.cmd.poke(CsrCmd.NONE)
    c.io.addr.poke(0.U); c.io.wdata.poke(0.U); c.io.retire.poke(false.B)
    c.io.trap.poke(false.B); c.io.cause.poke(0.U); c.io.tval.poke(0.U); c.io.epc.poke(0.U)
    c.io.mret.poke(false.B)
    c.io.irq.msip.poke(false.B); c.io.irq.mtip.poke(false.B); c.io.irq.meip.poke(false.B)
  }

  // One CSR instruction in MEM for one cycle. Returns the read value.
  def op(c: CsrFile, addr: Int, cmd: CsrCmd.Type, wdata: Long, write: Boolean = true,
         retire: Boolean = false, expectIllegal: Boolean = false): BigInt = {
    c.io.valid.poke(true.B); c.io.addr.poke(addr.U); c.io.cmd.poke(cmd)
    c.io.wdata.poke(wdata.U(32.W)); c.io.write.poke(write.B); c.io.retire.poke(retire.B)
    c.io.illegal.expect(expectIllegal.B, f"illegal for 0x$addr%03x write=$write")
    val v = c.io.rdata.peek().litValue
    c.clock.step()
    c.io.valid.poke(false.B); c.io.write.poke(false.B); c.io.retire.poke(false.B)
    v
  }
  def read(c: CsrFile, addr: Int, retire: Boolean = false): BigInt =
    op(c, addr, CsrCmd.RS, 0, write = false, retire = retire)
  def idle(c: CsrFile, retire: Boolean = false, n: Int = 1): Unit = {
    c.io.valid.poke(false.B); c.io.retire.poke(retire.B)
    c.clock.step(n)
    c.io.retire.poke(false.B)
  }

  "CsrFile" should "start with the reset values and the read-only constants" in {
    test(new CsrFile) { c =>
      init(c)
      assert(read(c, MSTATUS) == MSTATUS_MPP, "mstatus: MPP reads as 11")
      assert(read(c, MISA) == MISA_VALUE, "misa")
      for (a <- Seq(MIE, MTVEC, MSTATUSH, MSCRATCH, MEPC, MCAUSE, MTVAL, MIP,
                    MVENDORID, MARCHID, MIMPID, MHARTID, MCONFIGPTR, MINSTRET, MINSTRETH))
        assert(read(c, a) == 0, f"0x$a%03x after reset")
    }
  }

  it should "do RW, RS, RC and give the old value" in {
    test(new CsrFile) { c =>
      init(c)
      assert(op(c, MSCRATCH, CsrCmd.RW, 0x1234) == 0)        // csrrw: old 0
      assert(op(c, MSCRATCH, CsrCmd.RS, 0x00f0) == 0x1234)   // csrrs
      assert(op(c, MSCRATCH, CsrCmd.RC, 0x00f0) == 0x12f4)   // csrrc
      assert(read(c, MSCRATCH) == 0x1204)
      // RS / RC with write = 0 (rs1 = x0): read only, even with wdata != 0
      assert(op(c, MSCRATCH, CsrCmd.RS, 0xffff, write = false) == 0x1204)
      assert(op(c, MSCRATCH, CsrCmd.RC, 0xffff, write = false) == 0x1204)
      assert(read(c, MSCRATCH) == 0x1204)
      // the other plain 32-bit CSRs
      for (a <- Seq(MCAUSE, MTVAL)) {
        op(c, a, CsrCmd.RW, 0xdeadbeefL)
        assert(read(c, a) == 0xdeadbeefL, f"0x$a%03x")
      }
    }
  }

  it should "apply the write masks of mstatus, mtvec and mepc" in {
    test(new CsrFile) { c =>
      init(c)
      op(c, MSTATUS, CsrCmd.RW, 0xffffffffL)
      assert(read(c, MSTATUS) == (0x88 | MSTATUS_MPP), "mstatus keeps MIE, MPIE; MPP = 11")
      op(c, MSTATUS, CsrCmd.RC, 0x8)                         // clear MIE
      assert(read(c, MSTATUS) == (0x80 | MSTATUS_MPP))
      op(c, MTVEC, CsrCmd.RW, 0x80000103L)
      assert(read(c, MTVEC) == 0x80000100L, "mtvec: MODE = 0")
      c.io.mtvec.expect(0x80000100L.U, "mtvec output")
      op(c, MEPC, CsrCmd.RW, 0x00001007L)
      assert(read(c, MEPC) == 0x00001004L, "mepc: low 2 bits 0")
      c.io.mepc.expect(0x00001004L.U, "mepc output")
    }
  }

  it should "report illegal accesses and not write them" in {
    test(new CsrFile) { c =>
      init(c)
      // no such CSR: illegal for read and write
      op(c, 0x7c0, CsrCmd.RS, 0, write = false, expectIllegal = true)
      op(c, 0x7c0, CsrCmd.RW, 5, expectIllegal = true)
      // a write to a read-only CSR is illegal ...
      for (a <- Seq(CYCLE, INSTRET, MHARTID, MVENDORID))
        op(c, a, CsrCmd.RW, 5, expectIllegal = true)
      // ... but reading it is legal
      read(c, CYCLE); read(c, MHARTID)
      // misa has a read-write address: the write is legal but ignored (WARL)
      op(c, MISA, CsrCmd.RW, 0)
      assert(read(c, MISA) == MISA_VALUE, "misa is not written")
      // an illegal write changes nothing: a write to cycle does not change mcycle
      val before = read(c, MCYCLE)
      op(c, CYCLE, CsrCmd.RW, 0, expectIllegal = true)
      assert(read(c, MCYCLE) == before + 2, "cycle is read-only: mcycle just keeps counting")
      // valid = 0: nothing is written
      c.io.valid.poke(false.B); c.io.addr.poke(MSCRATCH.U); c.io.cmd.poke(CsrCmd.RW)
      c.io.wdata.poke(7.U); c.io.write.poke(true.B)
      c.clock.step()
      c.io.write.poke(false.B)
      assert(read(c, MSCRATCH) == 0, "valid = 0 must not write")
    }
  }

  it should "read the hpm counters and events as 0 and ignore writes" in {
    test(new CsrFile) { c =>
      init(c)
      for (a <- Seq(0xb03, 0xb1f, 0xb83, 0xb9f, 0x323, 0x33f)) {
        op(c, a, CsrCmd.RW, 0x55)                            // writable range: legal
        assert(read(c, a) == 0, f"0x$a%03x reads 0")
      }
      for (a <- Seq(0xc03, 0xc1f, 0xc83, 0xc9f)) {
        assert(read(c, a) == 0, f"0x$a%03x reads 0")
        op(c, a, CsrCmd.RW, 0x55, expectIllegal = true)      // read-only range
      }
      for (a <- Seq(0xb20, 0xc20, 0x322, 0x350))             // just outside the ranges
        op(c, a, CsrCmd.RS, 0, write = false, expectIllegal = true)
    }
  }

  it should "count cycles and show them in cycle and time" in {
    test(new CsrFile) { c =>
      init(c)
      idle(c, n = 10)
      val a = read(c, MCYCLE)
      val b = read(c, CYCLE)
      val t = read(c, TIME)
      assert(b == a + 1 && t == b + 1, s"mcycle $a, cycle $b, time $t: one more each cycle")
      op(c, MCYCLE, CsrCmd.RW, 1000)                         // the write wins over +1
      assert(read(c, MCYCLE) == 1000)
      assert(read(c, MCYCLE) == 1001)
    }
  }

  it should "count retired instructions like isa_sim" in {
    test(new CsrFile) { c =>
      init(c)
      idle(c, retire = true, n = 5)                          // 5 instructions retired
      assert(read(c, MINSTRET) == 5)
      // read compensation: an instruction retires in WB in the same cycle
      assert(read(c, MINSTRET, retire = true) == 6, "minstret + 1 while WB retires")
      // now 6 retired; the alias shows the same
      assert(read(c, INSTRET) == 6)
      // write minstret = 100 while an older instruction retires in WB:
      // the write wins at the edge
      op(c, MINSTRET, CsrCmd.RW, 100, retire = true)
      // next cycle the writing instruction itself retires: not counted
      idle(c, retire = true)
      assert(read(c, MINSTRET) == 100, "the instruction that wrote minstret does not count")
      // later retirements count again
      idle(c, retire = true, n = 3)
      assert(read(c, MINSTRET) == 103)
    }
  }

  // ---- trap and mret
  def trapCycle(c: CsrFile, epc: Long, cause: Long, tval: Long): Unit = {
    c.io.trap.poke(true.B); c.io.epc.poke(epc.U(32.W)); c.io.cause.poke(cause.U(32.W))
    c.io.tval.poke(tval.U(32.W))
    c.clock.step()
    c.io.trap.poke(false.B)
  }
  def mretCycle(c: CsrFile): Unit = {
    c.io.mret.poke(true.B); c.clock.step(); c.io.mret.poke(false.B)
  }

  it should "save the trap in mepc, mcause, mtval and turn interrupts off" in {
    test(new CsrFile) { c =>
      init(c)
      op(c, MSTATUS, CsrCmd.RS, 0x8)                         // MIE = 1
      trapCycle(c, 0x00000123L, 5, 0x10200000L)               // load access fault at pc 0x120 (+3)
      assert(read(c, MEPC) == 0x120, "mepc = epc with bits 1:0 cleared")
      c.io.mepc.expect(0x120.U, "mepc output")
      assert(read(c, MCAUSE) == 5)
      assert(read(c, MTVAL) == 0x10200000L)
      assert(read(c, MSTATUS) == (0x80 | MSTATUS_MPP), "MPIE = old MIE (1), MIE = 0")
      // an interrupt cause keeps bit 31 (FreeRTOS)
      trapCycle(c, 0x200, 0x80000007L, 0)
      assert(read(c, MCAUSE) == 0x80000007L)
      assert(read(c, MSTATUS) == MSTATUS_MPP, "MPIE = old MIE (0), MIE = 0")
    }
  }

  it should "restore MIE on mret" in {
    test(new CsrFile) { c =>
      init(c)
      op(c, MSTATUS, CsrCmd.RS, 0x8)                         // MIE = 1
      trapCycle(c, 0x100, 11, 0)                             // ecall: MPIE = 1, MIE = 0
      mretCycle(c)
      assert(read(c, MSTATUS) == (0x88 | MSTATUS_MPP), "MIE = MPIE (1), MPIE = 1")
      // with MIE = 0 before the trap
      op(c, MSTATUS, CsrCmd.RC, 0x8)                         // MIE = 0
      trapCycle(c, 0x100, 11, 0)                             // MPIE = 0, MIE = 0
      mretCycle(c)
      assert(read(c, MSTATUS) == (0x80 | MSTATUS_MPP), "MIE = MPIE (0), MPIE = 1")
      // mret does not touch mepc, mcause, mtval
      assert(read(c, MEPC) == 0x100 && read(c, MCAUSE) == 11)
    }
  }

  it should "keep 64-bit counters in two halves" in {
    test(new CsrFile) { c =>
      init(c)
      op(c, MINSTRET, CsrCmd.RW, 0xffffffffL)
      idle(c, retire = true)                                 // the writer: not counted
      idle(c, retire = true)                                 // +1: carries into the high half
      assert(read(c, MINSTRET) == 0)
      assert(read(c, MINSTRETH) == 1)
      assert(read(c, INSTRETH) == 1)
      op(c, MINSTRETH, CsrCmd.RW, 7)
      assert(read(c, MINSTRETH) == 7)
      op(c, MCYCLEH, CsrCmd.RW, 3)
      assert(read(c, MCYCLEH) == 3 && read(c, CYCLEH) == 3 && read(c, TIMEH) == 3)
    }
  }
}
