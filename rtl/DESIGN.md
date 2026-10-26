# RV32IMA Core: Design

A five-stage, in-order RV32IMA pipeline written in Chisel 3.5
(`rtl/src/main/scala/rvsc/`). Besides RV32IMA it implements only the small
part of machine mode that the unmodified riscv-tests environment needs: the
Zicsr instructions, machine-mode traps and `mret` (section 5); `fence` and
`fence.i` are accepted as no-ops.

Naming: block names in **bold** are the names in
[`microarch_diagram.svg`](microarch_diagram.svg); signal names are the
strings in `Core.scala` with the first letter in upper case (`RegWriteE` in
the diagram is `regWriteE` in the code). The suffix of a signal is the stage
that uses it: F, D, E, M, W.

Contents:

1. [Reading the diagram](#1-reading-the-diagram)
2. [Pipeline](#2-pipeline)
3. [Hazards](#3-hazards)
4. [Memories and the software interface](#4-memories-and-the-software-interface)
5. [Machine mode: CSRs and traps](#5-machine-mode-csrs-and-traps)
6. [Counting cycles and instructions](#6-counting-cycles-and-instructions)
7. [Limitations](#7-limitations)

## 1. Reading the diagram

Thin black lines are data, dashed red lines are control signals, thick blue
lines are the forwarding buses, orange lines are the inputs and outputs of
the hazard unit, and thick green lines are new PC values. Signals that go to
the same block share one line, labelled with all their names; a dot marks
where a signal joins or leaves a line, so a line may carry signals that its
stage does not use. A line with an arrow at both ends carries signals both
ways (`TrapUnit` with `Reservation`). Trapezoids are muxes, with the select
value next to each input. The grey boxes (instruction and data memory) are
outside the core, in `SimTop`. The `valid` bits and the ANDs with them are
not drawn.

MEM has three bands: `CsrFile`, `TrapUnit` and `Reservation` at the top,
`amoPhase` and the `aluOut` mux in the middle, and the store path,
`Data memory` and `AmoAlu` at the bottom. The hazard unit lists `PCSrcD`
and `JalrE`; `Core.scala` computes `JalrE` in EX and passes it in, with the
same logic.

## 2. Pipeline

Every instruction moves forward one stage per cycle unless the hazard unit
holds it (section 3). An instruction commits in MEM: that is the last stage
that can raise an exception, and the first that changes memory or a CSR
(section 5.3).

| Stage | Blocks (`core/`) | What it does |
|---|---|---|
| IF | **PCSel**, PC mux, **PCF**, **Instr memory** | Picks the next PC (section 3.2) and sends it to IMEM. IMEM reads synchronously, so the instruction arrives in the next cycle, in ID. During a stall IF sends **PCD** instead, so ID gets the same instruction again. |
| ID | **Control unit** (`Decoder`), **Register file** (`RegFile`, read first, write in WB), **ImmGen**, two forward muxes, **cmp** (`BranchCmp`), branch adder, **IdExcCheck** | Decodes; reads rs1 / rs2 and forwards them from MEM or WB; compares for branches; computes `PCD + ImmD` for branches and JAL, which jump from here; finds the exceptions known after decode. |
| EX | Two forward muxes, two source muxes, **ALU**, **Mul/Div** (`Multiplier`, one cycle; `Divider`, 34 cycles), result mux, **+4**, **ExExcCheck** | Computes the ALU result, the multiply / divide result or `pc + 4` (JAL / JALR link). The ALU also forms every memory address (`rs1 + imm`, `rs1 + 0` for A) and the JALR target, which jumps from here. Finds bad addresses and misaligned JALR targets. |
| MEM | **Data memory** port: the store-data mux (rs2, or the AMO's new word) and **align** (`StoreAlign`); **CsrFile**, **TrapUnit**, **AmoAlu**, **Reservation** (`resValid`, `resAddr`), **amoPhase** | Sends the DMEM request (the address is always `ALUOutM`); reads and writes the CSR; takes a trap or an mret; runs LR/SC and the two cycles of an AMO. The rd value of CSR, AMO and SC instructions is chosen here by the `aluOut` mux. |
| WB | **load align** (`LoadAlign`), result mux | Picks the bytes of the DMEM response for a load, writes rd, and sends the retire record to the testbench. |

What each pipeline register carries (`PipeRegs.scala`). `valid = 0` is a
bubble: every control field is 0, so a bubble never writes, jumps or stalls.

| Register | Data | Control |
|---|---|---|
| IF/ID | `pc` (the instruction itself is the IMEM response) | `valid` |
| ID/EX | `pc`, `inst`, `rs1Val`, `rs2Val`, `imm`, `rs1`, `rs2`, `rd`, `funct3`, exception (`excValid`, `excCause`, `excTval`) | `valid`, the whole `CtrlSignals` bundle |
| EX/MEM | `pc`, `inst`, `aluOut` (result or address), `storeData`, `rd`, `funct3`, exception | `valid`, `regWrite`, `wbSel`, `memWrite`, `memRead`, `aluOutNotResult`, `csr`, `csrCmd`, `csrWrite`, `mret`, `lr`, `sc`, `amo`, `amoOp` |
| MEM/WB | `pc`, `inst`, `aluOut` (the rd value, or the address for a load), `memAddr`, `storeData`, `rd`, `funct3`, trap (`trap`, `interrupt`, `cause`, `tval`, `trapTarget`) | `valid`, `regWrite`, `wbSel`, `memWrite` |

`pc`, `inst`, `memAddr`, `storeData` and the trap fields of MEM/WB only feed
the retire record, which the testbench turns into the lockstep trace.

## 3. Hazards

All stall, flush and forwarding decisions are in one combinational block,
**Hazard unit** (`HazardUnit.scala`). Each pipeline register has its own hold
(stall) and clear (flush) input; a flush wins over a stall.

### 3.1 Data hazards

**Forwarding.** Select codes are the same in ID and EX: `00` register file
(in EX: the value kept in ID/EX), `01` **ALUOutM**, `10` **ResultW**.

| To | From EX/MEM (`ALUOutM`) | From MEM/WB (`ResultW`) |
|---|---|---|
| EX rs1, rs2 (ALU, store data, JALR base) | yes | yes |
| ID rs1, rs2 (branch compare, and the value put in ID/EX) | yes, unless the MEM instruction has `aluOutNotResult` | yes |

`aluOutNotResult` marks instructions whose `aluOut` is not their rd value:
loads, CSR instructions, LR, SC and AMOs. Their value is known only in WB,
so it is forwarded from MEM/WB only. The register file reads before it
writes, so the ID forward from WB covers the write in the same cycle.

**Stalls** (one cycle each unless stated):

| Stall | Condition | Effect |
|---|---|---|
| load-use | EX holds an `aluOutNotResult` instruction whose rd is read by ID | hold PC, IF/ID; bubble into ID/EX |
| branch-use (EX) | a branch in ID reads the rd of any register-writing instruction in EX | same |
| branch-use (MEM) | a branch in ID reads the rd of an `aluOutNotResult` instruction in MEM | same; a load right before a branch costs 2 cycles |
| divide busy (`BusyE`) | a divide is in EX and its result is not ready (34 cycles in EX) | hold PC, IF/ID, ID/EX; bubble into EX/MEM |
| AMO (`busyM`) | the first MEM cycle of an AMO (read; it writes in the second) | hold PC, IF/ID, ID/EX, EX/MEM; bubble into MEM/WB |

Two details keep stalls correct:

- During any stall IMEM reads **PCD** again, because IF/ID keeps only the PC
  and the instruction comes straight from IMEM.
- While EX is held (`BusyE` or `busyM`), ID/EX stores the operands *after*
  EX forwarding (`rs1FwdE`, `rs2FwdE`) every cycle. Otherwise a value
  forwarded from WB is lost when that instruction leaves WB during the wait.
  The directed test `t11_amo` has a load -> AMO -> use sequence that fails
  without this.

### 3.2 Control hazards

| Event | Decided in | Clears | Cost |
|---|---|---|---|
| taken branch, JAL | ID (`PCSrcD`) | IF/ID | 1 cycle |
| JALR | EX (`JalrE`) | IF/ID, ID/EX | 2 cycles |
| trap or mret (`RedirectM`) | MEM (**TrapUnit**) | IF/ID, ID/EX, EX/MEM; the divider is reset | 3 cycles |

There is no branch prediction: IF always fetches `pc + 4`. The PC mux
priority is reset > `RedirectM` (11) > `JalrE` (10) > `PCSrcD` (01) > hold
during a stall > `pc + 4` (00): the oldest instruction wins, because the
younger jumps are on a wrong path. A jump in ID waits while EX or MEM is busy
(and a JALR waits while MEM is busy): jumping would clear the pipeline
register that holds the jump itself. An instruction with an exception never
jumps (`TakenD`, `JalD` and `JalrE` are ANDed with `!exc.valid`), but the
stall conditions do not look at exceptions: during a stall the operands are
not ready, so the exception decision of that cycle can be wrong.

### 3.3 Structural hazards

None. Instruction and data memories are separate (Harvard), the register
file has two read ports and one write port, and only MEM uses the data
memory port. An AMO uses that port twice, in its own two MEM cycles, while
the rest of the pipeline waits.

The hazard logic is checked from two sides: unit tests of the hazard unit
(`HazardUnitSpec.scala`), and fault injection, which turns off one stall or
forwarding path at a time and checks that the verification suites notice
(`verification/fault_injection/`).

## 4. Memories and the software interface

### 4.1 Memory map

The workloads, the ISA simulator and the RTL testbench share one memory
map. It is defined in one place, `workload/common/link.ld`, and every user
reads the resulting addresses out of the ELF (program headers and symbol
table). Nothing hardcodes an address, because section sizes and symbol
addresses move when the GEMM shape changes.

| Region | Base | Size | Contents |
|---|---|---|---|
| IMEM (instruction scratchpad) | `0x0000_0000` | 64 KB | `.text`; crt0's `.text.init` is placed first, so `_start` is at `0x0`, the reset PC |
| DMEM (data SRAM) | `0x1000_0000` | 2 MB | `.rodata`, `.data`/`.sdata`, `.tohost`, `.bss`/`.sbss`; the stack starts at the top |

Layout inside DMEM, from the bottom up: `.rodata`, `.data`, `.sdata`,
`.tohost`, `.bss`. The stack starts at `_stack_top` = `0x1020_0000` (one
address past the end of DMEM, because the stack subtracts from `sp` before
it stores) and grows down.

**Why IMEM and DMEM are separate.** Only instruction fetch reads IMEM. Every
load and store, including reads of `const` data, goes to DMEM, so `.rodata`
lives in DMEM as well. The ELF therefore contains exactly two `PT_LOAD`
segments: `R E` at `0x0` for the instruction scratchpad and `RW` at
`0x1000_0000` for the data SRAM, and the testbench loads one segment into
each memory. The RTL can tell the two apart with `addr[31:28]` alone.

**Capacity.** The linker reports an error if code does not fit in IMEM or
data does not fit in DMEM. The stack is not a section, so `link.ld` also
reserves `__stack_size` (4 KB) and asserts that
`__bss_end + __stack_size <= _stack_top`. Without that assertion, data that
nearly fills DMEM would link cleanly and then be overwritten by the stack at
run time. For GEMM the data is `(M*K + K*N + M*N) * 4` bytes, so the largest
square shape that fits is 417x417x417, and 418x418x418 fails to link (a
self-test checks both).

**Why 2 MB.** The size is set by the INT8 models. INT8 weights, INT32
biases, requantize parameters and activation buffers take about 753 KB for
ResNet18 with width 16 and 930 KB for MobileNetV1 with alpha 0.5, so each
model uses under half of DMEM. With 1 MB, MobileNet would use 91%. A larger
SRAM costs almost nothing in simulation; the real limit for these models is
simulation time, which depends on the number of multiply-accumulates, not
on memory size.

### 4.2 Startup, termination and the result

**Startup** (`workload/common/crt0.S`, entered at `0x0`):

1. `gp <- __global_pointer$`, which `link.ld` sets to the start of `.sdata`
   plus 2 KB. The instruction runs under `.option norelax`: otherwise the
   linker rewrites it into a gp-relative `mv gp, gp` and gp stays at its
   reset value. Once gp is correct, gp-relative code elsewhere is fine and
   saves one instruction per access.
2. `sp <- _stack_top`.
3. Clear `[__bss_start, __bss_end)` word by word. The ELF does not store the
   contents of `.bss`, and SRAM is not assumed to reset to zero.
4. `call workload_main`, the entry point every workload provides
   (`workload/common/harness.h`). The host build uses `main` instead and
   does not run crt0.

**Termination.** When `workload_main` returns, crt0 writes
`(exit_code << 1) | 1` (currently always `1`, meaning exit code 0) to the
word at symbol `tohost`, then spins. This is the riscv-tests HTIF
convention, so the same stop detection also works for the compliance tests.
The ISA simulator and the RTL testbench stop on a store of a non-zero value
to that address.

**Reading the result.** Every workload exports `result_ptr` (a pointer
variable: read the word at that symbol to get the address of the result) and
`result_nwords` (the number of 32-bit words). The simulator and the
testbench dump that range as one `%08x` word per line, which is the format
the host build prints.

### 4.3 Instruction memory

`IMem.scala`: 64 KB at `0x0000_0000`, one single-port synchronous RAM
(`SyncRam`) of 16,384 words. Before reset is released the testbench holds
`hostEn = 1` and writes every word of the ELF's `R E` segment through the
load port, one word per cycle; the core sees `ready = 0` and waits. After
that the core owns the port: one fetch per cycle, answered in the next
cycle, never a wait. Fetch only reads IMEM, so code cannot be written by
stores, and `fence.i` has nothing to synchronize (section 7). A PC outside
IMEM is an instruction access fault (cause 1), found in ID.

### 4.4 Data memory

`DMem.scala`: 2 MB at `0x1000_0000`, one synchronous single-port RAM with
byte write strobes, answered in the next cycle and always ready, so DMEM
costs no stall cycles. Like IMEM it has one port shared with the testbench
(`hostEn`): the testbench loads the `RW` segment before reset is released,
and takes the port again after the store to `tohost` to read the result.
There is no cache yet; the memory interfaces already have `ready` / `valid`
handshakes (`Interfaces.scala`), so a cache can sit between the MEM stage
and this RAM without changing the core's ports.

## 5. Machine mode: CSRs and traps

### 5.1 Scope: why the core has CSRs and traps

The workloads are bare-metal and never use a CSR or take a trap. The CSRs
and traps exist so that riscv-tests runs unmodified. Every riscv-tests
program is wrapped in the `p` environment (`env/p/riscv_test.h`), which
needs a small part of machine mode:

| What the environment does | What the core needs |
|---|---|
| reads `mhartid` first thing | the Zicsr instructions and a CSR file |
| writes CSRs that may not exist (`satp`, `pmpaddr0`, `mnstatus`) after pointing `mtvec` at the next instruction, so that the write traps and execution continues | an illegal-instruction trap to `mtvec` |
| enters the test body with `mret` | `mepc` and `mret` |
| reports the result with `ecall`; the handler reads `mcause` and writes `tohost` | the ecall trap and `mcause` |

The core implements exactly this (`verification/isa_compliance/README.md`).
It has one privilege level (machine mode), direct-mode `mtvec` only, and no
interrupts: `mie` and `mip` read as 0 and the interrupt lines are tied to 0,
although **TrapUnit** already has the interrupt inputs.

### 5.2 CSR file

`CsrFile.scala`, read and written in MEM. The six Zicsr instructions bring
their source value through the ALU (`rs1 + 0` or `0 + zimm`); the old CSR
value replaces `aluOut` on its way to MEM/WB, so it reaches rd like a load
result (`aluOutNotResult`).

| CSR | Behaviour |
|---|---|
| `mstatus` | MIE, MPIE writable; MPP reads as 11 (machine mode only) |
| `misa` | `0x40001101`: RV32, A, I, M; writes ignored |
| `mtvec` | direct mode only: bits 1:0 read as 0 |
| `mepc` | bits 1:0 read as 0 |
| `mcause`, `mtval`, `mscratch` | fully writable |
| `mcycle(h)`, `minstret(h)` | 64-bit counters, writable |
| `cycle(h)`, `instret(h)` | read-only views of `mcycle` / `minstret` |
| `time(h)` | read-only, equal to `mcycle` (there is no timer device) |
| `mhpmcounter3-31(h)`, `hpmcounter3-31(h)`, `mhpmevent3-31` | read as 0, writes ignored |
| `mie`, `mip`, `mstatush` | read as 0 |
| `mvendorid`, `marchid`, `mimpid`, `mhartid`, `mconfigptr` | read as 0 |

Any other address, or a write to a read-only CSR (address bits 11:10 = 11),
is an illegal instruction (cause 2). This is the trap the riscv-tests
environment relies on for `satp`, `pmpaddr0` and `mnstatus`.

### 5.3 Traps are taken in MEM

An exception is only recorded where it is found (the `exc` fields of ID/EX
and EX/MEM) and takes effect in MEM, when every older instruction has
already left MEM. **TrapUnit** then writes `mepc`, `mcause`, `mtval`, sets
MPIE <- MIE and MIE <- 0, and sends the PC to `mtvec`; it blocks the
instruction's DMEM request, CSR write and rd write, and clears the three
younger instructions. `mret` sets MIE <- MPIE, MPIE <- 1 and jumps to
`mepc`. A trap still produces one retire record, so the lockstep trace shows
it, but it does not count in `minstret` (same as the ISA simulator).

| Found in | Causes |
|---|---|
| ID (**IdExcCheck**) | 1 fetch outside IMEM, 2 illegal instruction, 11 ecall, 3 ebreak, 0 branch / JAL target not 4-byte aligned |
| EX (**ExExcCheck**) | 4 / 5 load (and LR) misaligned / outside DMEM, 6 / 7 store and AMO misaligned / outside DMEM, 6 SC misaligned, 0 JALR target misaligned |
| MEM | 2 illegal CSR access (CsrFile), 7 SC outside DMEM (only when the reservation holds, so it can only be decided where the reservation is) |

**Why taking traps in MEM is precise here.** A trap is precise when the
commit point is at or after the last place an exception can be found, and
nothing with a side effect (a store, a CSR write) happens before the commit
point. In this core every exception of a MEM instruction is known *before*
its DMEM request goes out: addresses are checked in EX, and the CSR and
reservation checks in MEM are combinational, in the same cycle that
**TrapUnit** blocks the request. It would not be precise with exceptions
that are only known after the access, such as an error response from memory
or a page fault from an MMU: the synchronous DMEM answers in WB, when the
next instruction may already have written memory in MEM. Such a design
would have to commit in WB (and delay stores to WB) or hold MEM until the
response is known.

## 6. Counting cycles and instructions

`mcycle` counts every cycle from reset release; `minstret` counts
instructions as they retire in WB. Both are 64 bits; a CSR write wins over
the count in the same cycle, and the instruction that writes `minstret` is
not counted. A read of `minstret` in MEM adds the instruction retiring in WB
in that cycle, so the value equals the ISA simulator's.

The cycle count of a run is measured by the testbench, not read from the
CSR: `cycle_count.txt` is the number of cycles from reset release to the
store that makes `tohost` non-zero. The testbench also writes
`coverage.json` with the stall, flush and forwarding counts that the cycle
breakdown in [`docs/PERFORMANCE.md`](../docs/PERFORMANCE.md) is built from.

## 7. Limitations

**ISA choices.** Both are allowed by the ISA, and each makes one riscv-tests
program fail in the same way on the ISA simulator and the RTL; that program
is an expected failure in `verification/isa_compliance/` (see its README).

- *Misaligned loads and stores trap* (cause 4 / 6) instead of being split
  into two accesses: `rv32ui-p-ma_data` fails.
- *No self-modifying code.* Fetch reads only IMEM and stores reach only
  DMEM, so `fence` and `fence.i` are no-ops. `rv32ui-p-fence_i` writes code
  into data memory and jumps to it, which is an instruction access fault
  here; `fence.i` is the Zifencei extension, outside RV32I(MA).

**Privileged architecture.** Only what section 5.1 lists: machine mode,
direct `mtvec`, no interrupts, and `wfi` is a no-op. The riscv-tests
`rv32mi` and `rv32si` suites (privileged architecture) are not run.

**A extension.** For one hart: `aq` / `rl` are ignored, and the reservation
is cleared only by SC and by a trap, not by an ordinary store to the same
word (the ISA simulator does the same).

**Performance and timing.**

- *No branch prediction.* Every taken branch costs one cycle and every JALR
  two; for GEMM this is about 12% of all cycles
  ([`docs/PERFORMANCE.md`](../docs/PERFORMANCE.md)).
- *Long combinational path.* DMEM read data -> load align -> forward to ID ->
  branch compare -> PC mux is one cycle. The design is only simulated; a real
  implementation would cut this path with an extra stall.

**Memory system.** IMEM and DMEM are always ready. The core already waits on
IMEM `ready = 0`, but DMEM waits (a data cache) are not handled yet;
`stallDmem` currently only counts the AMO's extra cycle.
