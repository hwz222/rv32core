package rvsc

import chisel3._

// Simulation top level: one core, IMEM and DMEM. The Verilator testbench
// drives only the ports in SimTopIO.
//
// Parameters:
//   coreGen    which core to build (StubCore or the real core)
//   imemWords  IMEM size in 32-bit words (default: full IMEM from MemMap)
//   dmemWords  DMEM size in 32-bit words (default: full DMEM from MemMap)
// Smaller sizes are only for fast unit tests (and maybe a small FPGA).
class SimTop(coreGen: => CoreBase,
             imemWords: Int = (MemMap.ImemSize / 4).toInt,
             dmemWords: Int = (MemMap.DmemSize / 4).toInt) extends Module {
  val io = IO(new SimTopIO)

  val core = Module(coreGen)
  val imem = Module(new IMem(imemWords))
  val dmem = Module(new DMem(dmemWords))

  // ---- Core <-> memories ---------------------------------------------------
  core.io.bootAddr := io.bootAddr
  core.io.irq      := io.irq
  imem.io.core     <> core.io.imem
  dmem.io.core     <> core.io.dmem

  // ---- Testbench -> memories -----------------------------------------------
  imem.io.hostEn := io.hostEn
  dmem.io.hostEn := io.hostEn

  // A load word goes to the memory whose region holds its address. A word
  // outside both regions is dropped (the testbench checks the ELF segments
  // first, so it should not send one).
  imem.io.load.valid := io.load.valid && MemMap.inImem(io.load.bits.addr)
  imem.io.load.bits  := io.load.bits
  dmem.io.load.valid := io.load.valid && MemMap.inDmem(io.load.bits.addr)
  dmem.io.load.bits  := io.load.bits

  dmem.io.dumpAddr := io.dumpAddr
  io.dumpData      := dmem.io.dumpData

  // ---- Core -> testbench ---------------------------------------------------
  io.retire := core.io.retire
  io.events := core.io.events
}
