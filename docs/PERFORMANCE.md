# Performance of the pipelined core

All numbers are from the RTL (Verilator) running the GEMM workload at `-O2`,
with ideal single-cycle IMEM and DMEM (no cache). They are reproduced by
`verification/lockstep/run_gemm_lockstep.sh`, which also checks every run
instruction by instruction against the ISA simulator; the log is
[`verification/lockstep/gemm_lockstep_rtl.log`](../verification/lockstep/gemm_lockstep_rtl.log).

## Cycle counts

Cycles are counted by the testbench from reset release to the store to
`tohost` (`cycle_count.txt`); instructions are the retired instructions in
the trace. Both include the startup code (clearing `.bss`).

| GEMM shape (M N K, seed) | Instructions | Cycles | CPI |
|---|---:|---:|---:|
| 8 8 8, 1 | 4,443 | 5,027 | 1.131 |
| 13 7 5, 2 | 4,483 | 5,036 | 1.123 |
| 16 16 16, 6 | 31,907 | 36,267 | 1.137 |
| 17 23 9, 7 | 29,468 | 33,385 | 1.133 |
| 32 32 32, 8 | 241,972 | 275,772 | 1.140 |
| 64 64 64, 9 | 1,884,695 | 2,150,942 | 1.141 |

## Where the cycles go

A cycle that does not retire an instruction has one of the causes below.
The counts come from the core's event port, which the testbench writes to
`coverage.json` for every run.

| Cause (cycles lost each time) | 8x8x8 | 13x7x5 | 64x64x64 |
|---|---:|---:|---:|
| Taken branch: the instruction fetched behind it is flushed (1) | 506 | 455 | 262,144 |
| JAL (1) / JALR (2) | 74 | 94 | 4,099 |
| Branch-use stall: a branch waits for its operand (1-2) | 1 | 1 | 1 |
| Load-use stall (1) | 0 | 0 | 0 |
| Divide busy / AMO extra MEM cycle | 0 | 0 | 0 |
| Pipeline fill and drain | 3 | 3 | 3 |
| **Cycles - instructions** | **584** | **553** | **266,247** |

GCC turns the inner loop into seven instructions per multiply-accumulate:

```
loop: lw   t0, 0(a0)        # A[i][k]
      lw   t2, 0(a6)        # B[k][j]
      addi a0, a0, 4
      addi a6, a6, 32       # next row of B (N * 4 bytes; N = 8 here)
      mul  t0, t0, t2
      add  a2, a2, t0
      bne  a0, t6, loop     # taken K-1 times out of K
```

The compiler already spaces each load two instructions from its use and the
loop counter four instructions from the branch, so there are no load-use
stalls and almost no branch-use stalls. What is left is the taken `bne` at
the end of every iteration: branches are resolved in ID, so the one
instruction already fetched behind it is thrown away. That is 8 cycles per
7 instructions, CPI 8/7 = 1.143, which is what large shapes approach
(1.141). At 64x64x64, **98.5% of the lost cycles are this branch flush**,
12% of all cycles.

## What limits performance, and what would help

1. **Taken loop branches (about 12% of all cycles).** A static
   "backward branch is taken" prediction in IF, or a small branch target
   buffer, would remove almost all of them. In software, unrolling the inner
   loop divides the number of branches by the unroll factor.
2. **Instructions per multiply-accumulate.** Five of the seven are address
   arithmetic and loop control. Unrolling and keeping one pointer per row
   would bring this down; this is the larger win, and it is a software
   change.
3. **Memory is ideal.** IMEM and DMEM answer in one cycle, so no cycles go
   to memory latency yet. With a data cache, GEMM's column walk through B
   (stride N * 4 bytes) would be the first thing to show up as misses.
4. **Timing (not measured in simulation).** The longest path is DMEM read
   data -> load align in WB -> forwarding to ID -> branch compare -> PC mux.
   A real implementation would add a cycle there, at the cost of more
   branch-use stalls.
