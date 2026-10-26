package rvsc

import chisel3._
import chisel3.util._

// Instruction scratchpad: one single-port RAM shared by two users.
//
//   hostEn = 1 : the testbench owns the RAM and writes the ELF through load.
//                The core sees ready = 0, so it waits.
//   hostEn = 0 : the core owns the RAM and fetches through core.
//
// The two users never need the RAM at the same time, so one port is enough.
// During reset the core port is also not ready.
//
// Only the low address bits are used. The core never sends an address
// outside IMEM (it checks first), and SimTop only routes IMEM loads here.
class IMem(words: Int = (MemMap.ImemSize / 4).toInt) extends Module {
  val io = IO(new Bundle {
    val core   = Flipped(new ImemPort)
    val hostEn = Input(Bool())
    val load   = Flipped(Valid(new LoadReq))
  })

  val ram     = Module(new SyncRam(words))
  val idxBits = log2Ceil(words)
  def index(addr: UInt): UInt = addr(idxBits + 1, 2)   // byte address -> word index

  // Core side. A request is taken when valid and ready are both 1.
  io.core.req.ready := !io.hostEn && !reset.asBool
  val coreFire = io.core.req.valid && io.core.req.ready

  when(io.hostEn) {
    // Testbench: write one whole word per cycle while load.valid is 1.
    ram.io.en    := io.load.valid
    ram.io.we    := true.B
    ram.io.wstrb := "b1111".U
    ram.io.addr  := index(io.load.bits.addr)
    ram.io.wdata := io.load.bits.data
  }.otherwise {
    // Core: read only.
    ram.io.en    := coreFire
    ram.io.we    := false.B
    ram.io.wstrb := 0.U
    ram.io.addr  := index(io.core.req.bits.addr)
    ram.io.wdata := 0.U
  }

  // Answer one cycle after the request was taken. The RAM data is valid in
  // that same cycle, even if hostEn has just turned on.
  io.core.resp.valid     := RegNext(coreFire, false.B)
  io.core.resp.bits.inst := ram.io.rdata
}
