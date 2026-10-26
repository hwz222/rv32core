#!/usr/bin/env bash
# Fault injection: turn off one hazard mechanism at a time and check which
# test suite notices. Writes fault_injection.log next to this script.
#
#   run_fault_injection.sh               # every mutant in fault_injection.py
#   run_fault_injection.sh NAME ...      # only these mutants
#
# Why: a suite that passes on the real design proves little by itself. A
# suite is only useful for a mechanism if it FAILS when that mechanism is
# broken. Each mutant is a one-line edit of HazardUnit.scala.
#
# Steps:
#   1. Make a separate git worktree of HEAD in a temporary directory, so
#      the repository, its generated files and its tracked logs are not
#      touched. The worktree is removed at the end.
#   2. For each mutant: restore HazardUnit.scala, apply the mutant, then run
#        a. GEMM at two shapes: RTL dump vs host golden
#           and RTL trace vs isa_sim trace
#        b. the directed tests (verification/lockstep/run_directed.sh --rtl)
#        c. riscv-tests (verification/isa_compliance/run_riscv_tests.sh --rtl)
#      run_rtl_sim.sh rebuilds the Verilog because the Scala source changed.
#   3. fault_injection.py summary adds one block per mutant to the log.
#
# Needs the riscv-tests ELFs (build_riscv_tests.sh). Takes about 4 minutes
# per mutant. Only the committed HEAD is tested, not uncommitted changes.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FI="$HERE/fault_injection.py"
LOG="$HERE/fault_injection.log"
RT_OUT="${RISCV_TESTS_OUT:-$REPO/verification/isa_compliance/riscv-tests/build-scalar-core}"

if [ ! -f "$RT_OUT/COMMIT" ]; then
    echo "error: no riscv-tests in $RT_OUT; run verification/isa_compliance/build_riscv_tests.sh" >&2
    exit 1
fi
if [ $# -gt 0 ]; then names="$*"; else names="$(python3 "$FI" list)"; fi

# ---- 1. Worktree -------------------------------------------------------------
tmp="$(mktemp -d)"
WT="$tmp/worktree"
cleanup() {
    git -C "$REPO" worktree remove --force "$WT" 2>/dev/null
    rm -rf "$tmp"
}
trap cleanup EXIT
git -C "$REPO" worktree add --detach "$WT" HEAD >/dev/null 2>&1 || {
    echo "error: cannot create a worktree in $WT" >&2; exit 1; }
make -s -C "$WT/isa_sim" >&2 || exit 1

{
    echo "# Fault injection: one hazard mechanism turned off at a time (fault_injection.py)"
    echo "# repository commit:  $(git -C "$REPO" describe --always --dirty --abbrev=40 2>/dev/null || echo uncommitted)"
    echo "# riscv-tests commit: $(cat "$RT_OUT/COMMIT")"
    echo "# date:               $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "#"
    echo "# gemm         8x8x8 seed 1 and 13x7x5 seed 2: RTL dump vs host golden, RTL trace vs isa_sim"
    echo "# directed     run_directed.sh --rtl: PASS needs the trace to equal isa_sim"
    echo "# self-check   the riscv-test's own answer (RTL exit code), as if there were no trace compare"
    echo "# trace compare  run_riscv_tests.sh --rtl: PASS needs the exit code AND an equal trace"
    echo
} > "$LOG"

# ---- 2. Mutants --------------------------------------------------------------
for name in $names; do
    echo "fault_injection: $name" >&2
    run="$tmp/$name"; mkdir -p "$run"
    git -C "$WT" checkout -q -- rtl/src
    python3 "$FI" apply "$name" "$WT" || exit 1

    # a. GEMM at two shapes
    : > "$run/gemm.txt"
    for shape in "8 8 8 1" "13 7 5 2"; do
        read -r m n k seed <<< "$shape"
        d="$run/gemm_${m}x${n}x${k}"; mkdir -p "$d"
        dump=error; trace=error
        if make -s -C "$WT/workload/gemm" host riscv GEMM_M="$m" GEMM_N="$n" GEMM_K="$k" \
                GEMM_SEED="$seed" >"$d/build.log" 2>&1; then
            "$WT/workload/gemm/gemm_host" > "$d/golden.hex"
            "$WT/isa_sim/isa_sim" "$WT/workload/gemm/gemm.elf" "$d/isa" \
                --trace "$d/isa.trace" >/dev/null 2>&1
            bash "$WT/rtl/run_rtl_sim.sh" "$WT/workload/gemm/gemm.elf" "$d/rtl" \
                --trace "$d/rtl.trace" >/dev/null 2>"$d/rtl.err"
            if cmp -s "$d/golden.hex" "$d/rtl/data_sram_dump.hex"; then dump=match; else dump=MISMATCH; fi
            if python3 "$REPO/verification/lockstep/compare_trace.py" "$d/isa.trace" "$d/rtl.trace" \
                    >/dev/null 2>&1; then trace=equal; else trace=DIFFERENT; fi
        fi
        echo "${m}x${n}x${k} $dump $trace" >> "$run/gemm.txt"
    done

    # b. directed tests, c. riscv-tests (each writes its log inside the worktree)
    bash "$WT/verification/lockstep/run_directed.sh" --rtl >/dev/null 2>&1
    cp "$WT/verification/lockstep/directed_rtl.log" "$run/"
    bash "$WT/verification/isa_compliance/run_riscv_tests.sh" --rtl >/dev/null 2>&1
    cp "$WT/verification/isa_compliance/riscv_tests_rtl.log" "$run/"

    # 3. One block of the log
    python3 "$FI" summary "$name" "$run" | tee -a "$LOG"
    echo >> "$LOG"
done
echo "log: $LOG" >&2
