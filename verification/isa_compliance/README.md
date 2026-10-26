# ISA compliance

The ISA simulator and the RTL core both run the official
[riscv-tests](https://github.com/riscv-software-src/riscv-tests) suites for
the instructions they implement (RV32I, M, A), unmodified.

## Result

| Side | Log | PASS | Expected failures | FAIL |
|---|---|---:|---:|---:|
| ISA simulator | `riscv_tests_isa_sim.log` | 58 | 2 | 0 |
| RTL | `riscv_tests_rtl.log` | 58 | 2 | 0 |

Both logs were made from the same repository commit and the same
riscv-tests commit; each log names them in its header. On the RTL
side every test is also compared instruction by instruction with the ISA
simulator (below), so a PASS there means more than "the test printed PASS".

## Which tests, and why these

| Suite | Tests | Why it is in scope |
|---|---:|---|
| `rv32ui-p-*` | 42 | the base integer instructions (RV32I) |
| `rv32um-p-*` | 8 | the core implements M: `mul`, `mulh`, `mulhsu`, `mulhu`, `div`, `divu`, `rem`, `remu` |
| `rv32ua-p-*` | 10 | the core implements A: `lr.w`, `sc.w` and the nine AMOs |

All three use the `p` environment: machine mode, no virtual memory, one hart.

Not run, because they are outside RV32I(MA) or test something the core does
not implement:

- `rv32mi`, `rv32si`: the machine- and supervisor-mode privileged
  architecture (CSR behaviour, trap details, privilege levels). The core only
  implements the small part of machine mode described below.
- `rv32uc`, `rv32uf`, `rv32ud`, `rv32uzb*` and the other extensions: no
  compressed, floating-point or bit-manipulation instructions.
- the `v` environment: it needs virtual memory.

### Why the core still has CSRs and traps

The tests themselves only use user-level instructions, but the `p`
environment around them (`env/p/riscv_test.h`) needs a small part of machine
mode:

- it reads `mhartid` first thing (`csrr a0, mhartid`);
- it initializes CSRs that may not exist (`satp`, `pmpaddr0`, `mnstatus`) by
  pointing `mtvec` at the next instruction first, so that the write traps as
  an illegal instruction and execution continues there;
- it enters the test body with `mret` (from `mepc`);
- it reports the result with `ecall`: the trap handler reads `mcause` and
  then writes the result to `tohost`.

So the core implements the Zicsr instructions, the machine-mode trap
(`mtvec`, `mepc`, `mcause`, `mtval`, `mstatus`) and `mret`, but nothing more
(see `rtl/DESIGN.md`, section 5.1). This lets the official tests run without
changing a line of them. The other way, rewriting `riscv_test.h` without
CSRs, would mean testing a modified suite.

## The two expected failures

Both fail in the same way on the ISA simulator and the RTL, with the same
trace, so they are design decisions and not bugs:

- **`rv32ui-p-fence_i`** writes instructions into data memory and then jumps
  to them, which only works when instruction fetch can see data memory. This
  core has separate instruction and data memories (Harvard: an
  instruction scratchpad), and fetch reads only IMEM, so the jump target is an
  instruction access fault (mcause 1). `fence.i` belongs to the Zifencei
  extension, not to RV32I(MA).
- **`rv32ui-p-ma_data`** needs misaligned loads and stores to work. The ISA
  lets an implementation either support them or raise a misaligned exception;
  this core raises the exception (mcause 4 for loads, 6 for stores).

The list of expected failures, with these reasons, is in
`run_riscv_tests.sh`. A test on that list that unexpectedly passes is
reported as XPASS and counts as an error, so the list cannot go stale.

## How it runs

```bash
bash verification/isa_compliance/build_riscv_tests.sh      # once
bash verification/isa_compliance/run_riscv_tests.sh        # ISA simulator -> riscv_tests_isa_sim.log
bash verification/isa_compliance/run_riscv_tests.sh --rtl  # RTL           -> riscv_tests_rtl.log
```

**Build** (`build_riscv_tests.sh`). The riscv-tests sources are cloned
into `verification/isa_compliance/riscv-tests/` (ignored by git; override with
`RISCV_TESTS_SRC`); the ELFs are build output and are not committed. The test sources and `riscv_test.h` are
used unchanged. Two build options differ from the upstream build:
`-T workload/common/link.ld`, so the tests use this core's memory map like
every other program, and `-Wl,--no-relax`, because the tests keep the test
number in `gp`.

**Run** (`run_riscv_tests.sh`). Each test is self-checking: it writes 1 to
`tohost` when every case passes, and `(case number << 1) | 1` when one fails;
the simulators turn that into their exit code. Without `--rtl` only the ISA
simulator runs. With `--rtl` each test runs on both:

1. the ISA simulator writes the reference retire trace;
2. the RTL (`rtl/run_rtl_sim.sh`) writes its own trace and exit code;
3. `verification/lockstep/compare_trace.py` compares the two traces line by
   line: pc, instruction, rd write, memory write and trap of every retired
   instruction.

| Status | Meaning |
|---|---|
| PASS | exit code 0; on the RTL, also the trace equal to the ISA simulator's |
| XFAIL | an expected failure that does fail; on the RTL, with the same exit code and the same trace as the ISA simulator |
| XPASS | an expected failure that passed: the list is out of date (error) |
| FAIL | anything else; the log shows the exit code and, on the RTL, the first trace difference |

The script exits 0 only when there is no FAIL and no XPASS.
