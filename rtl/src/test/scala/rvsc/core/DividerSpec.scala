package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the divider. Every case drives the ports the way Core
// does: req.valid = 1 with the operands; after the fire cycle the operands
// change to garbage (they must have been stored); resp.ready = 1; the
// result is checked in the cycle resp.valid = 1; then the instruction leaves.
//   1. Special cases of the spec: divide by zero, signed overflow.
//   2. Sign combinations and small values, against a BigInt model.
//   3. Random values.
//   4. Latency of a normal divide: always the same, 34 cycles in EX.
//   5. Handshake: req.ready only in IDLE; no second fire while busy.
//   6. Two divides back to back.
//   7. resp.ready = 0 (EX held by MEM): POST keeps the result.
//   8. kill in the middle: the next divide starts from scratch.
class DividerSpec extends AnyFlatSpec with ChiselScalatestTester {

  val M32 = BigInt(1) << 32
  def u(x: Long): BigInt = BigInt(x) & (M32 - 1)
  def s(x: Long): BigInt = { val v = u(x); if (v >= (M32 >> 1)) v - M32 else v }
  def to32(v: BigInt): BigInt = v & (M32 - 1)

  // RISC-V semantics. BigInt's / and % round toward zero, like the ISA.
  def model(signed: Boolean, a: Long, b: Long): (BigInt, BigInt) = {
    if (!signed) {
      if (u(b) == 0) (M32 - 1, u(a)) else (u(a) / u(b), u(a) % u(b))
    } else {
      if (s(b) == 0) (M32 - 1, u(a))
      else if (s(a) == -(M32 >> 1) && s(b) == -1) (to32(-(M32 >> 1)), BigInt(0))
      else (to32(s(a) / s(b)), to32(s(a) % s(b)))
    }
  }

  val CYCLES_IN_EX = 34                    // normal divide: fire cycle .. POST cycle
  val MAX_WAIT     = 40

  def idle(c: Divider): Unit = {
    c.io.req.valid.poke(false.B); c.io.resp.ready.poke(true.B); c.io.kill.poke(false.B)
  }

  // One divide as Core does it. Returns the number of cycles in EX
  // (from the fire cycle to the POST cycle, both included).
  def run(c: Divider, signed: Boolean, a: Long, b: Long): Int = {
    val name = f"${if (signed) "signed" else "unsigned"} a=0x$a%08x b=0x$b%08x"
    c.io.req.valid.poke(true.B); c.io.req.bits.signed.poke(signed.B)
    c.io.req.bits.a.poke(a.U(32.W)); c.io.req.bits.b.poke(b.U(32.W))
    c.io.resp.ready.poke(true.B); c.io.kill.poke(false.B)
    c.io.req.ready.expect(true.B, s"$name: must be IDLE when a divide arrives")
    var n = 1
    while (!c.io.resp.valid.peek().litToBoolean) {
      assert(n < MAX_WAIT, s"$name: no result after $MAX_WAIT cycles")
      c.clock.step(); n += 1
      // the operands must have been stored in the fire cycle
      c.io.req.bits.a.poke(0xdeadbeefL.U(32.W)); c.io.req.bits.b.poke(0x12345678L.U(32.W))
      c.io.req.bits.signed.poke((!signed).B)
      if (!c.io.resp.valid.peek().litToBoolean)
        c.io.req.ready.expect(false.B, s"$name: req.ready must be 0 while busy")
    }
    val (q, r) = model(signed, a, b)
    c.io.resp.bits.quot.expect(q.U(32.W), s"$name: quotient")
    c.io.resp.bits.rem.expect(r.U(32.W), s"$name: remainder")
    c.io.req.ready.expect(false.B, s"$name: req.ready must be 0 in POST")
    c.clock.step()                         // resp fires; the instruction leaves EX
    idle(c)
    c.io.resp.valid.expect(false.B, s"$name: POST must end after resp.fire")
    c.io.req.ready.expect(true.B, s"$name: back to IDLE")
    c.clock.step()
    n
  }

  "Divider" should "handle divide by zero and overflow as the spec says" in {
    test(new Divider) { c =>
      idle(c)
      for (signed <- Seq(true, false); a <- Seq(0L, 7L, 0xffffffffL, 0x80000000L)) {
        val n = run(c, signed, a, 0L)
        assert(n <= CYCLES_IN_EX, s"divide by zero took $n cycles")
      }
      run(c, true, 0x80000000L, 0xffffffffL)       // -2^31 / -1
      run(c, false, 0x80000000L, 0xffffffffL)      // not special when unsigned
    }
  }

  it should "get the signs right" in {
    test(new Divider) { c =>
      idle(c)
      val vals = Seq(1L, 2L, 7L, 100L, 0xfffffff9L /* -7 */, 0xfffffffeL /* -2 */,
                     0xffffff9cL /* -100 */, 0x7fffffffL, 0x80000000L, 0xffffffffL)
      for (signed <- Seq(true, false); a <- vals; b <- vals) run(c, signed, a, b)
    }
  }

  it should "match the model on random values" in {
    val rnd = new scala.util.Random(6)
    test(new Divider) { c =>
      idle(c)
      for (_ <- 0 until 300) {
        // mix of full-range and small divisors, so quotients are not always 0
        val b = if (rnd.nextBoolean()) rnd.nextLong() & 0xffffffffL else (rnd.nextInt(100) + 1).toLong
        run(c, rnd.nextBoolean(), rnd.nextLong() & 0xffffffffL, b)
      }
    }
  }

  it should s"spend exactly $CYCLES_IN_EX cycles in EX for a normal divide" in {
    test(new Divider) { c =>
      idle(c)
      for ((signed, a, b) <- Seq((false, 100L, 7L), (true, 0xffffff9cL, 7L),
                                 (false, 0xffffffffL, 1L), (true, 0x80000000L, 0xffffffffL))) {
        val n = run(c, signed, a, b)
        assert(n == CYCLES_IN_EX, f"a=0x$a%08x b=0x$b%08x: $n cycles, expected $CYCLES_IN_EX")
      }
    }
  }

  it should "start a new divide right after the previous one" in {
    test(new Divider) { c =>
      idle(c)
      c.io.req.valid.poke(true.B); c.io.req.bits.signed.poke(false.B)
      c.io.req.bits.a.poke(100.U); c.io.req.bits.b.poke(7.U)
      while (!c.io.resp.valid.peek().litToBoolean) c.clock.step()
      c.io.resp.bits.quot.expect(14.U)
      c.clock.step()
      // next cycle: a new divide is already in EX (req.valid stays 1, new operands)
      c.io.req.bits.a.poke(100.U); c.io.req.bits.b.poke(9.U)
      c.io.req.ready.expect(true.B, "IDLE again, ready for the new divide")
      c.io.resp.valid.expect(false.B, "the old result must not be reused")
      while (!c.io.resp.valid.peek().litToBoolean) c.clock.step()
      c.io.resp.bits.quot.expect(11.U); c.io.resp.bits.rem.expect(1.U)
    }
  }

  it should "hold the result in POST while resp.ready = 0" in {
    test(new Divider) { c =>
      idle(c)
      c.io.req.valid.poke(true.B); c.io.req.bits.signed.poke(true.B)
      c.io.req.bits.a.poke(0xffffff9cL.U(32.W)); c.io.req.bits.b.poke(7.U)   // -100 / 7
      c.io.resp.ready.poke(false.B)          // EX is held by MEM
      while (!c.io.resp.valid.peek().litToBoolean) c.clock.step()
      for (_ <- 0 until 5) {
        c.io.resp.bits.quot.expect(0xfffffff2L.U(32.W))     // -14
        c.io.resp.bits.rem.expect(0xfffffffeL.U(32.W))      // -2
        c.io.req.ready.expect(false.B, "must not take the same divide again")
        c.clock.step()
        c.io.resp.valid.expect(true.B, "POST must hold until resp.ready")
      }
      c.io.resp.ready.poke(true.B)
      c.clock.step()
      c.io.req.valid.poke(false.B)
      c.io.resp.valid.expect(false.B)
      c.io.req.ready.expect(true.B)
    }
  }

  it should "go back to IDLE on kill and start the next divide from scratch" in {
    test(new Divider) { c =>
      idle(c)
      c.io.req.valid.poke(true.B); c.io.req.bits.signed.poke(false.B)
      c.io.req.bits.a.poke(100.U); c.io.req.bits.b.poke(7.U)
      c.clock.step(5)                                    // in CALC
      c.io.kill.poke(true.B); c.io.req.valid.poke(false.B)  // a trap clears EX
      c.clock.step()
      c.io.kill.poke(false.B)
      c.io.req.ready.expect(true.B, "IDLE after kill")
      c.io.resp.valid.expect(false.B)
      c.clock.step()
      val n = run(c, false, 1000L, 3L)
      assert(n == CYCLES_IN_EX, s"the divide after kill took $n cycles: it reused old state")
    }
  }
}
