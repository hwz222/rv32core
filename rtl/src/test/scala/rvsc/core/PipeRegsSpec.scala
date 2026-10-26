package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the pipeline registers: the PipeReg module
// with each Bundle. It must start as a bubble, take the next value, keep its
// value on stall, and become a bubble on flush (flush wins over stall).
class PipeRegsSpec extends AnyFlatSpec with ChiselScalatestTester {

  "PipeReg(IdEx)" should "start as a bubble, hold on stall, clear on flush" in {
    test(new PipeReg(new IdEx)) { c =>
      c.io.out.valid.expect(false.B)                       // bubble after reset
      c.io.out.ctrl.regWrite.expect(false.B)
      c.io.out.ctrl.aluOp.expect(AluOp.ADD)                // enum fields start at 0 too

      // take an instruction
      c.io.stall.poke(false.B); c.io.flush.poke(false.B)
      c.io.in.valid.poke(true.B); c.io.in.pc.poke(0x100.U); c.io.in.rd.poke(5.U)
      c.io.in.ctrl.regWrite.poke(true.B); c.io.in.ctrl.aluOp.poke(AluOp.SUB)
      c.clock.step()
      c.io.out.valid.expect(true.B); c.io.out.pc.expect(0x100.U); c.io.out.rd.expect(5.U)
      c.io.out.ctrl.regWrite.expect(true.B); c.io.out.ctrl.aluOp.expect(AluOp.SUB)

      // stall: keep it although the input changes
      c.io.stall.poke(true.B); c.io.in.pc.poke(0x200.U)
      c.clock.step()
      c.io.out.pc.expect(0x100.U)

      // flush and stall together: a bubble, every field 0
      c.io.flush.poke(true.B)
      c.clock.step()
      c.io.out.valid.expect(false.B)
      c.io.out.pc.expect(0.U)
      c.io.out.ctrl.regWrite.expect(false.B)
    }
  }

  "PipeReg" should "work with the other three Bundles" in {
    test(new PipeReg(new IfId)) { c =>
      c.io.stall.poke(false.B); c.io.flush.poke(false.B)
      c.io.in.valid.poke(true.B); c.io.in.pc.poke(0x80.U)
      c.clock.step()
      c.io.out.valid.expect(true.B); c.io.out.pc.expect(0x80.U)
    }
    test(new PipeReg(new ExMem)) { c =>
      c.io.stall.poke(false.B); c.io.flush.poke(false.B)
      c.io.in.valid.poke(true.B); c.io.in.aluOut.poke(0x1234.U); c.io.in.wbSel.poke(WbSel.Load)
      c.clock.step()
      c.io.out.aluOut.expect(0x1234.U); c.io.out.wbSel.expect(WbSel.Load)
    }
    test(new PipeReg(new MemWb)) { c =>
      c.io.stall.poke(false.B); c.io.flush.poke(false.B)
      c.io.in.valid.poke(true.B); c.io.in.rd.poke(7.U); c.io.in.regWrite.poke(true.B)
      c.clock.step()
      c.io.out.rd.expect(7.U); c.io.out.regWrite.expect(true.B)
      c.io.out.trap.expect(false.B)
      // a trap record
      c.io.in.regWrite.poke(false.B); c.io.in.trap.poke(true.B); c.io.in.cause.poke(2.U)
      c.io.in.tval.poke(0x12345678L.U(32.W)); c.io.in.trapTarget.poke(0x400.U)
      c.clock.step()
      c.io.out.trap.expect(true.B); c.io.out.cause.expect(2.U)
      c.io.out.tval.expect(0x12345678L.U(32.W)); c.io.out.trapTarget.expect(0x400.U)
      c.io.out.regWrite.expect(false.B)
    }
  }

  "The four Bundles" should "have the widths of the design" in {
    // IF/ID: valid + pc = 1 + 32 (the instruction comes from IMEM in ID)
    assert((new IfId).getWidth == 33)
    // ID/EX and EX/MEM carry an exception; EX/MEM carries mret;
    // MEM/WB carries the trap taken in MEM
    assert((new IdEx).elements.contains("excTval"))
    assert((new ExMem).elements.contains("excTval"))
    assert((new ExMem).elements.contains("mret"))
    // EX/MEM carries the A extension kind; MEM/WB the address for the retire record
    Seq("lr", "sc", "amo", "amoOp").foreach(f => assert((new ExMem).elements.contains(f), f))
    assert((new MemWb).elements.contains("memAddr"))
    assert((new MemWb).elements.contains("trapTarget"))
    assert(!(new MemWb).elements.contains("excTval"))
  }
}
