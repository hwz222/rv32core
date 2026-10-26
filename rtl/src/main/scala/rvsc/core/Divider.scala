package rvsc

import chisel3._
import chisel3.util._

// 32-bit divider for div, divu, rem, remu. Multi-cycle: one
// restoring-division step per cycle, so the instruction waits in EX while
// the result is not ready (the hazard unit holds IF, ID and EX and sends a
// bubble to MEM). No Chisel `/` or `%`.
//
// Interface with Core (EX). Both sides use Decoupled: a transfer happens
// in a cycle where valid && ready ("fire").
//   req   Core -> Divider. valid = a divide is in EX (stays 1 while it waits).
//         ready = the divider is IDLE. bits are read ONLY in the fire cycle
//         and kept inside: the instructions in MEM and WB that a, b may be
//         forwarded from move on while the divide waits.
//   resp  Divider -> Core. valid = the result is ready (state POST); both
//         the quotient and the remainder, Core picks one.
//         ready = EX moves on this cycle (Core drops it while
//         MEM holds EX). POST holds the result until it is taken.
//   kill  a trap clears EX; go back to IDLE from any state.
//
// Core computes busyE = req.valid && !resp.valid for the hazard unit.
//
// States and timing of a normal divide (34 cycles in EX, 33 of them stalled):
//   cycle   state   req.ready  resp.valid  what happens
//   1       IDLE    1          0           fire: store |a|, |b| and the signs
//   2..33   CALC    0          0           one step per cycle, count 0 .. 31
//   34      POST    0          1           output with the signs fixed
//   35      IDLE    1          0           the next instruction is in EX
// Divide by zero: IDLE (fire) stores the answer and goes straight to POST.
//
// Restoring division (unsigned), one step, MSB of the dividend first:
//   r = (r << 1) | next dividend bit
//   if (r >= b) { r = r - b; quotient bit = 1 } else { quotient bit = 0 }
// 4-bit example, 13 / 3:
//   step  bit  r after shift  r >= 3?  r after  q
//   1     1    0001           no       0001     0
//   2     1    0011           yes      0000     01
//   3     0    0000           no       0000     010
//   4     1    0001           no       0001     0100   -> q = 4, r = 1
//
// Signed (DIV, REM; the ISA rounds toward zero): divide |a| by |b|, then
//   quotient is negative when the signs of a and b differ,
//   remainder has the sign of a.
//   Example: -7 / 2 = -3, remainder -1.
//
// Special cases (RISC-V spec, no exception):
//   b = 0                    quotient = all ones, remainder = a
//   a = -2^31, b = -1 (DIV)  quotient = -2^31, remainder = 0
//   The second needs no special code: |-2^31| is 0x80000000 as an unsigned
//   number, 0x80000000 / 1 = 0x80000000, and negating it gives the same bits.

// Request: what Core sends when a divide enters EX.
class DivReq extends Bundle {
  val a      = UInt(Consts.XLEN.W)       // dividend (rs1FwdE)
  val b      = UInt(Consts.XLEN.W)       // divisor  (rs2FwdE)
  val signed = Bool()                    // DIV, REM: true; DIVU, REMU: false
}

// Response: both results; Core picks one with mulDivOpE.
class DivResp extends Bundle {
  val quot = UInt(Consts.XLEN.W)         // quotient
  val rem  = UInt(Consts.XLEN.W)         // remainder
}

class Divider extends Module {
  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new DivReq))  // fire: taken in IDLE
    val resp = Decoupled(new DivResp)          // fire: result taken in POST
    val kill = Input(Bool())                   // back to IDLE now
  })

  // ChiselEnum values are 0, 1, 2 in this order.
  object State extends ChiselEnum {
    val IDLE, CALC, POST = Value
  }
  val state = RegInit(State.IDLE)

  // One restoring step. Pure Scala function: calling it builds the hardware
  // of one step (no registers inside). CALC calls it once per cycle; a
  // faster divider (radix-4: two steps per cycle) would only change this.
  //
  // a and the quotient share one register: each step a(31) leaves on the
  // left (brought down into r) and the new quotient bit enters on the right.
  //   step 0 : a = [a31 a30 ... a1 a0]
  //   step 1 : a = [a30 ... a1 a0 q31]
  //   step 32: a = [q31 q30 ... q1 q0]      = the quotient
  //
  //   r    partial remainder (32 bits: after a step r < b always fits)
  //   a    dividend bits not yet brought down (left) + quotient bits (right)
  //   b    divisor (32 bits)
  //   returns (new r, new a)
  def step(r: UInt, a: UInt, b: UInt): (UInt, UInt) = {
    // bring down a(31); subtract when it fits; the quotient bit enters a on the right
    val shifted = (r<<1) | a(31) // 33 bits
    val bge = shifted >= b 
    val newR = Mux(bge , (shifted - b)(31, 0) , shifted(31, 0))
    val newA = Cat(a(30, 0), bge.asUInt) 
    (newR, newA)
  }

  // Registers kept while dividing (no separate quotient register)
  //   r       32 bits   partial remainder; the remainder after 32 steps
  //   a       32 bits   |dividend| at the start; the quotient after 32 steps
  //   b       32 bits   |divisor|
  //   count   5 bits    step 0 .. 31
  //   negQuot, negRem   Bool, the signs to apply in POST
  val r = Reg(UInt(Consts.XLEN.W))
  val a = Reg(UInt(Consts.XLEN.W))
  val b = Reg(UInt(Consts.XLEN.W))
  val count = Reg(UInt(5.W))
  val negQuot = Reg(Bool())
  val negRem = Reg(Bool())
  
  def abs(x: UInt, signed: Bool): UInt = Mux(signed && x(31), 0.U - x, x)
  def negIf(x: UInt, neg: Bool): UInt = Mux(neg, 0.U - x, x)

  switch(state) {
    is(State.IDLE){
      when(io.req.fire){
        val req = io.req.bits
        a := abs(req.a, req.signed)
        b := abs(req.b, req.signed)
        r := 0.U
        count := 0.U
        negQuot := req.signed && (req.a(31) ^ req.b(31))
        negRem := req.signed && req.a(31)
        state := State.CALC
        when(req.b === 0.U){
          a := ~0.U(Consts.XLEN.W)
          r := req.a
          negQuot := false.B
          negRem := false.B
          state := State.POST
        }
      }
    }
    is(State.CALC){
      val (newR, newA) = step(r, a, b)
      r := newR
      a := newA
      count := count + 1.U
      when(count === 31.U) { state := State.POST}
    }
    is(State.POST){
      when(io.resp.fire) { state := State.IDLE}
    }
  }
  when(io.kill) { state := State.IDLE}
  
  io.req.ready      := state === State.IDLE
  io.resp.valid     := state === State.POST
  io.resp.bits.quot := negIf(a, negQuot)
  io.resp.bits.rem  := negIf(r, negRem)
}