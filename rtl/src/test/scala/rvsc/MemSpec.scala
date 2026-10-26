package rvsc

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for SyncRam, IMem and DMem. Small sizes keep the tests fast;
// the logic does not depend on the size.
class MemSpec extends AnyFlatSpec with ChiselScalatestTester {

  // ----------------------------------------------------------------- SyncRam

  "SyncRam" should "return read data one cycle later" in {
    test(new SyncRam(64)) { c =>
      // Write 0x11223344 to word 5.
      c.io.en.poke(true.B); c.io.we.poke(true.B); c.io.wstrb.poke("b1111".U)
      c.io.addr.poke(5.U);  c.io.wdata.poke("h11223344".U)
      c.clock.step()
      // Read word 5: the data is there after the next clock edge.
      c.io.we.poke(false.B)
      c.clock.step()
      c.io.rdata.expect("h11223344".U)
    }
  }

  it should "write only the bytes in wstrb" in {
    test(new SyncRam(64)) { c =>
      def write(a: Int, strb: String, d: String): Unit = {
        c.io.en.poke(true.B); c.io.we.poke(true.B)
        c.io.wstrb.poke(strb.U); c.io.addr.poke(a.U); c.io.wdata.poke(d.U)
        c.clock.step()
      }
      def read(a: Int): BigInt = {
        c.io.en.poke(true.B); c.io.we.poke(false.B); c.io.addr.poke(a.U)
        c.clock.step()
        c.io.rdata.peek().litValue
      }
      write(3, "b1111", "h11223344")
      write(3, "b0100", "h00ab0000")       // like sb to byte 2
      assert(read(3) == BigInt("11ab3344", 16))
      write(3, "b0011", "h0000cdef")       // like sh to bytes 0..1
      assert(read(3) == BigInt("11abcdef", 16))
    }
  }

  // -------------------------------------------------------------------- IMem

  "IMem" should "take ELF words from the host, then answer core fetches" in {
    test(new IMem(64)) { c =>
      // Host mode: the core is not ready; load two words.
      c.io.hostEn.poke(true.B)
      c.io.core.req.valid.poke(true.B)
      c.io.core.req.ready.expect(false.B)
      c.io.load.valid.poke(true.B)
      c.io.load.bits.addr.poke("h00000000".U); c.io.load.bits.data.poke("h00000013".U)
      c.clock.step()
      c.io.load.bits.addr.poke("h00000004".U); c.io.load.bits.data.poke("h00100093".U)
      c.clock.step()
      c.io.load.valid.poke(false.B)

      // Core mode: fetch address 4, the answer comes one cycle later.
      c.io.hostEn.poke(false.B)
      c.io.core.req.ready.expect(true.B)
      c.io.core.req.bits.addr.poke("h00000004".U)
      c.io.core.resp.valid.expect(false.B)
      c.clock.step()
      c.io.core.req.valid.poke(false.B)
      c.io.core.resp.valid.expect(true.B)
      c.io.core.resp.bits.inst.expect("h00100093".U)
      c.clock.step()
      c.io.core.resp.valid.expect(false.B)   // no request, no answer
    }
  }

  it should "not be ready during reset" in {
    test(new IMem(64)) { c =>
      c.io.hostEn.poke(false.B)
      c.reset.poke(true.B)
      c.io.core.req.ready.expect(false.B)
      c.reset.poke(false.B)
      c.io.core.req.ready.expect(true.B)
    }
  }

  // -------------------------------------------------------------------- DMem

  // Helpers for the DMem tests.
  def hostWrite(c: DMem, addr: String, data: String): Unit = {
    c.io.hostEn.poke(true.B)
    c.io.load.valid.poke(true.B)
    c.io.load.bits.addr.poke(addr.U); c.io.load.bits.data.poke(data.U)
    c.clock.step()
    c.io.load.valid.poke(false.B)
  }
  def dump(c: DMem, addr: String): BigInt = {
    c.io.hostEn.poke(true.B)
    c.io.load.valid.poke(false.B)
    c.io.dumpAddr.poke(addr.U)
    c.clock.step()
    c.io.dumpData.peek().litValue
  }
  def coreReq(c: DMem, addr: String, wen: Boolean, strb: String, data: String): Unit = {
    c.io.core.req.valid.poke(true.B)
    c.io.core.req.bits.addr.poke(addr.U)
    c.io.core.req.bits.wen.poke(wen.B)
    c.io.core.req.bits.wstrb.poke(strb.U)
    c.io.core.req.bits.wdata.poke(data.U)
  }

  "DMem" should "load from the host, run core loads and stores, then dump" in {
    test(new DMem(64)) { c =>
      hostWrite(c, "h10000008", "h11223344")

      // Core mode.
      c.io.hostEn.poke(false.B)
      // Store byte 0xab to address ...0a (byte 2 of word 2).
      coreReq(c, "h1000000a", true, "b0100", "h00ab0000")
      c.clock.step()
      c.io.core.resp.valid.expect(true.B)        // a store also gets an answer
      // Load word 2.
      coreReq(c, "h10000008", false, "b0000", "h0")
      c.clock.step()
      c.io.core.req.valid.poke(false.B)
      c.io.core.resp.valid.expect(true.B)
      c.io.core.resp.bits.rdata.expect("h11ab3344".U)

      // Host mode again: dump word 2.
      assert(dump(c, "h10000008") == BigInt("11ab3344", 16))
    }
  }

  it should "finish the last core request when hostEn turns on" in {
    test(new DMem(64)) { c =>
      hostWrite(c, "h10000000", "h0000aaaa")
      hostWrite(c, "h10000004", "h0000bbbb")

      // Cycle t: the core reads word 0 and the request is taken.
      c.io.hostEn.poke(false.B)
      coreReq(c, "h10000000", false, "b0000", "h0")
      c.io.core.req.ready.expect(true.B)
      c.clock.step()

      // Cycle t+1: hostEn turns on. The core still gets its answer,
      // and the host can already start a dump read of word 1.
      c.io.hostEn.poke(true.B)
      c.io.dumpAddr.poke("h10000004".U)
      c.io.core.req.ready.expect(false.B)
      c.io.core.resp.valid.expect(true.B)
      c.io.core.resp.bits.rdata.expect("h0000aaaa".U)
      c.clock.step()

      // Cycle t+2: the dump data arrives; no more core answers.
      c.io.core.resp.valid.expect(false.B)
      c.io.dumpData.expect("h0000bbbb".U)
    }
  }

  // --------------------------------------------------------- Verilog shape

  "SyncRam" should "become Verilog that reads with a registered address" in {
    // This is the pattern FPGA tools map to block RAM. The file is kept in
    // test_run_dir/SyncRam_verilog so you can read it.
    val v = (new ChiselStage).emitVerilog(new SyncRam(1024),
      Array("--target-dir", "test_run_dir/SyncRam_verilog"))
    assert(v.contains("reg [7:0] mem_0 [0:1023]"))     // one array per byte
    assert(v.contains("_addr_pipe_0"))                 // registered read address
  }
}
