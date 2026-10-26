#!/usr/bin/env bash
# Runs the riscv-tests built by build_riscv_tests.sh and writes a log next
# to this script.
#
#   run_riscv_tests.sh                 # on the ISA simulator -> riscv_tests_isa_sim.log
#   run_riscv_tests.sh --rtl           # on the RTL core      -> riscv_tests_rtl.log
#   run_riscv_tests.sh --rtl --keep D  # also keep every test's outputs in D
#
# A test checks its own answers. It writes 1 to tohost when every sub-test
# passes, and (test_number << 1) | 1 when one fails; the simulator then
# exits with a non-zero code.
#
# With --rtl each test runs on both isa_sim and the RTL (rtl/run_rtl_sim.sh),
# each writing a retire trace, and the two traces are compared with
# verification/lockstep/compare_trace.py:
#   PASS   the RTL exits 0 and its trace equals the isa_sim trace
#   XFAIL  (expected failures) the RTL exits with the same code as isa_sim
#          and its trace equals the isa_sim trace: it fails the same way
#   XPASS  an expected failure passed: the XFAIL list is out of date
#   FAIL   anything else; the log shows the RTL exit code and the first
#          trace difference
# The script exits 0 only when there is no FAIL and no XPASS.
#
# RTL_SIM (optional) replaces rtl/run_rtl_sim.sh. It is only for testing this
# script itself, e.g. with a wrapper that runs isa_sim: then every test must
# be PASS or XFAIL.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="${RISCV_TESTS_SRC:-$HERE/riscv-tests}"
OUT="${RISCV_TESTS_OUT:-$SRC/build-scalar-core}"
SIM="$REPO/isa_sim/isa_sim"
RTL="${RTL_SIM:-$REPO/rtl/run_rtl_sim.sh}"   # RTL_SIM: only to test this script
COMPARE="$REPO/verification/lockstep/compare_trace.py"

RTL_MAX_CYCLES=1000000   # every test retires well under 1000 instructions

# ---- Options -----------------------------------------------------------------
mode=isa_sim
keep=""
while [ $# -gt 0 ]; do
    case "$1" in
        --rtl) mode=rtl ;;
        --keep) shift; keep="${1:-}"; [ -n "$keep" ] || { echo "error: --keep needs a directory" >&2; exit 2; } ;;
        *) echo "usage: $0 [--rtl] [--keep DIR]" >&2; exit 2 ;;
    esac
    shift
done
LOG="$HERE/riscv_tests_$mode.log"

# Tests that are expected to fail on this core, by design.
declare -A XFAIL=(
    [rv32ui-p-fence_i]="copies instructions into data memory and jumps to them; fetch can only read IMEM (Harvard design), so the fetch faults (mcause 1)"
    [rv32ui-p-ma_data]="needs misaligned loads/stores; this core raises a misaligned exception instead (mcause 4/6, allowed by the ISA)"
)

if [ ! -f "$OUT/COMMIT" ]; then
    echo "error: no tests in $OUT; run build_riscv_tests.sh first" >&2
    exit 1
fi
make -s -C "$REPO/isa_sim" >&2

# Working directory: kept with --keep, otherwise removed at the end.
if [ -n "$keep" ]; then
    mkdir -p "$keep"
    tmp="$(cd "$keep" && pwd)"
else
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
fi

pass=0; fail=0; xfail=0; xpass=0
{
    echo "# riscv-tests on $mode"
    [ "$mode" = rtl ] && echo "# core (RTL_CORE):    ${RTL_CORE:-default of run_rtl_sim.sh}"
    [ "$mode" = rtl ] && echo "# PASS = RTL exit 0 and trace equal to isa_sim (compare_trace.py)"
    echo "# riscv-tests commit: $(cat "$OUT/COMMIT")"
    echo "# repository commit:  $(git -C "$REPO" describe --always --dirty --abbrev=40 2>/dev/null || echo uncommitted)"
    echo "# date:               $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$LOG"

# ---- One test on isa_sim: sets rc ------------------------------------------
run_isa_sim() {   # elf dir
    "$SIM" "$1" "$2/isa" --no-dump --max-insns 10000000 --trace "$2/isa.trace" \
        >/dev/null 2>"$2/isa.err"
    rc=$?
}

# ---- One test on the RTL: sets rc_rtl and cmp (first line of the compare) ---
run_rtl() {   # elf dir
    bash "$RTL" "$1" "$2/rtl" --no-dump --max-cycles "$RTL_MAX_CYCLES" \
        --trace "$2/rtl.trace" >/dev/null 2>"$2/rtl.err"
    rc_rtl=$?
    python3 "$COMPARE" "$2/isa.trace" "$2/rtl.trace" >"$2/compare.txt" 2>&1
    rc_cmp=$?
    cmp="$(head -n 1 "$2/compare.txt")"
}

for elf in "$OUT"/rv32u*-p-*; do
    name="$(basename "$elf")"
    dir="$tmp/$name"
    mkdir -p "$dir"
    run_isa_sim "$elf" "$dir"

    if [ "$mode" = isa_sim ]; then
        # ISA simulator only: decide by the exit code.
        if [ -n "${XFAIL[$name]:-}" ]; then
            if [ $rc -eq 0 ]; then status="XPASS"; xpass=$((xpass + 1))
            else status="XFAIL"; xfail=$((xfail + 1)); fi
            detail="${XFAIL[$name]}"
        elif [ $rc -eq 0 ]; then
            status="PASS"; pass=$((pass + 1)); detail=""
        else
            status="FAIL"; fail=$((fail + 1)); detail="exit $rc: $(tr '\n' ' ' < "$dir/isa.err")"
        fi
    else
        # RTL: decide by the RTL exit code and the trace compare.
        run_rtl "$elf" "$dir"
        if [ -n "${XFAIL[$name]:-}" ]; then
            if [ $rc_rtl -eq 0 ]; then
                status="XPASS"; xpass=$((xpass + 1)); detail="${XFAIL[$name]}"
            elif [ $rc_rtl -eq $rc ] && [ $rc_cmp -eq 0 ]; then
                status="XFAIL"; xfail=$((xfail + 1)); detail="${XFAIL[$name]}"
            else
                status="FAIL"; fail=$((fail + 1))
                detail="expected to fail like isa_sim (exit $rc); rtl exit $rc_rtl; trace: $cmp"
            fi
        elif [ $rc_rtl -eq 0 ] && [ $rc_cmp -eq 0 ]; then
            status="PASS"; pass=$((pass + 1)); detail=""
        else
            status="FAIL"; fail=$((fail + 1)); detail="rtl exit $rc_rtl; trace: $cmp"
        fi
    fi
    printf "%-6s %-24s %s\n" "$status" "$name" "$detail" | tee -a "$LOG"
done

summary="pass=$pass fail=$fail xfail=$xfail xpass=$xpass"
echo "# $summary" >> "$LOG"
# The core that really ran: read after the runs, when SimTop.v has been built.
[ "$mode" = rtl ] && echo "# core used: $(cat "$REPO/rtl/generated/SimTop.core" 2>/dev/null || echo unknown)" >> "$LOG"
echo "$summary"
[ -n "$keep" ] && echo "outputs kept in $tmp" >&2
# XPASS also counts as a problem: the XFAIL list above is out of date.
[ $fail -eq 0 ] && [ $xpass -eq 0 ]
