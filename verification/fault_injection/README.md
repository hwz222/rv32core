# Fault injection

A test suite that passes on the real design proves little by itself. It
proves that a mechanism works only if it FAILS when that mechanism is
broken. This directory breaks one hazard mechanism at a time and records
which suite notices.

## How it was made

```
bash verification/fault_injection/run_fault_injection.sh          # all mutants
bash verification/fault_injection/run_fault_injection.sh NAME ...  # some of them
```

The script makes a git worktree of HEAD in a temporary directory, so the
repository and its tracked logs are not touched. For each mutant in
`fault_injection.py` it edits one line of `HazardUnit.scala`, rebuilds the
RTL, and runs:

- GEMM at two shapes (8x8x8 and 13x7x5): RTL dump vs host golden, and RTL
  trace vs isa_sim trace
- the directed tests (`verification/lockstep/run_directed.sh --rtl`)
- riscv-tests (`verification/isa_compliance/run_riscv_tests.sh --rtl`),
  read two ways: the test's own check (the RTL exit code) alone, and the
  runner's rule (exit code AND an equal trace)

It writes `fault_injection.log`. It needs the riscv-tests ELFs
(`verification/isa_compliance/build_riscv_tests.sh`) and takes about 4
minutes per mutant.

## Result

| Mechanism turned off | GEMM golden diff | GEMM trace | Directed | riscv-tests, own check only | riscv-tests with trace |
|---|---|---|---|---|---|
| none (real design) | same | same | 0 / 13 fail | 0 / 58 fail | 0 / 60 fail |
| load-use stall | same | same | 6 / 13 fail | 7 / 58 fail | 7 / 60 fail |
| branch-use stall | same | different | 6 / 13 fail | 0 / 58 fail, 2 XPASS | 60 / 60 fail |
| forwarding EX/MEM -> EX | different | different | 13 / 13 fail | 58 / 58 fail | 60 / 60 fail |
| forwarding MEM/WB -> EX | same | same | 6 / 13 fail | 7 / 58 fail | 7 / 60 fail |
| forwarding MEM/WB -> ID | different | different | 13 / 13 fail | 57 / 58 fail | 58 / 60 fail |
| flush after a taken branch | different | different | 13 / 13 fail | 58 / 58 fail | 60 / 60 fail |

What it shows:

- The GEMM golden diff misses three of the six. The compiler keeps every
  load two instructions away from its use, so GEMM never needs the
  load-use stall or the MEM/WB -> EX forward.
- The directed tests and riscv-tests with the trace compare catch all six.
- riscv-tests' own check misses a broken branch-use stall completely. The
  `p` environment runs `li a0, 1; slli a0, a0, 31; bltz a0, 1f` before the
  test body; without the stall `bltz` reads the old `a0`, falls through to
  `RVTEST_PASS`, and every test reports success without running. Only the
  trace compare sees it (line 61, that `bltz`).
