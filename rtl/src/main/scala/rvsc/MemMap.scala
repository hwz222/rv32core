package rvsc

import chisel3._

// Memory map of the core. These numbers must match
// workload/common/link.ld, isa_sim/src/memmap.h and rtl/DESIGN.md 3.1.
// If you change one, change all of them.
object MemMap {
  // Instruction scratchpad. Only instruction fetch can read it.
  val ImemBase: BigInt = 0x00000000L
  val ImemSize: BigInt = 64 * 1024

  // Data SRAM. Every load and store goes here.
  val DmemBase: BigInt = 0x10000000L
  val DmemSize: BigInt = 2 * 1024 * 1024

  // Address bits used inside each region (16 for IMEM, 21 for DMEM).
  val ImemAddrBits: Int = log2Exact(ImemSize)
  val DmemAddrBits: Int = log2Exact(DmemSize)

  // True when addr is inside [base, base + size).
  // base is a multiple of size, so only the high bits need to match.
  // Example: DMEM is 2^21 bytes at 0x1000_0000, so the test is
  // addr(31, 21) === 0x080. One small comparator, no subtract.
  def inImem(addr: UInt): Bool = addr(31, ImemAddrBits) === (ImemBase >> ImemAddrBits).U
  def inDmem(addr: UInt): Bool = addr(31, DmemAddrBits) === (DmemBase >> DmemAddrBits).U

  // Word index inside a region, used as the SRAM address.
  // Example: dmemIndex(0x1000_0008) = 2.
  def imemIndex(addr: UInt): UInt = addr(ImemAddrBits - 1, 2)
  def dmemIndex(addr: UInt): UInt = addr(DmemAddrBits - 1, 2)

  // Checked when the hardware is built, not at run time.
  private def log2Exact(x: BigInt): Int = {
    require(x > 0 && x.bitCount == 1, s"$x is not a power of two")
    x.bitLength - 1
  }
  require(ImemBase % ImemSize == 0, "IMEM base must be a multiple of its size")
  require(DmemBase % DmemSize == 0, "DMEM base must be a multiple of its size")
}
