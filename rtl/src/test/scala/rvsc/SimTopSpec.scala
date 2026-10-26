package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// SimTop with StubCore and small memories: the same steps the Verilator
// testbench will do (load with hostEn = 1, run, dump with hostEn = 1).
class SimTopSpec extends AnyFlatSpec with ChiselScalatestTester {

  def load(c: SimTop, addr: Long, data: Long): Unit = {
    c.io.load.valid.poke(true.B)
    c.io.load.bits.addr.poke(addr.U)
    c.io.load.bits.data.poke(data.U)
    c.clock.step()
    c.io.load.valid.poke(false.B)
  }

  "SimTop with StubCore" should "load, fetch from bootAddr in order, and dump" in {
    test(new SimTop(new StubCore, imemWords = 64, dmemWords = 64)) { c =>
      val code = Seq(0x00000013L, 0x00100093L, 0x00200113L, 0x00300193L)

      // ---- Load phase: reset and hostEn held.
      c.reset.poke(true.B)
      c.io.hostEn.poke(true.B)
      c.io.bootAddr.poke(4.U)               // start at the 2nd word
      for ((w, i) <- code.zipWithIndex) load(c, 4L * i, w)
      load(c, 0x10000000L, 0xcafef00dL)     // DMEM word 0
      // Outside both regions: must be dropped. Its low bits point to word 0,
      // so if it were written to IMEM, word 0 would change.
      load(c, 0x20000000L, 0xdeadbeefL)

      // ---- Run phase.
      c.reset.poke(false.B)
      c.io.hostEn.poke(false.B)
      c.io.retire.valid.expect(false.B)     // first answer comes next cycle
      for (i <- 1 until code.length) {
        c.clock.step()
        c.io.retire.valid.expect(true.B)
        c.io.retire.pc.expect((4 * i).U)
        c.io.retire.insn.expect(code(i).U)
        c.io.retire.rdWe.expect(false.B)
        c.io.retire.memWe.expect(false.B)
        c.io.retire.trap.expect(false.B)
      }

      // ---- Dump phase: hostEn on, the core stops, DMEM word 0 is readable.
      c.io.hostEn.poke(true.B)
      c.io.dumpAddr.poke(0x10000000L.U)
      c.clock.step()
      c.io.dumpData.expect(0xcafef00dL.U)
      c.clock.step()
      c.io.retire.valid.expect(false.B)     // no fetch while hostEn = 1
    }
  }

  it should "not write IMEM for a load outside both regions" in {
    test(new SimTop(new StubCore, imemWords = 64, dmemWords = 64)) { c =>
      c.reset.poke(true.B)
      c.io.hostEn.poke(true.B)
      c.io.bootAddr.poke(0.U)
      load(c, 0x00000000L, 0x00000013L)
      load(c, 0x20000000L, 0xdeadbeefL)     // same low bits as word 0
      load(c, 0x10000000L, 0x11111111L)     // DMEM word 0, also same low bits
      c.reset.poke(false.B)
      c.io.hostEn.poke(false.B)
      c.clock.step()
      c.io.retire.valid.expect(true.B)
      c.io.retire.pc.expect(0.U)
      c.io.retire.insn.expect(0x00000013L.U)
    }
  }
}
