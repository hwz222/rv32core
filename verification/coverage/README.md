# Functional coverage

`coverage_report.md` and `coverage_report.json` are the merged coverage of
every RTL run of the three verification suites: the 13 directed lockstep
tests, the 60 riscv-tests and the 9 GEMM lockstep shapes (82 runs,
2.5 million cycles).

## How it was made

Each RTL run writes `coverage.json` (the testbench samples the core's
`events` port every cycle and counts the retired instructions). The suites
keep those files with `--keep`, and `merge_coverage.py` adds them up:

```
bash verification/lockstep/run_directed.sh --rtl --keep /tmp/cov/directed
bash verification/isa_compliance/run_riscv_tests.sh --rtl --keep /tmp/cov/rvtests
bash verification/lockstep/run_gemm_lockstep.sh --keep /tmp/cov/gemm
python3 verification/coverage/merge_coverage.py /tmp/cov/directed /tmp/cov/rvtests /tmp/cov/gemm
```

Run the suites one after the other: they rebuild workloads and share
generated files.

## Result

| Category | Covered | What it measures |
|---|---|---|
| Opcodes | 66 / 68 | implemented instructions that retired |
| Traps | 9 / 9 | every exception cause the core can take |
| Stalls | 4 / 4 | load-use, branch-use, divide busy, AMO extra MEM cycle |
| Flushes | 3 / 3 | taken branch, JAL / JALR, trap / mret |
| Forwarding | 8 / 8 | every forwarding path: ID and EX, rs1 and rs2, from EX/MEM and MEM/WB |
| Pipeline states | 15 / 32 | which of the five stages held an instruction in the same cycle |

### The two opcodes "not covered"

`ecall` and `ebreak` always trap: they never retire as a normal instruction,
so the opcode counter never sees them. They were executed: the trap bins
`exception 11` (ecall, 59 times) and `exception 3` (ebreak) are covered.

### The 17 pipeline states not covered

None of them can happen in this design:

- **16 states with IF empty.** IMEM is always ready, and the core sends a
  fetch request every cycle, so IF holds an instruction in every sampled
  cycle. An instruction cache with IMEM waits would make these states
  possible.
- **`IF - - - -` (only IF busy).** The only event that empties ID, EX and MEM
  together is a trap or mret in MEM, and then that instruction is in WB in
  the next cycle (`IF - - - WB`, covered 382 times). The first sampled cycle
  after reset already has the first instruction in ID.

### Not applicable

- Interrupts 3, 7 and 11: the interrupt lines are tied to 0.
- The IMEM stall: instruction memory is always ready. The DMEM stall bin is
  counted: it is the extra MEM cycle of each AMO (33 AMOs, 33 cycles).
