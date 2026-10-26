package rvsc

import java.nio.file.{Files, Paths}

import chisel3.stage.ChiselStage

// Writes the Verilog of SimTop to rtl/generated/SimTop.v, and the name of
// the core to rtl/generated/SimTop.core (run_rtl_sim.sh reads it to know
// whether SimTop.v holds the core it wants).
//
//   cd rtl
//   sbt "runMain rvsc.Main"         # default core
//   sbt "runMain rvsc.Main stub"    # StubCore
//   sbt "runMain rvsc.Main core"    # the pipelined core
//
// The default is "core", here and in run_rtl_sim.sh (RTL_CORE). "stub" is kept
// to test the testbench alone.
object Main extends App {
  val which = args.headOption.getOrElse("core")

  def gen(): CoreBase = which match {
    case "stub" => new StubCore
    case "core" => new Core                 // the five-stage pipeline
    case other  => throw new IllegalArgumentException(s"unknown core: $other")
  }

  (new ChiselStage).emitVerilog(new SimTop(gen()), Array("--target-dir", "generated"))
  Files.write(Paths.get("generated", "SimTop.core"), which.getBytes("UTF-8"))
  println(s"Verilog written to generated/SimTop.v (core: $which)")
}
