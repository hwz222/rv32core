#!/usr/bin/env bash
# Runs the directed tests in tests/ (pipeline hazards, traps, M, A) and
# writes a log next to this script.
#
#   run_directed.sh                 # on isa_sim  -> directed_isa_sim.log
#   run_directed.sh --rtl           # on the RTL  -> directed_rtl.log
#   run_directed.sh --rtl --keep D  # also keep every test's outputs in D
#
# The tests do not check their own answers: isa_sim is the reference.
#   isa_sim mode: PASS when isa_sim exits 0 (the test ran to the end).
#   rtl mode:     PASS when the RTL exits 0 AND its trace equals the isa_sim
#                 trace (compare_trace.py); otherwise FAIL with the RTL exit
#                 code and the first trace difference.
# The script exits 0 only when every test passes.
#
# RTL_SIM (optional) replaces rtl/run_rtl_sim.sh, only to test this script.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TESTS="$HERE/tests"
SIM="$REPO/isa_sim/isa_sim"
RTL="${RTL_SIM:-$REPO/rtl/run_rtl_sim.sh}"
COMPARE="$HERE/compare_trace.py"

RTL_MAX_CYCLES=1000000   # every test retires a few hundred instructions

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
LOG="$HERE/directed_$mode.log"

# ---- Build isa_sim and the tests ----------------------------------------------
make -s -C "$REPO/isa_sim" >&2 || exit 1
make -s -C "$TESTS" >&2 || exit 1

if [ -n "$keep" ]; then
    mkdir -p "$keep"
    tmp="$(cd "$keep" && pwd)"
else
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
fi

pass=0; fail=0
{
    echo "# directed tests on $mode"
    [ "$mode" = rtl ] && echo "# core (RTL_CORE):   ${RTL_CORE:-default of run_rtl_sim.sh}"
    [ "$mode" = rtl ] && echo "# PASS = RTL exit 0 and trace equal to isa_sim (compare_trace.py)"
    echo "# repository commit: $("$REPO/verification/repo_version.sh")"
    echo "# date:              $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$LOG"

for elf in "$TESTS"/build/t*.elf; do
    name="$(basename "$elf" .elf)"
    dir="$tmp/$name"
    mkdir -p "$dir"

    # isa_sim: the reference trace.
    "$SIM" "$elf" "$dir/isa" --no-dump --trace "$dir/isa.trace" >/dev/null 2>"$dir/isa.err"
    rc=$?

    if [ "$mode" = isa_sim ]; then
        if [ $rc -eq 0 ]; then
            status="PASS"; detail="$(wc -l < "$dir/isa.trace") trace lines"
        else
            status="FAIL"; detail="isa_sim exit $rc: $(tr '\n' ' ' < "$dir/isa.err")"
        fi
    else
        bash "$RTL" "$elf" "$dir/rtl" --no-dump --max-cycles "$RTL_MAX_CYCLES" \
            --trace "$dir/rtl.trace" >/dev/null 2>"$dir/rtl.err"
        rc_rtl=$?
        python3 "$COMPARE" "$dir/isa.trace" "$dir/rtl.trace" >"$dir/compare.txt" 2>&1
        rc_cmp=$?
        if [ $rc -ne 0 ]; then
            status="FAIL"; detail="isa_sim itself failed (exit $rc)"
        elif [ $rc_rtl -eq 0 ] && [ $rc_cmp -eq 0 ]; then
            status="PASS"; detail="$(head -n 1 "$dir/compare.txt")"
        else
            status="FAIL"; detail="rtl exit $rc_rtl; trace: $(head -n 1 "$dir/compare.txt")"
        fi
    fi
    [ "$status" = PASS ] && pass=$((pass + 1)) || fail=$((fail + 1))
    printf "%-5s %-16s %s\n" "$status" "$name" "$detail" | tee -a "$LOG"
done

summary="pass=$pass fail=$fail"
echo "# $summary" >> "$LOG"
# The core that really ran: read after the runs, when SimTop.v has been built.
[ "$mode" = rtl ] && echo "# core used: $(cat "$REPO/rtl/generated/SimTop.core" 2>/dev/null || echo unknown)" >> "$LOG"
echo "$summary"
[ -n "$keep" ] && echo "outputs kept in $tmp" >&2
[ $fail -eq 0 ]
