package rvsc

import chisel3._
import chisel3.util._

// Port of a single-port RAM, in the style of an FPGA block RAM:
// one address, write enable, byte enables, write data and read data.
class SyncRamIO(words: Int) extends Bundle {
  val en    = Input(Bool())                     // do a read or a write this cycle
  val we    = Input(Bool())                     // 1 = write, 0 = read
  val wstrb = Input(UInt(4.W))                  // byte enables for a write
  val addr  = Input(UInt(log2Ceil(words).W))    // word index, not byte address
  val wdata = Input(UInt(32.W))
  val rdata = Output(UInt(32.W))                // valid one cycle after a read
}

// A 32-bit wide, single-port RAM with byte write enables.
//
// - Read is synchronous: data for a read in cycle t shows in cycle t+1.
//   This is what lets synthesis tools map it to block RAM.
// - Each word is stored as 4 bytes, so a write can change only some bytes
//   (sb, sh). Chisel may split this into 4 byte-wide arrays; each one is
//   still a block RAM.
// - There is no reset on the contents. The testbench loads them.
// - rdata is only defined in the cycle after a read. Do not use it at
//   other times.
//
// All memories in the design use this module, so a vendor RAM (for
// example a Xilinx XPM BlackBox) can later be swapped in here only.
class SyncRam(val words: Int) extends Module {
  require(words > 0 && isPow2(words), s"words = $words must be a power of two")
  val io = IO(new SyncRamIO(words))

  val mem = SyncReadMem(words, Vec(4, UInt(8.W)))

  // Split the 32-bit write data into 4 bytes: byte i = wdata(8i+7, 8i).
  val wbytes = VecInit(Seq.tabulate(4)(i => io.wdata(8 * i + 7, 8 * i)))
  when(io.en && io.we) {
    mem.write(io.addr, wbytes, io.wstrb.asBools)
  }

  // Read when enabled and not writing (one port: read or write, not both).
  io.rdata := mem.read(io.addr, io.en && !io.we).asUInt
}
