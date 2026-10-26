#!/usr/bin/env bash
# GEMM shapes chosen to break the workload, the build or the simulator, plus
# build-discipline checks that no single shape can show.
#
# Part A -- shapes. For each: rebuild both targets, run the host binary for
# the golden, run the ELF on the ISA simulator, diff line by line.
# Part B -- build discipline: make clean, stale generated headers, both
# targets seeing the same tensors, and the simulator not modifying the ELF.
#
# The RTL runs the same shapes in verification/lockstep/run_gemm_lockstep.sh.
#
#   bash verification/self_tests/run_gemm_shapes.sh            everything
#   bash verification/self_tests/run_gemm_shapes.sh --quick    skip the slow shapes
#
# Writes gemm_shapes.log next to this script. Exits non-zero if anything
# fails, so it can be used as a gate.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
GEMM="$REPO/workload/gemm"
LOG="$HERE/gemm_shapes.log"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

pass=0; fail=0
note()  { printf '%s\n' "$*" >> "$LOG"; }
row()   { printf '%-20s %-10s %-10s %s\n' "$1" "$2" "$3" "$4" >> "$LOG"; }
good()  { pass=$((pass + 1)); }
bad()   { fail=$((fail + 1)); }

build() {  # M N K SEED
    make -s -C "$GEMM" host riscv GEMM_M="$1" GEMM_N="$2" GEMM_K="$3" \
         GEMM_SEED="$4" >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# Part A: shapes
# ---------------------------------------------------------------------------
SHAPES=(
  # -- degenerate: each dimension driven to 1 on its own, so a failure names
  #    the loop that is wrong
  "1 1 1 1|every loop bound is 1; the result is a single word"
  "1 1 64 2|K only: a dot product, M and N degenerate"
  "64 1 1 3|M only: a column of single-term sums, inner loop runs once"
  "1 64 1 4|N only: a row, K degenerate"
  "2 1 1 18|M=2: the smallest case where the outer loop iterates at all"
  "1 2 1 19|N=2: the smallest case where the middle loop iterates"
  "1 1 2 20|K=2: the smallest real accumulation, catches acc=a*b overwrite"
  # -- powers of two, the shape a tiled or blocked implementation is happiest
  #    with; here so a regression in the easy case is still caught
  "2 2 2 21|smallest power-of-two cube"
  "16 16 16 22|power-of-two cube, one tile for most tile sizes"
  "64 64 64 23|power-of-two cube, several tiles"
  # -- nothing shares a factor: breaks anything assuming a multiple of a tile
  #    width, a cache line or an SRAM word
  "13 7 5 5|no dimension shares a factor, none is a power of two"
  "17 19 23 6|three different primes"
  "7 11 13 24|three different primes, all below the first shape's"
  "1 16 33 7|a single row against a wide, odd K"
  # -- one past and one below a power of two: the two sides of <= versus <
  "33 33 33 11|cube, one past a power of two in every dimension"
  "31 31 31 12|cube, one below a power of two in every dimension"
  "65 63 64 25|one past, one below and exactly a power of two, together"
  # -- each dimension largest in turn: same work, different loop dominating
  "64 3 5 8|M largest by far"
  "3 64 5 9|N largest by far"
  "3 5 64 10|K largest by far"
  # -- rectangular and lopsided
  "100 1 100 26|M and K large with N=1: a matrix-vector product"
  "1 100 100 27|N and K large with M=1: a vector-matrix product"
  "128 96 112 14|non-square, result tensor 48 KB"
  # -- seed sensitivity: the result must change when only the seed changes
  "8 8 8 1|the default shape and seed"
  "8 8 8 13|the default shape, different seed"
)
SLOW=(
  "256 256 256 15|1.7e7 multiply-accumulates"
  "417 417 417 16|the largest cube the 2 MB data SRAM holds"
  "1 1 200000 28|the deepest accumulation the memory map allows (K=200000)"
)

{
  echo "# $(date '+%Y-%m-%d %H:%M:%S')  repo $("$REPO/verification/repo_version.sh" short)"
  echo "# host golden vs ISA simulator, bit for bit"
  echo
  echo "## Part A -- shapes"
  echo
  printf '%-20s %-10s %-10s %s\n' "shape (MxNxK s)" "host" "isa_sim" "what it stresses"
} > "$LOG"

run_one() {
    local spec="${1%%|*}" why="${1#*|}"
    read -r M N K S <<< "$spec"
    local label="${M}x${N}x${K} s$S"

    build "$M" "$N" "$K" "$S" || { row "$label" "BUILD FAIL" "-" "$why"; bad; return; }
    "$GEMM/gemm_host" > "$OUT/golden.hex" 2>/dev/null \
        || { row "$label" "RUN FAIL" "-" "$why"; bad; return; }
    rm -rf "$OUT/sim"
    bash "$REPO/isa_sim/run_isa_sim.sh" "$GEMM/gemm.elf" "$OUT/sim" >/dev/null 2>&1 \
        || { row "$label" "ok" "SIM FAIL" "$why"; bad; return; }
    if cmp -s "$OUT/golden.hex" "$OUT/sim/output_tensor.hex"; then
        row "$label" "ok" "ok" "$why"; good
    else
        row "$label" "ok" "DIFFERS" "$why"; bad
    fi
}

for s in "${SHAPES[@]}"; do run_one "$s"; done
if [ "$QUICK" = "0" ]; then
    for s in "${SLOW[@]}"; do run_one "$s"; done
else
    note "# slow shapes skipped (--quick)"
fi

# ---------------------------------------------------------------------------
# Part B: build discipline. None of these can be shown by running one shape.
# ---------------------------------------------------------------------------
{ echo; echo "## Part B -- build discipline and the simulator's contract"; echo; } >> "$LOG"
checkrow() { printf '%-46s %-10s %s\n' "$1" "$2" "$3" >> "$LOG"; }

# B1. A shape past the memory map must be REJECTED AT LINK TIME. Passing here
#     means NOT producing an ELF: a link that succeeded would give a binary
#     whose .bss overruns the stack at run time, which is far harder to find.
if make -s -C "$GEMM" riscv GEMM_M=418 GEMM_N=418 GEMM_K=418 GEMM_SEED=17 >/dev/null 2>&1; then
    checkrow "418x418x418 must not link" "BUILT" "link.ld ASSERT did not fire"; bad
else
    checkrow "418x418x418 must not link" "rejected" "link.ld ASSERT fires, as intended"; good
fi

# B2. make clean removes both artifacts and the generated header.
build 8 8 8 1 >/dev/null 2>&1
make -s -C "$GEMM" clean >/dev/null 2>&1
if [ ! -e "$GEMM/gemm_host" ] && [ ! -e "$GEMM/gemm.elf" ] && [ ! -e "$GEMM/gemm_data.h" ]; then
    checkrow "make clean removes host, elf and header" "ok" "nothing left behind"; good
else
    checkrow "make clean removes host, elf and header" "FAIL" "something was left behind"; bad
fi

# B3. Stale generated header. Make compares file times, and the shape is a
#     make variable, not a file: without care the PREVIOUS shape's binary
#     comes back. Build A, then B, then A again: the second A must equal
#     the first. This is the bug the FORCE rule in the Makefile exists for.
build 13 7 5 5  && "$GEMM/gemm_host" > "$OUT/a1.hex" 2>/dev/null
build 31 31 31 12 >/dev/null 2>&1
build 13 7 5 5  && "$GEMM/gemm_host" > "$OUT/a2.hex" 2>/dev/null
if cmp -s "$OUT/a1.hex" "$OUT/a2.hex"; then
    checkrow "rebuild A, B, A gives A's original answer" "ok" "no stale generated header"; good
else
    checkrow "rebuild A, B, A gives A's original answer" "FAIL" "a stale header survived"; bad
fi

# B4. The generator must be deterministic: one (M,N,K,seed) always gives the
#     same tensors, otherwise a golden made earlier describes a different
#     problem. Both builds include the one generated header, so building twice
#     from clean and comparing the header covers both.
build 17 19 23 6
sha_first=$(sha256sum "$GEMM/gemm_data.h" | cut -d' ' -f1)
make -s -C "$GEMM" clean >/dev/null 2>&1
build 17 19 23 6
sha_again=$(sha256sum "$GEMM/gemm_data.h" | cut -d' ' -f1)
if [ "$sha_first" = "$sha_again" ]; then
    checkrow "generator is deterministic across builds" "ok" "same tensors both times"; good
else
    checkrow "generator is deterministic across builds" "FAIL" "the generator is not deterministic"; bad
fi

# B5. Seed actually changes the data. If it does not, a cached golden or a
#     compile-time-folded GEMM would go unnoticed.
build 8 8 8 1  && "$GEMM/gemm_host" > "$OUT/s1.hex" 2>/dev/null
build 8 8 8 13 && "$GEMM/gemm_host" > "$OUT/s2.hex" 2>/dev/null
if cmp -s "$OUT/s1.hex" "$OUT/s2.hex"; then
    checkrow "a different seed changes the result" "FAIL" "same output: data may be folded away"; bad
else
    checkrow "a different seed changes the result" "ok" "the hide() barrier is doing its job"; good
fi

# B6. The simulator must not modify the ELF it runs.
build 33 33 33 11
sha_before=$(sha256sum "$GEMM/gemm.elf" | cut -d' ' -f1)
rm -rf "$OUT/sim"; bash "$REPO/isa_sim/run_isa_sim.sh" "$GEMM/gemm.elf" "$OUT/sim" >/dev/null 2>&1
sha_after=$(sha256sum "$GEMM/gemm.elf" | cut -d' ' -f1)
if [ "$sha_before" = "$sha_after" ]; then
    checkrow "isa_sim leaves the ELF byte identical" "ok" "same SHA-256"; good
else
    checkrow "isa_sim leaves the ELF byte identical" "FAIL" "the ELF changed"; bad
fi

# B7. The simulator is deterministic: same ELF twice, same output.
rm -rf "$OUT/sim2"; bash "$REPO/isa_sim/run_isa_sim.sh" "$GEMM/gemm.elf" "$OUT/sim2" >/dev/null 2>&1
if cmp -s "$OUT/sim/output_tensor.hex" "$OUT/sim2/output_tensor.hex"; then
    checkrow "isa_sim gives the same answer twice" "ok" "deterministic"; good
else
    checkrow "isa_sim gives the same answer twice" "FAIL" "not deterministic"; bad
fi

# B8. The instruction count must scale with the work. A GEMM folded at compile
#     time, or a simulator counting wrong, shows up as a flat count.
build 16 16 16 22
rm -rf "$OUT/i1"; bash "$REPO/isa_sim/run_isa_sim.sh" "$GEMM/gemm.elf" "$OUT/i1" >/dev/null 2>&1
n1=$(python3 -c "import json;print(json.load(open('$OUT/i1/report.json'))['instructions_executed'])")
build 32 32 32 22
rm -rf "$OUT/i2"; bash "$REPO/isa_sim/run_isa_sim.sh" "$GEMM/gemm.elf" "$OUT/i2" >/dev/null 2>&1
n2=$(python3 -c "import json;print(json.load(open('$OUT/i2/report.json'))['instructions_executed'])")
# 32^3 is 8x the work of 16^3; allow a wide band for loop overhead.
if [ "$n2" -gt $((n1 * 5)) ] && [ "$n2" -lt $((n1 * 12)) ]; then
    checkrow "instruction count scales with the work" "ok" "16^3 $n1 -> 32^3 $n2, about 8x"; good
else
    checkrow "instruction count scales with the work" "FAIL" "16^3 $n1 -> 32^3 $n2, expected about 8x"; bad
fi

make -s -C "$GEMM" clean >/dev/null 2>&1
{ echo; echo "# $pass passed, $fail failed"; } >> "$LOG"
cat "$LOG"
[ "$fail" -eq 0 ]
