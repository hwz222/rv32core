package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the register file. It has registers, so the
// tests step the clock: a write shows up only after c.clock.step().
//   1. All 0 after reset.
//   2. Write then read back, two read ports at once, we = 0 writes nothing.
//   3. Read first, then write: same-cycle read gives the old value.
//   4. x0 stays 0.
//   5. Random reads and writes against a Scala array, cycle by cycle.
class RegFileSpec extends AnyFlatSpec with ChiselScalatestTester {

  val Mask = 0xffffffffL

  def write(c: RegFile, addr: Int, data: Long): Unit = {
    c.io.we.poke(true.B); c.io.waddr.poke(addr.U); c.io.wdata.poke(data.U(32.W))
    c.clock.step()
    c.io.we.poke(false.B)
  }

  def read(c: RegFile, a1: Int, a2: Int, want1: Long, want2: Long, msg: String): Unit = {
    c.io.raddr1.poke(a1.U); c.io.raddr2.poke(a2.U)
    c.io.rdata1.expect(want1.U(32.W), s"$msg: x$a1 on port 1")
    c.io.rdata2.expect(want2.U(32.W), s"$msg: x$a2 on port 2")
  }

  "RegFile" should "read 0 from every register after reset" in {
    test(new RegFile) { c =>
      for (i <- 0 until 32) read(c, i, 31 - i, 0, 0, "after reset")
    }
  }

  it should "keep what was written and read two registers at once" in {
    test(new RegFile) { c =>
      for (i <- 1 until 32) write(c, i, 0x1000L * i + i)
      for (i <- 1 until 32) read(c, i, 32 - i, 0x1000L * i + i, 0x1000L * (32 - i) + (32 - i), "after writes")
      // we = 0: nothing changes even with an address and data
      c.io.we.poke(false.B); c.io.waddr.poke(5.U); c.io.wdata.poke(0xdeadL.U)
      c.clock.step()
      read(c, 5, 5, 0x5005L, 0x5005L, "we = 0")
    }
  }

  it should "give the old value when the same register is written in the same cycle" in {
    test(new RegFile) { c =>
      write(c, 5, 0x11L)
      // cycle N: write 0x99 to x5 and read x5 on both ports
      c.io.we.poke(true.B); c.io.waddr.poke(5.U); c.io.wdata.poke(0x99L.U)
      read(c, 5, 5, 0x11L, 0x11L, "same cycle as the write")
      c.clock.step()
      // cycle N+1: the new value
      c.io.we.poke(false.B)
      read(c, 5, 5, 0x99L, 0x99L, "next cycle")
    }
  }

  it should "keep x0 at 0" in {
    test(new RegFile) { c =>
      write(c, 0, 0xffffffffL)
      read(c, 0, 0, 0, 0, "after writing x0")
      write(c, 1, 0x1234L)                       // a write to x1 still works
      read(c, 0, 1, 0, 0x1234L, "x0 and x1")
    }
  }

  it should "match a Scala array on random reads and writes" in {
    val rnd = new scala.util.Random(10)
    val model = Array.fill(32)(0L)
    test(new RegFile) { c =>
      for (cycle <- 0 until 2000) {
        val we = rnd.nextInt(4) != 0               // write in 3 of 4 cycles
        val wa = rnd.nextInt(32)
        val wd = rnd.nextLong() & Mask
        val a1 = rnd.nextInt(32)
        val a2 = rnd.nextInt(32)
        c.io.we.poke(we.B); c.io.waddr.poke(wa.U); c.io.wdata.poke(wd.U(32.W))
        // reads see the registers before this cycle's write
        read(c, a1, a2, model(a1), model(a2), s"cycle $cycle")
        c.clock.step()
        if (we && wa != 0) model(wa) = wd
      }
    }
  }
}
