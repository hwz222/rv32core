#!/usr/bin/env python3
"""Fault injection: the mutants, and the summary of one run.

Each mutant turns off one hazard mechanism in HazardUnit.scala with a plain
text edit. run_fault_injection.sh applies one mutant at a time in a
separate git worktree, runs the test suites on it, and calls `summary`.

    fault_injection.py list                        names of the mutants
    fault_injection.py apply NAME WORKTREE         edit HazardUnit.scala in WORKTREE
    fault_injection.py summary NAME RUN_DIR        one block of fault_injection.log

RUN_DIR holds gemm.txt, directed_rtl.log and riscv_tests_rtl.log of one
mutant. Only the Python standard library is used.
"""
import re
import sys

HAZARD = "rtl/src/main/scala/rvsc/core/HazardUnit.scala"

# name -> (what is broken, [(old text, new text), ...])
# Every old text must appear exactly once in HazardUnit.scala.
MUTANTS = {
    "none": ("no change (the real design)", []),
    "loaduse_off": (
        "no load-use stall: a user right after a load/CSR is not held",
        [("  val loadUse = io.aluOutNotResultE",
          "  val loadUse = false.B && io.aluOutNotResultE")]),
    "branchuse_off": (
        "no branch-use stall: a branch in ID compares operands that are not ready",
        [("  val branchUse  = branchUseE || branchUseM",
          "  val branchUse  = false.B")]),
    "fwd_exmem_to_ex_off": (
        "no forwarding EX/MEM -> EX (ALUOutM)",
        [("when(io.regWriteM && io.rdM =/= 0.U && io.rdM === io.rs1E)", "when(false.B)"),
         ("when(io.regWriteM && io.rdM =/= 0.U && io.rdM === io.rs2E)", "when(false.B)")]),
    "fwd_memwb_to_ex_off": (
        "no forwarding MEM/WB -> EX (ResultW)",
        [(".elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs1E)", ".elsewhen(false.B)"),
         (".elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs2E)", ".elsewhen(false.B)")]),
    "fwd_memwb_to_id_off": (
        "no forwarding MEM/WB -> ID (needed because the register file is read-first)",
        [(".elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs1D)", ".elsewhen(false.B)"),
         (".elsewhen(io.regWriteW && io.rdW =/= 0.U && io.rdW === io.rs2D)", ".elsewhen(false.B)")]),
    "branch_flush_off": (
        "no flush after a taken branch or JAL: the next instruction also runs",
        [("io.flushD := pcSrcD || io.jalrE || io.redirectM",
          "io.flushD := io.jalrE || io.redirectM")]),
}

XFAIL_COUNT = 2   # rv32ui-p-fence_i, rv32ui-p-ma_data (run_riscv_tests.sh)


def apply(name, worktree):
    path = f"{worktree}/{HAZARD}"
    with open(path) as f:
        text = f.read()
    for old, new in MUTANTS[name][1]:
        if text.count(old) != 1:
            sys.exit(f"fault_injection: '{old}' is not in {HAZARD} exactly once")
        text = text.replace(old, new)
    with open(path, "w") as f:
        f.write(text)


def read_results(path, pattern):
    """(status, name, detail) for each result line of a runner log."""
    rows = []
    with open(path) as f:
        for line in f:
            m = re.match(pattern, line)
            if m:
                rows.append((m.group(1), m.group(2), m.group(3).strip()))
    return rows


def rtl_exit(detail):
    m = re.search(r"rtl exit (\d+)", detail)
    return int(m.group(1)) if m else 0


def first_line(detail):
    m = re.search(r"MISMATCH at line (\d+)", detail)
    return m.group(1) if m else "-"


def summary(name, run_dir):
    out = [f"== {name}: {MUTANTS[name][0]}"]

    # 1. GEMM: one line per shape, written by run_fault_injection.sh
    with open(f"{run_dir}/gemm.txt") as f:
        gemm = [line.split() for line in f if line.strip()]
    for shape, dump, trace in gemm:
        out.append(f"   gemm {shape:<8} dump vs host golden: {dump:<8} trace vs isa_sim: {trace}")

    # 2. directed tests: PASS needs the trace to equal isa_sim
    d = read_results(f"{run_dir}/directed_rtl.log", r"^(PASS|FAIL)\s+(\S+)\s+(.*)$")
    d_fail = [n for s, n, _ in d if s == "FAIL"]
    out.append(f"   directed  FAIL {len(d_fail)}/{len(d)}: {' '.join(d_fail) or '-'}")

    # 3. riscv-tests. Two views of the same run:
    #    self-check: what the test itself reports (RTL exit code), as if
    #                there were no trace compare
    #    lockstep:   PASS needs the RTL exit code AND the trace compare
    r = read_results(f"{run_dir}/riscv_tests_rtl.log",
                     r"^(PASS|FAIL|XFAIL|XPASS)\s+(\S+)\s*(.*)$")
    normal = [(s, n, x) for s, n, x in r if s in ("PASS", "FAIL") and "expected to fail" not in x]
    self_fail = [n for s, n, x in normal if s == "FAIL" and rtl_exit(x) != 0]
    lock_only = [n for s, n, x in normal if s == "FAIL" and rtl_exit(x) == 0]
    xpass = [n for s, n, _ in r if s == "XPASS"]
    lock_fail = [n for s, n, _ in r if s in ("FAIL", "XPASS")]
    lines = sorted({first_line(x) for s, _, x in r if s == "FAIL"} - {"-"}, key=int)
    out.append(f"   riscv-tests self-check reports failure: {len(self_fail)}/{len(normal)}"
               f"   expected failures reported as PASS: {len(xpass)}/{XFAIL_COUNT}")
    out.append(f"   riscv-tests FAIL with trace compare:    {len(lock_fail)}/{len(r)}"
               f"   found only by the trace compare: {len(lock_only)}")
    if lock_fail:
        shown = " ".join(lock_fail[:8]) + (" ..." if len(lock_fail) > 8 else "")
        out.append(f"   riscv-tests failing: {shown}")
        out.append(f"   first trace difference at line(s): {' '.join(lines[:8]) or '-'}")
    print("\n".join(out))


def main(argv):
    if len(argv) >= 2 and argv[1] == "list":
        print(" ".join(MUTANTS))
    elif len(argv) == 4 and argv[1] == "apply" and argv[2] in MUTANTS:
        apply(argv[2], argv[3])
    elif len(argv) == 4 and argv[1] == "summary" and argv[2] in MUTANTS:
        summary(argv[2], argv[3])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
