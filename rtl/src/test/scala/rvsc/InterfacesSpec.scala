package rvsc

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Small module that only exposes the MemMap checks, so we can test them.
class MemMapProbe extends Module {
  val io = IO(new Bundle {
    val addr   = Input(UInt(32.W))
    val inImem = Output(Bool())
    val inDmem = Output(Bool())
    val dIndex = Output(UInt(19.W))
  })
  io.inImem := MemMap.inImem(io.addr)
  io.inDmem := MemMap.inDmem(io.addr)
  io.dIndex := MemMap.dmemIndex(io.addr)
}

// Empty modules with the real IO, only to check that the bundles build.
class CoreIOProbe extends Module {
  val io = IO(new CoreIO)
  io := DontCare
}

class SimTopIOProbe extends Module {
  val io = IO(new SimTopIO)
  io := DontCare
}

class InterfacesSpec extends AnyFlatSpec with ChiselScalatestTester {
  "MemMap" should "match isa_sim/src/memmap.h at the region edges" in {
    test(new MemMapProbe) { c =>
      // (address, in IMEM, in DMEM)
      val cases = Seq(
        (0x00000000L, true, false),
        (0x0000fffcL, true, false),   // last IMEM word
        (0x00010000L, false, false),  // one past IMEM
        (0x0fffffffL, false, false),
        (0x10000000L, false, true),   // first DMEM byte
        (0x101ffffcL, false, true),   // last DMEM word
        (0x10200000L, false, false),  // one past DMEM (_stack_top)
        (0xffffffffL, false, false)
      )
      for ((a, i, d) <- cases) {
        c.io.addr.poke(a.U)
        c.io.inImem.expect(i.B, f"inImem($a%08x)")
        c.io.inDmem.expect(d.B, f"inDmem($a%08x)")
      }
      // Word index inside DMEM.
      c.io.addr.poke(0x10000008L.U)
      c.io.dIndex.expect(2.U)
    }
  }

  "CoreIO and SimTopIO" should "build into Verilog ports" in {
    val core = ChiselStage.emitVerilog(new CoreIOProbe)
    val top  = ChiselStage.emitVerilog(new SimTopIOProbe)
    // A few port names the testbench will use.
    assert(core.contains("io_imem_req_bits_addr"))
    assert(core.contains("io_dmem_req_bits_wstrb"))
    assert(top.contains("io_load_valid"))
    assert(top.contains("io_dumpData"))
    assert(top.contains("io_retire_trapTarget"))
    assert(top.contains("io_events_stageValid_4"))
    assert(top.contains("io_irq_mtip"))
    assert(top.contains("io_hostEn"))
    assert(top.contains("io_retire_interrupt"))
  }
}
