package rvsc

import chisel3._
import chisel3.util._

// A fake core that does not execute anything. It is kept on purpose: it
// lets us check the testbench, the memories and the scripts without a
// real core. If a testbench problem shows up later, run with StubCore
// to see whether the core is to blame.
//
// What it does:
//   - starts at bootAddr and sends one fetch request per cycle (pc += 4)
//   - for every answer, sends one retire record with only pc and insn
//     (no register write, no memory access, no trap)
//   - stops fetching after the last IMEM word, so it never sends an
//     address outside IMEM
//   - never uses DMEM and never writes tohost, so a run with StubCore
//     always ends by timeout (testbench exit code 4)
class StubCore extends CoreBase {
  val lastAddr = (MemMap.ImemBase + MemMap.ImemSize - 4).U(32.W)

  // ---- State ---------------------------------------------------------------
  val pc   = RegInit(io.bootAddr)        // loaded from bootAddr during reset
  val done = RegInit(false.B)            // 1 after the last IMEM word is fetched

  // ---- Fetch: one request per cycle while the memory is ready -------------
  io.imem.req.valid     := !done
  io.imem.req.bits.addr := pc

  val fire = io.imem.req.valid && io.imem.req.ready
  when(fire) {
    pc := pc + 4.U
    when(pc === lastAddr) { done := true.B }
  }

  // The answer comes one cycle after the request, when pc has already
  // moved on, so keep the pc of the request that was taken.
  val respPc = RegEnable(pc, fire)

  // ---- Retire: one record per answer, other fields 0 ----------------------
  io.retire       := 0.U.asTypeOf(new RetireIO)
  io.retire.valid := io.imem.resp.valid
  io.retire.pc    := respPc
  io.retire.insn  := io.imem.resp.bits.inst

  // ---- Data memory: never used --------------------------------------------
  io.dmem.req.valid := false.B
  io.dmem.req.bits  := 0.U.asTypeOf(new DmemReq)

  // ---- Events: only "IF has an instruction" -------------------------------
  io.events               := 0.U.asTypeOf(new PerfEvents)
  io.events.stageValid(0) := io.imem.resp.valid
}
