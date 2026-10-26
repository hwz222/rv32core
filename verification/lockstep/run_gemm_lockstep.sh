#!/usr/bin/env bash
# Instruction-level lockstep of the GEMM workload, ISA simulator
# against the RTL, at several shapes. Writes gemm_lockstep_rtl.log next to
# this script.
#
#   run_gemm_lockstep.sh              # every shape below
#   run_gemm_lockstep.sh --keep DIR   # also keep every shape's outputs in DIR
#
# For each shape:
#   1. Rebuild workload/gemm (host and riscv) at that shape and seed.
#   2. isa_sim writes the reference retire trace; the RTL (rtl/run_rtl_sim.sh)
#      writes its own trace, its SRAM dump and its cycle count.
#   3. compare_trace.py compares the two traces line by line: every retired
#      instruction, its rd write and its memory write (not only the
#      final state).
#   4. The RTL dump is also compared with the host golden (gemm_host), bit
#      exact.
# PASS needs the RTL to exit 0, the traces to match and the dump to match.
# The shapes rebuild workload/gemm: do not run this together with another
# script that builds workloads. The script exits 0 only when every shape passes.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
GEMM="$REPO/workload/gemm"
SIM="$REPO/isa_sim/isa_sim"
RTL="${RTL_SIM:-$REPO/rtl/run_rtl_sim.sh}"
COMPARE="$HERE/compare_trace.py"
LOG="$HERE/gemm_lockstep_rtl.log"
RTL_MAX_CYCLES=200000000

keep=""
while [ $# -gt 0 ]; do
    case "$1" in
        --keep) shift; keep="${1:-}"; [ -n "$keep" ] || { echo "error: --keep needs a directory" >&2; exit 2; } ;;
        *) echo "usage: $0 [--keep DIR]" >&2; exit 2 ;;
    esac
    shift
done

# M N K SEED | why this shape
SHAPES=(
  "8 8 8 1|the default shape"
  "13 7 5 2|no dimension shares a factor"
  "1 1 1 3|every loop runs once; the result is one word"
  "1 1 64 4|K only: one long dot product (load-use and accumulation chains)"
  "64 1 1 5|M only: the outer loop, single-term sums"
  "16 16 16 6|power-of-two cube"
  "17 23 9 7|odd sizes, all different"
  "32 32 32 8|about 33k multiply-accumulates"
  "64 64 64 9|the largest shape here, about 262k multiply-accumulates"
)

make -s -C "$REPO/isa_sim" >&2 || exit 1
if [ -n "$keep" ]; then
    mkdir -p "$keep"; tmp="$(cd "$keep" && pwd)"
else
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fi

pass=0; fail=0
{
    echo "# GEMM lockstep: isa_sim trace vs RTL trace, and RTL dump vs host golden"
    echo "# PASS = RTL exit 0, traces equal (compare_trace.py), dump equal to gemm_host"
    echo "# repository commit: $(git -C "$REPO" describe --always --dirty --abbrev=40 2>/dev/null || echo uncommitted)"
    echo "# date:              $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf "# %-5s %-12s %-8s %10s %10s %6s  %s\n" status "M N K seed" result instret cycles CPI "trace compare / note"
} > "$LOG"

for entry in "${SHAPES[@]}"; do
    shape="${entry%%|*}"; why="${entry#*|}"
    read -r m n k seed <<< "$shape"
    name="gemm_${m}x${n}x${k}_s${seed}"
    dir="$tmp/$name"; mkdir -p "$dir"

    if ! make -s -C "$GEMM" host riscv GEMM_M="$m" GEMM_N="$n" GEMM_K="$k" \
            GEMM_SEED="$seed" >"$dir/build.log" 2>&1; then
        status=FAIL; detail="build failed"; inst=-; cyc=-; cpi=-; dump=-
    else
        "$GEMM/gemm_host" > "$dir/golden.hex"
        "$SIM" "$GEMM/gemm.elf" "$dir/isa" --trace "$dir/isa.trace" >/dev/null 2>"$dir/isa.err"
        rc=$?
        bash "$RTL" "$GEMM/gemm.elf" "$dir/rtl" --max-cycles "$RTL_MAX_CYCLES" \
            --trace "$dir/rtl.trace" >/dev/null 2>"$dir/rtl.err"
        rc_rtl=$?
        python3 "$COMPARE" "$dir/isa.trace" "$dir/rtl.trace" >"$dir/compare.txt" 2>&1
        rc_cmp=$?
        if cmp -s "$dir/golden.hex" "$dir/rtl/data_sram_dump.hex"; then dump=match; else dump=MISMATCH; fi
        inst="$(wc -l < "$dir/rtl.trace" 2>/dev/null || echo 0)"
        cyc="$(cat "$dir/rtl/cycle_count.txt" 2>/dev/null || echo 0)"
        cpi="$(awk -v c="$cyc" -v i="$inst" 'BEGIN { if (i > 0) printf "%.3f", c / i; else print "-" }')"
        if [ $rc -ne 0 ]; then
            status=FAIL; detail="isa_sim exit $rc"
        elif [ $rc_rtl -eq 0 ] && [ $rc_cmp -eq 0 ] && [ "$dump" = match ]; then
            status=PASS; detail="$(head -n 1 "$dir/compare.txt")"
        else
            status=FAIL; detail="rtl exit $rc_rtl; trace: $(head -n 1 "$dir/compare.txt")"
        fi
    fi
    [ "$status" = PASS ] && pass=$((pass + 1)) || fail=$((fail + 1))
    printf "  %-5s %-12s %-8s %10s %10s %6s  %s (%s)\n" "$status" "$m $n $k $seed" "dump=$dump" \
        "$inst" "$cyc" "$cpi" "$detail" "$why" | tee -a "$LOG"
done

# Leave workload/gemm at its default shape for whoever builds next.
make -s -C "$GEMM" host riscv >/dev/null 2>&1

summary="pass=$pass fail=$fail"
echo "# $summary" >> "$LOG"
echo "# core used: $(cat "$REPO/rtl/generated/SimTop.core" 2>/dev/null || echo unknown)" >> "$LOG"
echo "$summary"
[ -n "$keep" ] && echo "outputs kept in $tmp" >&2
[ $fail -eq 0 ]
