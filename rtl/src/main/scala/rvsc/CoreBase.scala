package rvsc

import chisel3._

// Every core that SimTop can hold extends this class, so the testbench
// can run the same flow with StubCore (to check the testbench itself)
// or with the real pipelined core.
abstract class CoreBase extends Module {
  val io = IO(new CoreIO)
}
