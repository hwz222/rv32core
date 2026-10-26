package rvsc

import chisel3._
import chisel3.util._

// Hazard unit (combinational only, no registers).
// Rules: rtl/DESIGN.md, section 3.
//   forwarding  ForwardAD/BD (to ID), ForwardAE/BE (to EX), codes from Fwd:
//               Fwd.None = register file / ID/EX value, Fwd.ExMem = ALUOutM,
//               Fwd.MemWb = ResultW
//   stall       loadUse, branchUseE, branchUseM (aluOutNotResult, not memRead)
//   jump        pcSrcD = (branchD && takenD || jalD) && !(stall || busyE)
//   flush       branch taken or JAL in ID, JALR in EX, trap or mret in MEM
//   busy EX     busyE (divide): hold IF, ID, EX; bubble into MEM
//   busy MEM    busyM (AMO, first MEM cycle): hold IF, ID, EX, MEM; bubble into WB
//   per-stage hold / clear outputs, so new stall sources only change this module
//
// Inputs from Core. Every control input is already ANDed with the valid bit
// of its stage (a bubble never writes, never jumps, never stalls).
// Register numbers are 5 bits; x0 never forwards and never stalls.
//
// How Core uses the outputs (priority as in rtl/DESIGN.md, section 3.2):
//   PC     : reset > redirectM (trap target / mepc) > jalrE (PCJalrE) > pcSrcD (PCBranchD)
//            > stallF (hold) > pc + 4
//   IF/ID  : when(flushD) bubble .elsewhen(!stallD) next      (flush wins over stall)
//   ID/EX  : when(flushE) bubble .elsewhen(!stallE) next
//   EX/MEM : when(flushM) bubble .elsewhen(!stallM) next
//   MEM/WB : when(flushW) bubble                               (busyM)
//   stallE and flushM come from busyE; stallM and flushW from busyM;
//   flushM also from redirectM.
class HazardUnit extends Module {
  val io = IO(new Bundle {
    // ---- ID: the instruction being decoded
    val rs1D      = Input(UInt(Consts.REG_ADDR_W.W))  // inst(19, 15)
    val rs2D      = Input(UInt(Consts.REG_ADDR_W.W))  // inst(24, 20)
    val readsRs1D = Input(Bool())                     // ctrl.readsRs1
    val readsRs2D = Input(Bool())                     // ctrl.readsRs2
    val branchD   = Input(Bool())                     // ctrl.branch
    val jalD      = Input(Bool())                     // ctrl.jal
    val takenD    = Input(Bool())                     // BranchCmp: condition holds

    // ---- EX
    val rs1E       = Input(UInt(Consts.REG_ADDR_W.W)) // kept in ID/EX
    val rs2E       = Input(UInt(Consts.REG_ADDR_W.W))
    val rdE        = Input(UInt(Consts.REG_ADDR_W.W))
    val regWriteE  = Input(Bool())
    val aluOutNotResultE = Input(Bool())              // its aluOut is not the rd value (load)
    val jalrE      = Input(Bool())                    // JALR jumps in EX this cycle
    val busyE      = Input(Bool())                    // the instruction in EX needs more cycles
                                                      // (a divide; Core: req.valid && !resp.valid)

    // ---- MEM
    val rdM        = Input(UInt(Consts.REG_ADDR_W.W))
    val regWriteM  = Input(Bool())
    val aluOutNotResultM = Input(Bool())
    val redirectM  = Input(Bool())                    // trap or mret in MEM (TrapUnit)
    val busyM      = Input(Bool())                    // the AMO in MEM needs one more cycle

    // ---- WB
    val rdW        = Input(UInt(Consts.REG_ADDR_W.W))
    val regWriteW  = Input(Bool())

    // ---- forwarding selects (values from object Fwd)
    val forwardAD = Output(UInt(2.W))   // ID rs1: to the branch compare and ID/EX
    val forwardBD = Output(UInt(2.W))   // ID rs2
    val forwardAE = Output(UInt(2.W))   // EX rs1: ALU A, JALR base
    val forwardBE = Output(UInt(2.W))   // EX rs2: ALU B, store data

    // ---- jump decided in ID
    val pcSrcD    = Output(Bool())      // branch taken (and not stalled) or JAL

    // ---- hold (stall) and clear (flush) per pipeline register
    val stallF    = Output(Bool())      // PC keeps its value
    val stallD    = Output(Bool())      // IF/ID keeps its value
    val stallE    = Output(Bool())      // ID/EX keeps its value   (busyE)
    val stallM    = Output(Bool())      // EX/MEM keeps its value  (busyM)
    val flushD    = Output(Bool())      // IF/ID becomes a bubble
    val flushE    = Output(Bool())      // ID/EX becomes a bubble
    val flushM    = Output(Bool())      // EX/MEM becomes a bubble (busyE)
    val flushW    = Output(Bool())      // MEM/WB becomes a bubble (busyM)

    // ---- for events (PerfEvents)
    val loadUse   = Output(Bool())      // -> stallLoadUse
    val branchUse = Output(Bool())      // -> stallBranchUse (branchUseE || branchUseM)
  })

  // ---- Forwarding to EX (first kind): which value EX uses for rs1 / rs2.
  // Condition 1: MEM writes the same register -> ALUOutM.
  // Condition 2: WB writes the same register  -> ResultW.
  // Both hold -> MEM wins, it is the newer write (when before elsewhen).
  // No aluOutNotResult check: a load in MEM never meets its user in EX, because
  // loadUse already put a bubble between them.
  io.forwardAE := Fwd.None                                            // 00: value in ID/EX
  when(io.regWriteM && io.rdM =/= 0.U && io.rdM === io.rs1E) {        // condition 1
    io.forwardAE := Fwd.ExMem                                         // 01: ALUOutM
  }.elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs1E) {  // condition 2
    io.forwardAE := Fwd.MemWb                                         // 10: ResultW
  }

  io.forwardBE := Fwd.None
  when(io.regWriteM && io.rdM =/= 0.U && io.rdM === io.rs2E) {        // condition 1
    io.forwardBE := Fwd.ExMem
  }.elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs2E) {  // condition 2
    io.forwardBE := Fwd.MemWb
  }

  // ---- Forwarding to ID (second kind): the value goes to the branch compare
  // and into ID/EX.
  // Condition 3: MEM writes the same register and it is NOT a load
  //              (aluOutNotResultM = 1: aluOutM is the load address, the data is not
  //              here yet; the branch waits with branchUseM instead).
  // Condition 4: WB writes the same register -> ResultW (load data included).
  io.forwardAD := Fwd.None                                                          // 00: register file
  when(io.regWriteM && !io.aluOutNotResultM && io.rdM =/= 0.U && io.rdM === io.rs1D) {  // condition 3 (!aluOutNotResultM: aluOutM IS the result)
    io.forwardAD := Fwd.ExMem                                                       // 01: ALUOutM
  }.elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs1D) {              // condition 4
    io.forwardAD := Fwd.MemWb                                                       // 10: ResultW
  }

  io.forwardBD := Fwd.None
  when(io.regWriteM && !io.aluOutNotResultM && io.rdM =/= 0.U && io.rdM === io.rs2D) {  // condition 3 (!aluOutNotResultM: aluOutM IS the result)
    io.forwardBD := Fwd.ExMem
  }.elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs2D) {              // condition 4
    io.forwardBD := Fwd.MemWb
  }

  // ---- Stalls: a value is not ready yet and forwarding cannot help.
  // loadUse: EX holds an instruction whose aluOut is not its rd value
  // (aluOutNotResultE: a load now, CSR / AMO later), and ID really reads
  // that register. The data arrives only in WB, too late for ID's
  // instruction to use it in EX next cycle. Stall 1 cycle; then forwarding condition 2 (ResultW) gives it.
  // readsRs1D / readsRs2D avoid a false stall when bits 19:15 / 24:20 are part
  // of an immediate (lui, addi, ...).
  val loadUse = io.aluOutNotResultE && io.rdE =/= 0.U &&
                ((io.readsRs1D && io.rdE === io.rs1D) || (io.readsRs2D && io.rdE === io.rs2D))

  // branchUse: only for a branch in ID, which compares its operands in ID,
  // one stage earlier than EX. (A branch always reads rs1 and rs2.)
  //   branchUseE: EX writes a register the branch reads; the result is in
  //               ALUOutM next cycle (condition 3). Stall 1 cycle.
  //   branchUseM: MEM holds a load the branch reads; the data is in ResultW
  //               next cycle (condition 4). Stall 1 more cycle.
  // A load right before a branch triggers branchUseE, then branchUseM: 2 cycles.
  val branchUseE = io.branchD && io.regWriteE && io.rdE =/= 0.U &&
                   (io.rdE === io.rs1D || io.rdE === io.rs2D)
  val branchUseM = io.branchD && io.aluOutNotResultM && io.rdM =/= 0.U &&
                   (io.rdM === io.rs1D || io.rdM === io.rs2D)
  val branchUse  = branchUseE || branchUseM
  val stall      = loadUse || branchUse

  io.loadUse   := loadUse
  io.branchUse := branchUse

  // ---- EX busy (busyE, a divide still computing). Two kinds of waiting:
  //   ID waits (stall = loadUse || branchUse):  hold PC, IF/ID; ID/EX gets a bubble
  //   EX busy  (busyE):                        hold PC, IF/ID, ID/EX; EX/MEM gets a bubble
  // How busyE changes the outputs below:
  //   stallF, stallD = stall || busyE
  //   stallE         = busyE
  //   flushE         = ... || (stall && !busyE)   never clear the divide in EX
  //   flushM         = busyE || ...
  //   pcSrcD         = (branchD && takenD || jalD) && !(stall || busyE)
  //                    a JAL in ID must also wait: if it jumped while EX is
  //                    busy, flushD would clear the JAL itself (IF/ID) before
  //                    it could enter EX, and rd would never get pc + 4.

  // ---- MEM busy (busyM, the first MEM cycle of an AMO: it reads now
  // and writes next cycle). Everything older than WB waits:
  //   hold PC, IF/ID, ID/EX, EX/MEM; MEM/WB gets a bubble (flushW).
  //   flushE, flushM must not clear the instruction that waits in EX / MEM.
  //   No jump while MEM is busy (same reason as for busyE). Core also holds
  //   jalrE and the divider result (resp.ready = !busyM).
  val hold = stall || io.busyE || io.busyM   // IF and ID must wait

  // ---- Jump decided in ID: a taken branch (only when its operands are ready,
  // so !stall) or a JAL. Core then loads PCBranchD = PCD + ImmD into the PC.
  val pcSrcD = (io.branchD && io.takenD || io.jalD) && !hold
  io.pcSrcD := pcSrcD

  // ---- Hold and clear. Core gives flush priority over stall.
  // stall:  PC and IF/ID keep their values; Core sends PCD (ifId.pc) to
  //         IMEM so ID gets its instruction again;
  //         ID/EX gets a bubble so nothing runs twice.
  // pcSrcD: the instruction fetched after the jump (now in IF) is wrong: clear IF/ID.
  // jalrE:  the two instructions after the JALR (in IF and ID) are wrong:
  //         clear IF/ID and ID/EX.
  io.stallF := hold
  io.stallD := hold
  io.stallE := io.busyE || io.busyM
  io.stallM := io.busyM
  // redirectM: a trap or mret in MEM. The younger instructions in IF,
  //         ID and EX are on a wrong path: clear IF/ID, ID/EX and EX/MEM.
  //         It wins over every stall (flush wins in PipeReg) and over the
  //         jumps in ID and EX (Core's PC mux). Core also kills the divider.
  //         (Never together with busyM: an AMO that traps is not busy.)
  io.flushD := pcSrcD || io.jalrE || io.redirectM
  io.flushE := io.jalrE || (stall && !io.busyE && !io.busyM) || io.redirectM
  io.flushM := (io.busyE && !io.busyM) || io.redirectM
  io.flushW := io.busyM
}
