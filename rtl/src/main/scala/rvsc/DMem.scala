package rvsc

import chisel3._
import chisel3.util._

// Data SRAM: one single-port RAM shared by two users.
//
//   hostEn = 1 : the testbench owns the RAM. If load.valid is 1 it writes
//                one word; otherwise it reads dumpAddr, and dumpData shows
//                the word one cycle later. The core sees ready = 0.
//   hostEn = 0 : the core owns the RAM and does loads and stores.
//
// The testbench sets hostEn = 1 to load the ELF (with reset held) and again
// after the program writes tohost, to read the result. During reset the
// core port is also not ready.
//
// Only the low address bits are used. The core never sends an address
// outside DMEM (it checks first), and SimTop only routes DMEM loads here.
class DMem(words: Int = (MemMap.DmemSize / 4).toInt) extends Module {
  val io = IO(new Bundle {
    val core     = Flipped(new DmemPort)
    val hostEn   = Input(Bool())
    val load     = Flipped(Valid(new LoadReq))
    val dumpAddr = Input(UInt(32.W))
    val dumpData = Output(UInt(32.W))
  })

  val ram     = Module(new SyncRam(words))
  val idxBits = log2Ceil(words)
  def index(addr: UInt): UInt = addr(idxBits + 1, 2)   // byte address -> word index

  // Core side. A request is taken when valid and ready are both 1.
  io.core.req.ready := !io.hostEn && !reset.asBool
  val coreFire = io.core.req.valid && io.core.req.ready

  when(io.hostEn) {
    // Testbench: write a whole word if load.valid, else read dumpAddr.
    ram.io.en    := true.B
    ram.io.we    := io.load.valid
    ram.io.wstrb := "b1111".U
    ram.io.addr  := Mux(io.load.valid, index(io.load.bits.addr), index(io.dumpAddr))
    ram.io.wdata := io.load.bits.data
  }.otherwise {
    // Core: load (wen = 0) or store (wen = 1, only the bytes in wstrb).
    ram.io.en    := coreFire
    ram.io.we    := io.core.req.bits.wen
    ram.io.wstrb := io.core.req.bits.wstrb
    ram.io.addr  := index(io.core.req.bits.addr)
    ram.io.wdata := io.core.req.bits.wdata
  }

  // Every taken request (load or store) gets one answer in the next cycle.
  // For a store the answer only says "done"; rdata has no meaning.
  io.core.resp.valid      := RegNext(coreFire, false.B)
  io.core.resp.bits.rdata := ram.io.rdata

  io.dumpData := ram.io.rdata
}
