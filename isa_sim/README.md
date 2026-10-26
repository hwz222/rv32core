# isa_sim — functional RV32IMA simulator

A functional RV32IMA instruction-set simulator in C++17. It runs the
same bare-metal ELF as the RTL and writes the result tensor, so its output
can be diffed against the host golden. It models no timing: one instruction
is one step.

## Usage

```
isa_sim/run_isa_sim.sh <elf> <out_dir> [options]     # builds, then runs
isa_sim/isa_sim        <elf> <out_dir> [options]     # the binary itself
```

`run_isa_sim.sh` builds the simulator from source first (`make -C isa_sim`),
so a run never uses a stale binary.

| Option | Meaning |
|---|---|
| `--trace FILE` | Write one line per retired instruction (format below). |
| `--max-insns N` | Stop with an error after N steps. Default 2,000,000,000. |
| `--no-dump` | Do not write `output_tensor.hex` (for programs without `result_ptr`, such as riscv-tests). |
| `--info` | Print the segments and the key symbols of the ELF, then exit. |

## Output

| File | Content |
|---|---|
| `<out_dir>/output_tensor.hex` | The result tensor: `result_nwords` words starting at the address stored in `result_ptr`, one `%08x` word per line. Same format as the host golden. Written only on success. |
| `<out_dir>/report.json` | `instructions_executed`, `stop_reason`, `tohost_value`, `exit_code`, and trap details if any. Always written. |

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The program wrote `1` to `tohost` (success). |
| 1 | The program wrote `(n << 1) \| 1` with `n != 0` to `tohost` (it reported failure `n`; riscv-tests use this). |
| 2 | Bad arguments, bad ELF, a segment outside IMEM/DMEM, or a missing symbol. |
| 3 | A trap with no handler (`mtvec == 0`). The cause, pc and tval are printed. |
| 4 | Instruction limit reached without writing `tohost` (probably an infinite loop). |
| 5 | Cannot write the output files, or the result range is outside DMEM. |
| 6 | An even non-zero value was written to `tohost` (a host system call; not supported). |

## What is modeled

**ISA:** RV32I, M, A, Zicsr, Zifencei. Machine mode only. No interrupts.
That is a deliberate choice: the bare-metal workloads need neither other
privilege modes nor interrupts, and riscv-tests' `p` environment only needs
the machine-mode subset listed below.

| Group | Instructions |
|---|---|
| RV32I | LUI AUIPC JAL JALR, BEQ BNE BLT BGE BLTU BGEU, LB LH LW LBU LHU, SB SH SW, ADDI SLTI SLTIU XORI ORI ANDI SLLI SRLI SRAI, ADD SUB SLL SLT SLTU XOR SRL SRA OR AND, FENCE, ECALL, EBREAK |
| Zifencei | FENCE.I (no-op: there is one hart and no cache in this model) |
| M | MUL MULH MULHSU MULHU DIV DIVU REM REMU (division by zero and overflow follow the spec, no trap) |
| A | LR.W SC.W AMOSWAP/ADD/XOR/AND/OR/MIN/MAX/MINU/MAXU.W (one hart; aq/rl have no effect) |
| Zicsr | CSRRW CSRRS CSRRC CSRRWI CSRRSI CSRRCI |
| Privileged | MRET, WFI (no-op) |

**CSRs:** `mstatus` (MIE, MPIE; MPP always reads M), `misa` (`0x40001101`:
RV32 with A, I, M), `mtvec` (direct mode), `mepc`, `mcause`, `mtval`,
`mscratch`, `mie`/`mip` (always 0), `mcycle[h]`, `minstret[h]`, `cycle[h]`,
`time[h]`, `instret[h]`, `mvendorid`/`marchid`/`mimpid`/`mhartid`/`mconfigptr`
(0), and the hardware performance counters 3..31 (always 0). There is no
timing, so one retired instruction is one cycle, and `time` reads the cycle
count. Any other CSR raises an illegal-instruction exception.

**Memory:** the same map as `workload/common/link.ld` and `rtl/DESIGN.md`
section 4.1 (see `src/memmap.h`). Like the RTL, it is a Harvard design:

- instruction fetch can only read IMEM (`0x0000_0000`, 64 KB)
- loads and stores can only reach DMEM (`0x1000_0000`, 2 MB)
- anything else is an access fault; a misaligned access is a misaligned
  exception (the ISA allows this instead of supporting it in hardware)

## Traps and stopping

- A trap sets `mepc`, `mcause`, `mtval` and `mstatus`, then jumps to `mtvec`.
- Our crt0 sets no handler, so `mtvec` stays 0. A trap then **stops** the
  run with exit code 3 and prints the cause, instead of jumping to address
  0 and silently restarting the program.
- The run stops when a store writes a non-zero value to the `tohost`
  symbol (the riscv-tests HTIF convention, see `workload/common/crt0.S`).

## Trace format

One line per retired instruction, and one line per trap:

```
<n> <pc> <insn> [x<rd>=<value>] [mem<bits>[<addr>]=<value>]
<n> <pc> trap cause=<mcause> tval=<tval>
```

`n` counts retired instructions from 0. All numbers are hex except `n`,
`rd`, `bits` and `cause`. Example:

```
1 00000004 88c18193 x3=1000088c
8 00000020 0002a023 mem32[100000c8]=00000000
```

The RTL testbench writes the same format, so the lockstep check
(`verification/lockstep/compare_trace.py`) compares the two line by line.

## Verification

- **GEMM and the operator benchmarks vs the host build:**
  `verification/self_tests/` (GEMM shapes, every operator layer of both
  models, and the simulator's own error paths).
- **riscv-tests:** `verification/isa_compliance/build_riscv_tests.sh` then
  `run_riscv_tests.sh`. Result: all rv32ui, rv32um and rv32ua tests pass,
  except two that fail by design:

  | Test | Why it fails on this core |
  |---|---|
  | `rv32ui-p-fence_i` | Copies instructions into data memory and jumps to them. Fetch can only read IMEM, so the fetch faults (mcause 1). |
  | `rv32ui-p-ma_data` | Needs misaligned loads and stores. This core raises a misaligned exception instead (mcause 4/6). |

  `amocas_w`/`amocas_d` are not built: compare-and-swap is the Zacas
  extension, not part of A.

## Files

| File | Content |
|---|---|
| `src/main.cpp` | Command line, symbol lookup, output files, exit codes |
| `src/elf.{h,cpp}` | ELF32 reader: header checks, PT_LOAD segments, symbol table |
| `src/memmap.h` | The memory map constants |
| `src/memory.{h,cpp}` | IMEM/DMEM with the Harvard access rules and fault codes |
| `src/decode.h` | Instruction fields and immediates |
| `src/hart.{h,cpp}` | Registers, CSRs, traps, and the execute loop |
