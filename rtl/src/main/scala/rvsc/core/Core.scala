package rvsc

import chisel3._
import chisel3.util._

// The five-stage RV32IMA pipeline. io is CoreIO (see CoreBase and
// Interfaces.scala): bootAddr, irq, imem, dmem, retire, events.
// Drawing: rtl/microarch_diagram.svg.
// Parts: A defaults and instances, B IF, C ID, D EX, E MEM and WB,
//        F hazard unit, retire record, events.
//
// Naming. A name in the drawing and in this file is the same string; only
// the case of the first letter differs (RegWriteE in the drawing = regWriteE).
//   - The suffix is the stage that USES the value: F = IF, D = ID, E = EX,
//     M = MEM, W = WB. A pipeline register output belongs to the stage that
//     reads it: the pc kept in ID/EX is read in EX, so it is pcE.
//   - Each stage starts with an "inputs" block that names every field it
//     reads from its pipeline register (pcE = idEx.pc, ...). The code below
//     that block uses only these names. aluOutM is named in part A instead,
//     because ID and EX read it (forwarding) before the MEM part.
//   - rs1Val / rs2Val: the rs value this stage has. rs1Fwd / rs2Fwd: the value
//     after EX forwarding, the one EX really uses. rd is only the NUMBER of
//     the destination register, never a value.
//   - Ports on a block in the drawing use the module's port names
//     (regFile: raddr1, rdata1, we, ...; IMEM / DMEM: addr, wdata, rdata).
//   - Scala case rule: IfId is a type (Bundle), ifId is hardware.
//
// Valid rule (a bubble must never write, jump or stall):
//   - Only ctrlD comes straight from the decoder, so only it is ANDed with
//     validD. Later stages read pipeline registers, where a bubble already has
//     every control signal 0 (flush / reset, and the ID/EX input Mux in part C).
//   - jalrE, the DMEM request and regWriteW AND valid once more as an extra
//     guard, because they change the PC, memory or a register.
//
// Exception rule (see Exceptions.scala and TrapUnit.scala):
//   - An exception is found in ID or EX and only recorded (exc fields); it
//     takes effect in MEM, after every older instruction has passed MEM.
//   - An instruction with an exception does nothing, like a bubble, but keeps
//     valid = 1 so it reaches MEM, traps there and retires as a trap line:
//     ID sends its ctrl as 0, it never jumps, and MEM blocks DMEM, the CSR
//     write and the rd write.
//   - exc is only meaningful when valid = 1 (TrapUnit checks valid first).
//   - A trap or mret in MEM (redirectM) changes the PC and clears the three
//     younger instructions.
//
// Other rules:
//   - Give every wire a name with val (it shows up in the waveform).
//   - "Last := wins": the defaults below are overridden by the code after them.
class Core extends CoreBase {

  // ===========================================================================
  // A. Defaults and instances
  // ===========================================================================

  // Every output has a value first (see StubCore.scala:41-52).
  io.imem.req.valid := false.B
  io.imem.req.bits  := 0.U.asTypeOf(new ImemReq)
  io.dmem.req.valid := false.B
  io.dmem.req.bits  := 0.U.asTypeOf(new DmemReq)
  io.retire         := 0.U.asTypeOf(new RetireIO)
  io.events         := 0.U.asTypeOf(new PerfEvents)
  // io.irq is not used yet (FreeRTOS, rtos branch).

  // Modules of steps 1-8
  val decoder    = Module(new Decoder)       // ID
  val regFile    = Module(new RegFile)       // ID reads, WB writes
  val immGen     = Module(new ImmGen)        // ID
  val branchCmp  = Module(new BranchCmp)     // ID
  val alu        = Module(new ALU)           // EX
  val storeAlign = Module(new StoreAlign)    // MEM
  val loadAlign  = Module(new LoadAlign)     // WB
  val hazard     = Module(new HazardUnit)    // all stages
  val multiplier = Module(new Multiplier)    // EX (one cycle)
  val divider    = Module(new Divider)       // EX (multi-cycle)
  val csrFile    = Module(new CsrFile)       // MEM
  val idExcCheck = Module(new IdExcCheck)    // ID 
  val exExcCheck = Module(new ExExcCheck)    // EX 
  val trapUnit   = Module(new TrapUnit)      // MEM
  val amoAlu     = Module(new AmoAlu)        // MEM

  // Pipeline registers. Drive xxxReg.io.in, read xxxReg.io.out.
  val ifIdReg  = Module(new PipeReg(new IfId))
  val idExReg  = Module(new PipeReg(new IdEx))
  val exMemReg = Module(new PipeReg(new ExMem))
  val memWbReg = Module(new PipeReg(new MemWb))
  val ifId  = ifIdReg.io.out                  // what ID works on this cycle
  val idEx  = idExReg.io.out                  // what EX works on
  val exMem = exMemReg.io.out                 // what MEM works on
  val memWb = memWbReg.io.out                 // what WB works on

  // Values read before the stage that makes them. Scala runs this class body
  // from top to bottom, so a name must exist before it is read.
  //   Wires: made here, given a value later with := (never with val again).
  //   Each one must get its value in the part named on the right, or the
  //   design will not elaborate ("not fully initialized").
  val pcBranchD = Wire(UInt(Consts.XLEN.W))   // branch / JAL target; read in IF; set in part C
  val jalrE     = Wire(Bool())                // a valid JALR in EX;  read in IF; set in part D
  val pcJalrE   = Wire(UInt(Consts.XLEN.W))   // JALR target;         read in IF; set in part D
  val resultW   = Wire(UInt(Consts.XLEN.W))   // write-back value;    read in ID, EX; set in part E
  val busyM     = Wire(Bool())                // AMO's first MEM cycle; read in EX, part F; set in part E
  //   aluOutM is a pipeline register output, so a plain val is enough. It is
  //   forwarded to ID and EX, so it is named here instead of in part E.
  val aluOutM   = exMem.aluOut
  //   redirectM / pcRedirectM are TrapUnit outputs (a module port, like a
  //   pipeline register output), so plain vals too. IF and EX read them;
  //   TrapUnit's inputs are set in part E.
  val redirectM   = trapUnit.io.redirect      // trap or mret in MEM
  val pcRedirectM = trapUnit.io.target        // mtvec (trap) or mepc (mret)

  // Hold (stall) and clear (flush) of each pipeline register, from the
  // hazard unit. Flush wins over stall inside PipeReg. MEM/WB never holds.
  ifIdReg.io.stall  := hazard.io.stallD
  ifIdReg.io.flush  := hazard.io.flushD
  // ID/EX never just keeps its value: while EX waits (stallE) it takes the
  // operands after EX forwarding (part D), so a value forwarded from WB is not
  // lost when that instruction leaves WB.
  idExReg.io.stall  := false.B
  idExReg.io.flush  := hazard.io.flushE
  exMemReg.io.stall := hazard.io.stallM
  exMemReg.io.flush := hazard.io.flushM
  memWbReg.io.stall := false.B
  memWbReg.io.flush := hazard.io.flushW      // bubble while the AMO is busy in MEM

  // ===========================================================================
  // B. IF: PC and the fetch request
  // ===========================================================================
  val pcF      = RegInit(io.bootAddr)         // the PC register; after reset = bootAddr
  val pcPlus4F = pcF + 4.U

  // IMEM address. During a stall IMEM reads the ID instruction again
  // (pcD): IF/ID keeps no instruction, and IMEM answers one cycle later, so
  // ID must get the same answer next cycle. pcF is one instruction ahead of
  // ID (it is the IF address); sending pcF would give ID the next instruction.
  val imemAddrF = Mux(hazard.io.stallD, ifId.pc, pcF)   // ifId.pc = pcD (named in part C)

  // Fetch request. ready = 0 during reset and while hostEn = 1: not taken.
  io.imem.req.valid     := true.B
  io.imem.req.bits.addr := imemAddrF
  val fetchFire = io.imem.req.valid && io.imem.req.ready

  // Next PC (rtl/DESIGN.md, section 3.2). pcSel = {redirectM, jalrE, pcSrcD}.
  // The oldest instruction wins; the younger jumps are on a wrong path:
  //   1xx: trap or mret in MEM
  //   01x: JALR in EX
  //   001: branch taken or JAL in ID
  //   000: pc + 4
  val pcSel   = Cat(redirectM, jalrE, hazard.io.pcSrcD)
  val pcNextF = MuxCase(pcPlus4F, Seq(
    pcSel(2) -> pcRedirectM,
    pcSel(1) -> pcJalrE,
    pcSel(0) -> pcBranchD))
  // PC write enable. A jump always writes: stall only stops pc + 4.
  // A jump does not check fetchFire: what IF fetches now is flushed anyway.
  val pcEnF = pcSel =/= 0.U || (!hazard.io.stallF && fetchFire)
  when(pcEnF) {
    pcF := pcNextF
  }

  // IF/ID input. On a jump flushD clears it, so a wrong-path fetch never reaches ID.
  ifIdReg.io.in.valid := fetchFire
  ifIdReg.io.in.pc    := pcF

  // ===========================================================================
  // C. ID: decode, read registers, forward, compare, branch target
  // ===========================================================================
  // ---- ID inputs. The instruction is the IMEM answer (sync read: it arrives
  // one cycle after the request, together with ifId.pc).
  val validD = ifId.valid
  val pcD    = ifId.pc
  val instD  = io.imem.resp.bits.inst

  // Decode. The register numbers and funct3 are fixed bits of the instruction.
  decoder.io.inst := instD
  val ctrlD   = decoder.io.ctrl
  val rs1D    = instD(19, 15)
  val rs2D    = instD(24, 20)
  val rdD     = instD(11, 7)
  val funct3D = instD(14, 12)
  // The control lines out of the Control unit in the drawing. They are not
  // ANDed with validD here: the ID/EX input below and the hazard unit inputs
  // in part F do that (valid rule).
  val regWriteD  = ctrlD.regWrite
  val wbSelD     = ctrlD.wbSel
  val memWriteD  = ctrlD.memWrite
  val memReadD   = ctrlD.memRead
  val aluOutNotResultD = ctrlD.aluOutNotResult
  val linkD      = ctrlD.link
  val jalrD      = ctrlD.jalr
  val jalD       = ctrlD.jal
  val branchD    = ctrlD.branch
  val aluOpD     = ctrlD.aluOp
  val srcAD      = ctrlD.srcA
  val srcBD      = ctrlD.srcB
  val immSelD    = ctrlD.immSel
  val readsRs1D  = ctrlD.readsRs1
  val readsRs2D  = ctrlD.readsRs2

  // Register file read (read-first: a write in WB this cycle is not seen here;
  // the ID forward mux below gives that value through resultW).
  regFile.io.raddr1 := rs1D
  regFile.io.raddr2 := rs2D

  // Immediate
  immGen.io.inst    := instD
  immGen.io.immType := immSelD
  val immD = immGen.io.imm

  // ID forward muxes (select from the hazard unit, codes in object Fwd).
  // Their output goes to both the branch compare and ID/EX.
  //   None  -> register file (rdata1 / rdata2)
  //   ExMem -> aluOutM (never a load address: the hazard unit stalls instead)
  //   MemWb -> resultW (also a load value)
  val rs1ValD = MuxLookup(hazard.io.forwardAD, regFile.io.rdata1, Seq(
    Fwd.ExMem -> aluOutM,
    Fwd.MemWb -> resultW))
  val rs2ValD = MuxLookup(hazard.io.forwardBD, regFile.io.rdata2, Seq(
    Fwd.ExMem -> aluOutM,
    Fwd.MemWb -> resultW))

  // Branch compare and target. pcSrcD (hazard unit) decides if IF jumps.
  branchCmp.io.a      := rs1ValD
  branchCmp.io.b      := rs2ValD
  branchCmp.io.funct3 := funct3D
  val takenD = branchCmp.io.taken             // only the compare result, not ANDed with branchD
  pcBranchD := pcD + immD                     // branch / JAL target (Wire in part A)

  // exceptions found in ID. An instruction with one sends its ctrl to
  // ID/EX as 0 (below) and does not jump (part F).
  idExcCheck.io.pc          := pcD
  idExcCheck.io.inst        := instD
  idExcCheck.io.illegal     := ctrlD.illegalInst
  idExcCheck.io.ecall       := ctrlD.ecall
  idExcCheck.io.ebreak      := ctrlD.ebreak
  idExcCheck.io.branchTaken := branchD && takenD
  idExcCheck.io.jal         := jalD
  idExcCheck.io.target      := pcBranchD
  val excD = idExcCheck.io.exc

  // ID/EX input (class IdEx). A bubble in ID sends all control signals as 0,
  // so EX, MEM and WB never act on a stale IMEM answer. An instruction with
  // an exception does too, but keeps valid = 1 and its exc fields.
  // (During a stall excD may be wrong, but then ID/EX gets a bubble anyway.)
  // idExD is the next ID/EX value from ID; part D picks it or the held value.
  val idExD = Wire(new IdEx)
  idExD.valid    := validD
  idExD.pc       := pcD
  idExD.inst     := instD
  idExD.ctrl     := Mux(validD && !excD.valid, ctrlD, 0.U.asTypeOf(new CtrlSignals))
  idExD.rs1Val   := rs1ValD
  idExD.rs2Val   := rs2ValD
  idExD.imm      := immD
  idExD.rs1      := rs1D
  idExD.rs2      := rs2D
  idExD.rd       := rdD
  idExD.funct3   := funct3D
  idExD.excValid := excD.valid
  idExD.excCause := excD.cause
  idExD.excTval  := excD.tval

  // ===========================================================================
  // D. EX: forward, ALU, link value, JALR target
  // ===========================================================================
  // ---- EX inputs (from ID/EX). Below this block only xxxE names are used.
  val validE     = idEx.valid
  val pcE        = idEx.pc
  val instE      = idEx.inst
  val immE       = idEx.imm
  val rs1E       = idEx.rs1
  val rs2E       = idEx.rs2
  val rdE        = idEx.rd
  val funct3E    = idEx.funct3
  val rs1ValE    = idEx.rs1Val                // before EX forwarding
  val rs2ValE    = idEx.rs2Val
  val regWriteE  = idEx.ctrl.regWrite
  val wbSelE     = idEx.ctrl.wbSel
  val memWriteE  = idEx.ctrl.memWrite
  val memReadE   = idEx.ctrl.memRead
  val aluOutNotResultE = idEx.ctrl.aluOutNotResult
  val linkE      = idEx.ctrl.link
  val aluOpE     = idEx.ctrl.aluOp
  val srcAE      = idEx.ctrl.srcA
  val srcBE      = idEx.ctrl.srcB
  val excValidE  = idEx.excValid
  val excCauseE  = idEx.excCause
  val excTvalE   = idEx.excTval
  val mulDivE    = idEx.ctrl.mulDiv
  val mulDivOpE  = idEx.ctrl.mulDivOp
  val csrE       = idEx.ctrl.csr
  val csrCmdE    = idEx.ctrl.csrCmd
  val csrWriteE  = idEx.ctrl.csrWrite
  val mretE      = idEx.ctrl.mret
  val lrE        = idEx.ctrl.lr
  val scE        = idEx.ctrl.sc
  val amoE       = idEx.ctrl.amo
  val amoOpE     = idEx.ctrl.amoOp
  val jalrInstE  = idEx.ctrl.jalr             // a JALR (jalrE below: and it jumps now)

  // EX forward muxes (same codes as in ID). None -> the value kept in ID/EX.
  // rs2FwdE is the ALU B operand of R-type and also the store data.
  val rs1FwdE = MuxLookup(hazard.io.forwardAE, rs1ValE, Seq(
    Fwd.ExMem -> aluOutM,
    Fwd.MemWb -> resultW))
  val rs2FwdE = MuxLookup(hazard.io.forwardBE, rs2ValE, Seq(
    Fwd.ExMem -> aluOutM,
    Fwd.MemWb -> resultW))

  // ID/EX input. While EX waits (stallE: a divide in EX or an AMO in
  // MEM), keep the instruction but store its operands after forwarding:
  // the instruction in WB that one of them came from leaves WB next cycle.
  val idExHoldE = WireInit(idEx)
  idExHoldE.rs1Val := rs1FwdE
  idExHoldE.rs2Val := rs2FwdE
  idExReg.io.in := Mux(hazard.io.stallE, idExHoldE, idExD)

  // ALU operands. MuxCase(default, Seq(condition -> value)): the first true
  // condition wins, else the default.
  //   A: Rs1 (default), Pc (AUIPC), Zero (LUI: 0 + imm)
  //   B: Rs2 (default), Imm
  val aluAE = MuxCase(rs1FwdE, Seq(
    (srcAE === SrcASel.Pc)   -> pcE,
    (srcAE === SrcASel.Zero) -> 0.U))
  val aluBE = MuxCase(rs2FwdE, Seq(
    (srcBE === SrcBSel.Imm)   -> immE,
    (srcBE === SrcBSel.Zero) -> 0.U))

  alu.io.a  := aluAE
  alu.io.b  := aluBE
  alu.io.op := aluOpE
  val aluRawE = alu.io.out                    // the ALU output itself

  // Multiplier (one cycle) and Divider (34 cycles in EX). Both take the
  // values after EX forwarding, like the ALU. The divider reads them only in
  // the cycle it takes the request, so forwarding sources that leave MEM / WB
  // while it computes do not matter.
  // MulDivOp = funct3: bit 2 = divide; for a divide, bit 1 = remainder.
  val isMulE = mulDivE && !mulDivOpE.asUInt(2)
  val isDivE = mulDivE &&  mulDivOpE.asUInt(2)
  val isRemE = mulDivOpE.asUInt(1)

  multiplier.io.a  := rs1FwdE
  multiplier.io.b  := rs2FwdE
  multiplier.io.op := mulDivOpE

  divider.io.req.valid       := validE && isDivE
  divider.io.req.bits.a      := rs1FwdE
  divider.io.req.bits.b      := rs2FwdE
  divider.io.req.bits.signed := mulDivOpE === MulDivOp.DIV || mulDivOpE === MulDivOp.REM
  divider.io.resp.ready      := !busyM      // MEM holds EX, keep the result
  divider.io.kill            := redirectM   // a trap or mret in MEM clears EX

  // EX busy: a divide is in EX and its result is not ready (hazard unit, events)
  val busyE = divider.io.req.valid && !divider.io.resp.valid

  val divOutE = Mux(isRemE, divider.io.resp.bits.rem, divider.io.resp.bits.quot)

  // EX result: one source per kind of instruction (the conditions never overlap).
  // JAL / JALR write pc + 4 to rd (link); the ALU then only makes the JALR target.
  val pcPlus4E = pcE + 4.U
  val aluOutE  = MuxCase(aluRawE, Seq(
    linkE  -> pcPlus4E,
    isMulE -> multiplier.io.out,
    isDivE -> divOutE))

  // JALR target (Wire in part A): rs1 + imm from the ALU, bit 0 cleared.
  pcJalrE := aluRawE & ~1.U(Consts.XLEN.W)

  // exceptions found in EX. excE keeps the one from ID, or adds a bad
  // load / store address or a misaligned JALR target.
  exExcCheck.io.in.valid   := excValidE
  exExcCheck.io.in.cause   := excCauseE
  exExcCheck.io.in.tval    := excTvalE
  exExcCheck.io.memRead    := memReadE
  exExcCheck.io.memWrite   := memWriteE
  exExcCheck.io.amo        := amoE
  exExcCheck.io.sc         := scE
  exExcCheck.io.funct3     := funct3E
  exExcCheck.io.addr       := aluRawE         // rs1 + imm
  exExcCheck.io.jalr       := jalrInstE
  exExcCheck.io.jalrTarget := pcJalrE
  val excE = exExcCheck.io.out

  // JALR jumps from EX (Wire in part A): validE once more as an extra guard, an
  // instruction with an exception never jumps, and it waits while MEM is
  // busy: jumping would clear ID/EX, where the JALR itself waits.
  jalrE := validE && jalrInstE && !excE.valid && !busyM

  // EX/MEM input (class ExMem). ctrl is already 0 for a bubble (see ID/EX input).
  exMemReg.io.in.valid     := validE
  exMemReg.io.in.pc        := pcE
  exMemReg.io.in.inst      := instE
  exMemReg.io.in.aluOut    := aluOutE            // the address for load / store
  exMemReg.io.in.storeData := rs2FwdE
  exMemReg.io.in.rd        := rdE
  exMemReg.io.in.funct3    := funct3E
  exMemReg.io.in.regWrite  := regWriteE
  exMemReg.io.in.wbSel     := wbSelE
  exMemReg.io.in.memWrite  := memWriteE
  exMemReg.io.in.memRead   := memReadE
  exMemReg.io.in.aluOutNotResult := aluOutNotResultE
  exMemReg.io.in.csr       := csrE
  exMemReg.io.in.csrCmd    := csrCmdE
  exMemReg.io.in.csrWrite  := csrWriteE
  exMemReg.io.in.mret      := mretE
  exMemReg.io.in.lr        := lrE
  exMemReg.io.in.sc        := scE
  exMemReg.io.in.amo       := amoE
  exMemReg.io.in.amoOp     := amoOpE
  exMemReg.io.in.excValid  := excE.valid
  exMemReg.io.in.excCause  := excE.cause
  exMemReg.io.in.excTval   := excE.tval

  // ===========================================================================
  // E. MEM and WB
  // ===========================================================================
  // ---- MEM inputs (from EX/MEM). aluOutM is named in part A.
  val validM     = exMem.valid
  val pcM        = exMem.pc
  val instM      = exMem.inst
  val storeDataM = exMem.storeData
  val rdM        = exMem.rd
  val funct3M    = exMem.funct3
  val regWriteM  = exMem.regWrite
  val wbSelM     = exMem.wbSel
  val memWriteM  = exMem.memWrite
  val memReadM   = exMem.memRead
  val aluOutNotResultM = exMem.aluOutNotResult
  val csrM       = exMem.csr
  val csrCmdM    = exMem.csrCmd
  val csrWriteM  = exMem.csrWrite
  val mretM      = exMem.mret
  val excValidM  = exMem.excValid
  val excCauseM  = exMem.excCause
  val excTvalM   = exMem.excTval
  val lrM        = exMem.lr
  val scM        = exMem.sc
  val amoM       = exMem.amo
  val amoOpM     = exMem.amoOp

  // the reservation of lr.w / sc.w (one word). LR sets it, SC uses it
  // up, a trap clears it (like isa_sim). Updated at the end of the cycle.
  val resValid = RegInit(false.B)
  val resAddr  = Reg(UInt(Consts.XLEN.W))
  val resHitM  = resValid && resAddr === aluOutM   // SC here may write
  // sc.w outside DMEM traps only when it would really write (isa_sim), so it
  // is found here, where the reservation is; all other A exceptions come from EX.
  val scFaultM = scM && resHitM && !MemMap.inDmem(aluOutM)

  // an AMO stays two cycles in MEM. amoPhase = 0: read the old word
  // (busyM holds the pipeline); amoPhase = 1: the old word is on the DMEM
  // response, AmoAlu makes the new word, write it.
  val amoPhase = RegInit(false.B)
  amoPhase := busyM
  amoAlu.io.op  := amoOpM
  amoAlu.io.old := io.dmem.resp.bits.rdata
  amoAlu.io.src := storeDataM
  val amoNewM = amoAlu.io.out

  // TrapUnit: does the instruction in MEM trap, or is it an mret? Both
  // redirect the PC (part B) and clear IF/ID, ID/EX, EX/MEM (hazard unit, part F).
  // A trapping instruction does nothing here: no DMEM request, no CSR write,
  // no rd write. It only leaves the trap fields for the retire record.
  trapUnit.io.valid      := validM
  trapUnit.io.pc         := pcM
  trapUnit.io.inst       := instM
  trapUnit.io.exc.valid  := excValidM || scFaultM
  trapUnit.io.exc.cause  := Mux(excValidM, excCauseM, Cause.STORE_ACCESS_FAULT.U)
  trapUnit.io.exc.tval   := Mux(excValidM, excTvalM, aluOutM)
  trapUnit.io.csrIllegal := csrM && csrFile.io.illegal   // illegal does not depend on csrFile.io.valid
  trapUnit.io.mret       := mretM
  trapUnit.io.irqPending := false.B          // FreeRTOS (rtos branch); there also AND !busyM:
                                             // never between the read and the write of an AMO
  trapUnit.io.irqCause   := 0.U
  trapUnit.io.mtvec      := csrFile.io.mtvec
  trapUnit.io.mepc       := csrFile.io.mepc
  val trapM = trapUnit.io.trap

  // the first MEM cycle of an AMO that does not trap (hazard unit, part F)
  busyM := validM && amoM && !amoPhase && !trapM

  when(trapM) {
    resValid := false.B
  }.elsewhen(validM && lrM) {
    resValid := true.B
    resAddr  := aluOutM
  }.elsewhen(validM && scM) {
    resValid := false.B
  }

  // CsrFile: a CSR instruction reads and writes its CSR here, unless it
  // traps (an illegal access, or an interrupt on the rtos branch).
  csrFile.io.valid  := validM && csrM && !trapM
  csrFile.io.addr   := instM(31, 20)
  csrFile.io.cmd    := csrCmdM
  csrFile.io.write  := csrWriteM
  csrFile.io.wdata  := aluOutM            // rs1 or zimm, brought by the ALU
  csrFile.io.retire := memWb.valid && !memWb.trap   // a trap does not count in minstret (isa_sim)
  // trap and mret. The full mcause has the interrupt bit at 31.
  csrFile.io.trap   := trapM
  csrFile.io.cause  := Cat(trapUnit.io.interrupt, 0.U(27.W), trapUnit.io.cause)
  csrFile.io.tval   := trapUnit.io.tval
  csrFile.io.epc    := pcM
  csrFile.io.mret   := redirectM && !trapM   // an mret that really returns
  // FreeRTOS: interrupt lines. Tied off for now.
  csrFile.io.irq    := 0.U.asTypeOf(new IrqIO)

  // Store: copy the data to its byte lanes and set the byte enables.
  // For load / store aluOutM is the byte address.
  storeAlign.io.addrLow   := aluOutM(1, 0)
  storeAlign.io.funct3    := funct3M
  storeAlign.io.storeData := Mux(amoM, amoNewM, storeDataM)   // AMO writes the new word

  // DMEM request. ctrl is already 0 for a bubble; valid is ANDed once more
  // as an extra guard, because this request changes memory (see the valid rule).
  // a bad address traps instead: no request (a store must never write).
  // an AMO reads (amoPhase 0), then writes (amoPhase 1); sc.w writes
  // only when the reservation holds.
  // RV32I assumes ready = 1 while the core runs (no stall from DMEM yet).
  val scWriteM = scM && resHitM
  val memWeM   = memWriteM || (amoM && amoPhase) || scWriteM
  io.dmem.req.valid      := validM && (memWriteM || memReadM || amoM || scWriteM) && !trapM
  io.dmem.req.bits.addr  := aluOutM
  io.dmem.req.bits.wen   := memWeM
  io.dmem.req.bits.wstrb := storeAlign.io.wstrb
  io.dmem.req.bits.wdata := storeAlign.io.wdata

  // MEM/WB input (class MemWb). The load data is not stored: DMEM reads
  // synchronously, so it arrives in WB as io.dmem.resp.
  memWbReg.io.in.valid     := validM
  memWbReg.io.in.pc        := pcM
  memWbReg.io.in.inst      := instM
  // A CSR instruction writes the old CSR value to rd: it replaces aluOut,
  // which held the source value and is not needed after MEM (WB stays the same).
  // AMO and SC also make their rd value here: the old word (on the DMEM
  // response in the AMO's second cycle; in WB it is gone) and 0 / 1.
  memWbReg.io.in.aluOut    := MuxCase(aluOutM, Seq(
    csrM -> csrFile.io.rdata,
    amoM -> io.dmem.resp.bits.rdata,
    scM  -> Mux(resHitM, 0.U, 1.U)))
  memWbReg.io.in.memAddr   := aluOutM          // for retire.memAddr only
  memWbReg.io.in.storeData := Mux(amoM, amoNewM, storeDataM)   // for retire.memWdata only
  memWbReg.io.in.rd        := rdM
  memWbReg.io.in.funct3    := funct3M
  memWbReg.io.in.regWrite  := regWriteM && !trapM   // a trap writes nothing
  memWbReg.io.in.wbSel     := wbSelM
  memWbReg.io.in.memWrite  := (memWriteM || amoM || scWriteM) && !trapM
  memWbReg.io.in.trap       := trapM
  memWbReg.io.in.interrupt  := trapUnit.io.interrupt
  memWbReg.io.in.cause      := trapUnit.io.cause
  memWbReg.io.in.tval       := trapUnit.io.tval
  memWbReg.io.in.trapTarget := pcRedirectM          // = mtvec when trap = 1

  // ---- WB inputs (from MEM/WB)
  val validW     = memWb.valid
  val pcW        = memWb.pc
  val instW      = memWb.inst
  val aluOutW    = memWb.aluOut
  val storeDataW = memWb.storeData
  val rdW        = memWb.rd
  val funct3W    = memWb.funct3
  val wbSelW     = memWb.wbSel
  val memWriteW  = memWb.memWrite
  val trapW       = memWb.trap
  val interruptW  = memWb.interrupt
  val causeW      = memWb.cause
  val tvalW       = memWb.tval
  val trapTargetW = memWb.trapTarget
  // validW once more as an extra guard: this writes the register file.
  val regWriteW  = validW && memWb.regWrite

  // Load: pick the bytes from the whole word and extend them.
  loadAlign.io.addrLow := aluOutW(1, 0)
  loadAlign.io.funct3  := funct3W
  loadAlign.io.rdata   := io.dmem.resp.bits.rdata
  val loadDataW = loadAlign.io.loadData

  // Write-back value (Wire in part A): also forwarded to ID and EX.
  resultW := Mux(wbSelW === WbSel.Load, loadDataW, aluOutW)

  // Register file write port (x0 is guarded inside RegFile).
  regFile.io.we    := regWriteW
  regFile.io.waddr := rdW
  regFile.io.wdata := resultW

  // ===========================================================================
  // F. Hazard unit, retire record, events
  // ===========================================================================
  // ---- hazard unit inputs. ID control signals are ANDed with validD
  // (valid rule); the other stages are already 0 for a bubble.
  // ID
  hazard.io.rs1D      := rs1D
  hazard.io.rs2D      := rs2D
  hazard.io.readsRs1D := validD && readsRs1D
  hazard.io.readsRs2D := validD && readsRs2D
  // an instruction with an exception never jumps, so takenD and jalD are
  // ANDed with !excD.valid. branchD and readsRs1D/2D are NOT: they decide the
  // stall, and during a stall excD may be wrong (the operands are not ready).
  // A jump needs !stall, so in that cycle excD is right.
  hazard.io.branchD   := validD && branchD
  hazard.io.jalD      := validD && jalD && !excD.valid
  hazard.io.takenD    := takenD && !excD.valid   // only used together with branchD
  // EX
  hazard.io.rs1E       := rs1E
  hazard.io.rs2E       := rs2E
  hazard.io.rdE        := rdE
  hazard.io.regWriteE  := regWriteE
  hazard.io.aluOutNotResultE := aluOutNotResultE
  hazard.io.jalrE      := jalrE
  hazard.io.busyE      := busyE              // made in part D
  // MEM
  hazard.io.rdM        := rdM
  hazard.io.regWriteM  := regWriteM
  hazard.io.aluOutNotResultM := aluOutNotResultM
  hazard.io.redirectM  := redirectM          // trap or mret
  hazard.io.busyM      := busyM              // AMO, first MEM cycle
  // WB
  hazard.io.rdW        := rdW
  hazard.io.regWriteW  := regWriteW

  // ---- retire record, all from WB.
  // A trap also gives one record (trap = 1): rdWe and memWe are 0 for it.
  io.retire.valid    := validW
  io.retire.pc       := pcW
  io.retire.insn     := instW
  io.retire.rdWe     := regWriteW
  io.retire.rdAddr   := rdW
  io.retire.rdData   := resultW
  io.retire.memWe    := validW && memWriteW
  io.retire.memAddr  := memWb.memAddr
  io.retire.memSize  := funct3W(1, 0)
  io.retire.memWdata := storeDataW
  io.retire.trap       := validW && trapW
  io.retire.interrupt  := interruptW
  io.retire.cause      := causeW
  io.retire.tval       := tvalW
  io.retire.trapTarget := trapTargetW

  // ---- events (one cycle each). Stall / flush kinds not in RV32I stay 0.
  io.events.stageValid(0) := fetchFire        // IF
  io.events.stageValid(1) := validD           // ID
  io.events.stageValid(2) := validE           // EX
  io.events.stageValid(3) := validM           // MEM
  io.events.stageValid(4) := validW           // WB
  io.events.stallLoadUse   := hazard.io.loadUse
  io.events.stallBranchUse := hazard.io.branchUse
  io.events.flushBranch    := hazard.io.pcSrcD && !hazard.io.jalD   // taken branch
  io.events.flushJump      := hazard.io.jalD || jalrE
  io.events.idFwdRs1       := hazard.io.forwardAD
  io.events.idFwdRs2       := hazard.io.forwardBD
  io.events.exFwdRs1       := hazard.io.forwardAE
  io.events.exFwdRs2       := hazard.io.forwardBE
  io.events.stallMulDiv    := busyE
  io.events.stallDmem      := busyM          // the AMO's extra MEM cycle
  io.events.flushTrap      := redirectM      // exception, interrupt or mret
}
