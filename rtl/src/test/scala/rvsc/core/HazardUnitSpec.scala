package rvsc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// Tests for the hazard unit. It is combinational: poke,
// then expect. Cases follow rtl/DESIGN.md, section 3.
//   1. Forwarding to EX and to ID, one case per rule.
//   2. The three stalls, plus inputs that must NOT stall.
//   3. Jump in ID and the hold / clear outputs.
//   4. EX busy (busyE, a divide) and how it combines with the rest.
//   5. a trap or mret in MEM (redirectM) clears IF/ID, ID/EX, EX/MEM.
//   6. MEM busy (busyM, an AMO) holds everything before WB, bubble into WB.
//   7. Random inputs against a Scala model of every output.
class HazardUnitSpec extends AnyFlatSpec with ChiselScalatestTester {

  // All inputs of the hazard unit, as plain Scala values.
  case class In(
    rs1D: Int = 0, rs2D: Int = 0, readsRs1D: Boolean = false, readsRs2D: Boolean = false,
    branchD: Boolean = false, jalD: Boolean = false, takenD: Boolean = false,
    rs1E: Int = 0, rs2E: Int = 0, rdE: Int = 0, regWriteE: Boolean = false,
    aluOutNotResultE: Boolean = false, jalrE: Boolean = false,
    rdM: Int = 0, regWriteM: Boolean = false, aluOutNotResultM: Boolean = false,
    rdW: Int = 0, regWriteW: Boolean = false, busyE: Boolean = false,
    redirectM: Boolean = false, busyM: Boolean = false)

  // What every output should be (the rules of rtl/DESIGN.md).
  case class Out(fAD: Int, fBD: Int, fAE: Int, fBE: Int, pcSrcD: Boolean,
                 stallF: Boolean, stallD: Boolean, flushD: Boolean, flushE: Boolean,
                 loadUse: Boolean, branchUse: Boolean,
                 stallE: Boolean, flushM: Boolean, stallM: Boolean, flushW: Boolean)

  val None = 0; val ExMem = 1; val MemWb = 2

  def model(i: In): Out = {
    def fwdE(rs: Int) =
      if (i.regWriteM && i.rdM != 0 && i.rdM == rs) ExMem
      else if (i.regWriteW && i.rdW != 0 && i.rdW == rs) MemWb
      else None
    def fwdD(rs: Int) =
      if (i.regWriteM && !i.aluOutNotResultM && i.rdM != 0 && i.rdM == rs) ExMem
      else if (i.regWriteW && i.rdW != 0 && i.rdW == rs) MemWb
      else None
    val loadUse = i.aluOutNotResultE && i.rdE != 0 &&
      ((i.readsRs1D && i.rdE == i.rs1D) || (i.readsRs2D && i.rdE == i.rs2D))
    val bE = i.branchD && i.regWriteE && i.rdE != 0 && (i.rdE == i.rs1D || i.rdE == i.rs2D)
    val bM = i.branchD && i.aluOutNotResultM && i.rdM != 0 && (i.rdM == i.rs1D || i.rdM == i.rs2D)
    val stall = loadUse || bE || bM
    val hold   = stall || i.busyE || i.busyM         // IF and ID must wait
    val pcSrcD = ((i.branchD && i.takenD) || i.jalD) && !hold
    Out(fwdD(i.rs1D), fwdD(i.rs2D), fwdE(i.rs1E), fwdE(i.rs2E), pcSrcD,
        hold, hold, pcSrcD || i.jalrE || i.redirectM,
        i.jalrE || (stall && !i.busyE && !i.busyM) || i.redirectM,
        loadUse, bE || bM, i.busyE || i.busyM, (i.busyE && !i.busyM) || i.redirectM,
        i.busyM, i.busyM)
  }

  def poke(c: HazardUnit, i: In): Unit = {
    val io = c.io
    io.rs1D.poke(i.rs1D.U); io.rs2D.poke(i.rs2D.U)
    io.readsRs1D.poke(i.readsRs1D.B); io.readsRs2D.poke(i.readsRs2D.B)
    io.branchD.poke(i.branchD.B); io.jalD.poke(i.jalD.B); io.takenD.poke(i.takenD.B)
    io.rs1E.poke(i.rs1E.U); io.rs2E.poke(i.rs2E.U); io.rdE.poke(i.rdE.U)
    io.regWriteE.poke(i.regWriteE.B); io.aluOutNotResultE.poke(i.aluOutNotResultE.B); io.jalrE.poke(i.jalrE.B)
    io.rdM.poke(i.rdM.U); io.regWriteM.poke(i.regWriteM.B); io.aluOutNotResultM.poke(i.aluOutNotResultM.B)
    io.rdW.poke(i.rdW.U); io.regWriteW.poke(i.regWriteW.B)
    io.busyE.poke(i.busyE.B); io.redirectM.poke(i.redirectM.B)
    io.busyM.poke(i.busyM.B)
  }

  // Checks every output against the model; `want` lets a case also state the
  // value it is about, so a wrong model cannot hide a wrong design.
  def check(c: HazardUnit, name: String, i: In)(want: Out => Boolean): Unit = {
    val o = model(i)
    assert(want(o), s"$name: the test case itself is wrong")
    poke(c, i)
    val io = c.io
    io.forwardAD.expect(o.fAD.U, s"$name: forwardAD")
    io.forwardBD.expect(o.fBD.U, s"$name: forwardBD")
    io.forwardAE.expect(o.fAE.U, s"$name: forwardAE")
    io.forwardBE.expect(o.fBE.U, s"$name: forwardBE")
    io.pcSrcD.expect(o.pcSrcD.B, s"$name: pcSrcD")
    io.stallF.expect(o.stallF.B, s"$name: stallF")
    io.stallD.expect(o.stallD.B, s"$name: stallD")
    io.flushD.expect(o.flushD.B, s"$name: flushD")
    io.flushE.expect(o.flushE.B, s"$name: flushE")
    io.loadUse.expect(o.loadUse.B, s"$name: loadUse")
    io.branchUse.expect(o.branchUse.B, s"$name: branchUse")
    io.stallE.expect(o.stallE.B, s"$name: stallE")
    io.flushM.expect(o.flushM.B, s"$name: flushM")
    io.stallM.expect(o.stallM.B, s"$name: stallM")
    io.flushW.expect(o.flushW.B, s"$name: flushW")
  }

  // ----------------------------------------------------------- forwarding

  "HazardUnit" should "forward to EX by the design-page rules" in {
    test(new HazardUnit) { c =>
      // ex 1: add x5 ; sub x6, x5, x3  -> rs1 from MEM
      check(c, "ex1 distance 1", In(rs1E = 5, regWriteM = true, rdM = 5))(_.fAE == ExMem)
      // ex 2: add x5 ; nop ; sub x6, x5, x3  -> rs1 from WB
      check(c, "ex2 distance 2", In(rs1E = 5, regWriteW = true, rdW = 5))(_.fAE == MemWb)
      // ex 3: both write x5 -> MEM (newer) wins
      check(c, "ex3 newest wins", In(rs1E = 5, regWriteM = true, rdM = 5, regWriteW = true, rdW = 5))(_.fAE == ExMem)
      // ex 4: store data rs2 from MEM
      check(c, "ex4 store data", In(rs2E = 5, regWriteM = true, rdM = 5))(_.fBE == ExMem)
      // ex 12: x0 never forwards
      check(c, "ex12 x0", In(rs1E = 0, rs2E = 0, regWriteM = true, rdM = 0, regWriteW = true, rdW = 0))(
        o => o.fAE == None && o.fBE == None)
      // MEM does not write (store / branch / bubble)
      check(c, "no regWrite", In(rs1E = 5, regWriteM = false, rdM = 5))(_.fAE == None)
    }
  }

  it should "forward to ID by the design-page rules" in {
    test(new HazardUnit) { c =>
      // ex 7: add x5 ; nop ; beq x5  -> from MEM
      check(c, "ex7 branch distance 2", In(rs1D = 5, branchD = true, regWriteM = true, rdM = 5))(_.fAD == ExMem)
      // ex 8 / 9: distance 3 -> from WB (for every instruction, not only branches)
      check(c, "ex9 distance 3", In(rs2D = 5, regWriteW = true, rdW = 5))(_.fBD == MemWb)
      // a load in MEM: ALUOutM is its address, never forward it to ID
      check(c, "load in MEM", In(rs1D = 5, regWriteM = true, aluOutNotResultM = true, rdM = 5))(_.fAD == None)
      check(c, "load in MEM, older in WB", In(rs1D = 5, regWriteM = true, aluOutNotResultM = true, rdM = 5,
                                              regWriteW = true, rdW = 5))(_.fAD == MemWb)
      // ex 10: load in WB -> ResultW
      check(c, "ex10 load in WB", In(rs1D = 5, branchD = true, regWriteW = true, rdW = 5))(_.fAD == MemWb)
    }
  }

  // ----------------------------------------------------------- stalls

  it should "stall for load-use, branch-use E and branch-use M only" in {
    test(new HazardUnit) { c =>
      // load-use: lw x5 ; add x6, x5, x1
      check(c, "load-use", In(rs1D = 5, readsRs1D = true, regWriteE = true, aluOutNotResultE = true, rdE = 5))(
        o => o.loadUse && o.stallF && o.stallD && o.flushE && !o.branchUse)
      // lw x5 ; addi x6, x7, 5: bits 24:20 = 5 are an immediate -> no stall
      check(c, "no false load-use", In(rs1D = 7, rs2D = 5, readsRs1D = true, readsRs2D = false,
                                       regWriteE = true, aluOutNotResultE = true, rdE = 5))(o => !o.stallF)
      // lw x0 -> no stall
      check(c, "load to x0", In(rs1D = 0, readsRs1D = true, regWriteE = true, aluOutNotResultE = true, rdE = 0))(o => !o.stallF)
      // branch-use E: addi x5 ; beq x5, x3
      check(c, "branch-use E", In(rs1D = 5, rs2D = 3, readsRs1D = true, readsRs2D = true, branchD = true,
                                  regWriteE = true, rdE = 5))(o => o.branchUse && o.stallF && !o.loadUse)
      // not a branch: add x5 ; sub x6, x5  -> EX forwarding, no stall
      check(c, "ALU then ALU", In(rs1D = 5, readsRs1D = true, regWriteE = true, rdE = 5))(o => !o.stallF)
      // branch-use M: lw x5 ; nop ; beq x5, x0
      check(c, "branch-use M", In(rs1D = 5, readsRs1D = true, readsRs2D = true, branchD = true,
                                  regWriteM = true, aluOutNotResultM = true, rdM = 5))(o => o.branchUse && o.stallF)
      // ALU in MEM, branch in ID -> forward (condition 3), no stall
      check(c, "ALU in MEM, branch", In(rs1D = 5, readsRs1D = true, readsRs2D = true, branchD = true,
                                        regWriteM = true, rdM = 5))(o => !o.stallF && o.fAD == ExMem)
    }
  }

  // ----------------------------------------------------------- jumps and flushes

  it should "jump and flush as the design document says" in {
    test(new HazardUnit) { c =>
      check(c, "branch taken", In(branchD = true, takenD = true, readsRs1D = true, readsRs2D = true))(
        o => o.pcSrcD && o.flushD && !o.flushE)
      check(c, "branch not taken", In(branchD = true, takenD = false))(o => !o.pcSrcD && !o.flushD)
      check(c, "branch taken but waiting", In(rs1D = 5, branchD = true, takenD = true, regWriteE = true, rdE = 5))(
        o => !o.pcSrcD && o.stallF)
      check(c, "jal", In(jalD = true))(o => o.pcSrcD && o.flushD)
      check(c, "jalr in EX", In(jalrE = true))(o => o.flushD && o.flushE && !o.pcSrcD)
      // jalr x5 ; beq x5: stall and flush together, Core lets flush win
      check(c, "jalr and branch-use", In(rs1D = 5, branchD = true, regWriteE = true, rdE = 5, jalrE = true))(
        o => o.flushD && o.flushE && o.stallD)
    }
  }

  // ----------------------------------------------------------- EX busy

  it should "hold IF, ID, EX and bubble MEM while EX is busy" in {
    test(new HazardUnit) { c =>
      // a divide computing in EX, an unrelated add in ID
      check(c, "busy alone", In(rs1D = 1, rs2D = 2, readsRs1D = true, readsRs2D = true, busyE = true))(
        o => o.stallF && o.stallD && o.stallE && o.flushM && !o.flushE && !o.loadUse && !o.branchUse)
      // div x5 ; beq x5: branch-use E and busy together -> the divide must stay in EX
      check(c, "busy + branch-use", In(rs1D = 5, rs2D = 3, branchD = true, regWriteE = true, rdE = 5,
                                       busyE = true))(o => o.stallE && !o.flushE && o.flushM && o.branchUse)
      // the POST cycle of that divide: busy is 0, branch-use still holds
      check(c, "divide done, branch still waits", In(rs1D = 5, rs2D = 3, branchD = true, regWriteE = true,
                                                     rdE = 5))(o => !o.stallE && o.flushE && !o.flushM)
      // jal in ID while EX is busy: it must not jump yet (flushD would clear the jal)
      check(c, "jal waits for busy EX", In(jalD = true, busyE = true))(o => !o.pcSrcD && !o.flushD && o.stallD)
      // a taken branch with ready operands also waits
      check(c, "taken branch waits for busy EX", In(branchD = true, takenD = true, busyE = true))(
        o => !o.pcSrcD && !o.flushD)
      // div x5 ; add x6, x5: not a load-use (the divide result is made in EX)
      check(c, "divide then use", In(rs1D = 5, readsRs1D = true, regWriteE = true, rdE = 5, busyE = true))(
        o => !o.loadUse && o.stallE)
    }
  }

  // ----------------------------------------------------------- redirect

  it should "clear IF/ID, ID/EX and EX/MEM on a trap or mret in MEM" in {
    test(new HazardUnit) { c =>
      check(c, "redirect alone", In(redirectM = true))(
        o => o.flushD && o.flushE && o.flushM && !o.pcSrcD && !o.stallF)
      // load-use in ID at the same time: still cleared (flush wins over stall in PipeReg)
      check(c, "redirect + load-use", In(rs1D = 5, readsRs1D = true, regWriteE = true,
                                         aluOutNotResultE = true, rdE = 5, redirectM = true))(
        o => o.flushD && o.flushE && o.flushM && o.stallD)
      // a divide waiting in EX is cleared too (Core also kills the divider)
      check(c, "redirect + busy EX", In(busyE = true, redirectM = true))(
        o => o.flushD && o.flushE && o.flushM && o.stallE)
      // jumps in ID and EX at the same time: Core's PC mux takes the redirect
      check(c, "redirect + jal + jalr", In(jalD = true, jalrE = true, redirectM = true))(
        o => o.flushD && o.flushE && o.flushM)
    }
  }

  // ----------------------------------------------------------- MEM busy

  it should "hold everything before WB while an AMO is busy in MEM" in {
    test(new HazardUnit) { c =>
      check(c, "busy MEM alone", In(busyM = true))(
        o => o.stallF && o.stallD && o.stallE && o.stallM && o.flushW && !o.flushE && !o.flushM)
      // load-use in ID at the same time: EX must not be cleared, it waits too
      check(c, "busy MEM + load-use", In(rs1D = 5, readsRs1D = true, regWriteE = true,
                                         aluOutNotResultE = true, rdE = 5, busyM = true))(
        o => o.loadUse && !o.flushE && o.stallE && o.stallM)
      // a divide in EX and the AMO in MEM: the AMO must not be cleared
      check(c, "busy MEM + busy EX", In(busyE = true, busyM = true))(
        o => o.stallE && o.stallM && !o.flushM && o.flushW)
      // no jump in ID while MEM is busy
      check(c, "jal waits for busy MEM", In(jalD = true, busyM = true))(o => !o.pcSrcD && !o.flushD)
      check(c, "taken branch waits for busy MEM", In(branchD = true, takenD = true, busyM = true))(
        o => !o.pcSrcD && !o.flushD)
      // second AMO cycle: nothing is held any more
      check(c, "AMO second cycle", In())(o => !o.stallM && !o.flushW && !o.stallF)
    }
  }

  // ----------------------------------------------------------- random

  it should "match the Scala model on random inputs" in {
    val rnd = new scala.util.Random(11)
    def r = rnd.nextInt(4)                       // registers 0..3: many matches, x0 included
    def b = rnd.nextBoolean()
    test(new HazardUnit) { c =>
      for (n <- 0 until 5000) {
        val i = In(r, r, b, b, b, b, b, r, r, r, b, b, b, r, b, b, r, b, b, b, b)
        check(c, s"random $n $i", i)(_ => true)
      }
    }
  }
}
