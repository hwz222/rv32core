#!/usr/bin/env bash
# The ISA simulator's contract, including the paths a
# passing workload never takes.
#
# The shape sweep only ever exercises the happy path: a correct ELF that
# finishes. These are the cases where the simulator has to refuse, and refuse
# in a way that names the problem -- a simulator that accepts a broken ELF,
# or spins forever on a runaway program, hides bugs rather than finding them.
# The exit codes checked here are the ones documented in isa_sim/README.md.
#
#   bash verification/self_tests/run_sim_contract.sh
#
# Writes sim_contract.log next to this script. Exits non-zero on any failure.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SIM="$REPO/isa_sim/run_isa_sim.sh"
GEMM="$REPO/workload/gemm"
LOG="$HERE/sim_contract.log"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

CC_RISCV="${CC_RISCV:-$("$REPO/workload/common/find_riscv_gcc.sh")}"
FLAGS="-march=rv32ima -mabi=ilp32 -O2 -nostdlib -nostartfiles"

pass=0; fail=0
{
  echo "# $(date '+%Y-%m-%d %H:%M:%S')  repo $("$REPO/verification/repo_version.sh" short)"
  echo "# the ISA simulator's contract: what it must refuse, and how"
  echo
  printf '%-44s %-8s %-8s %s\n' "case" "want" "got" "result"
} > "$LOG"

check() {  # name expected_code actual_code note
    local want="$2" got="$3"
    if [ "$got" = "$want" ]; then
        printf '%-44s %-8s %-8s %s\n' "$1" "$want" "$got" "ok -- $4" >> "$LOG"
        pass=$((pass + 1))
    else
        printf '%-44s %-8s %-8s %s\n' "$1" "$want" "$got" "FAIL -- $4" >> "$LOG"
        fail=$((fail + 1))
    fi
}

run_sim() {  # elf [extra args...] -> prints the exit code
    local elf="$1"; shift
    rm -rf "$WORK/out"
    bash "$SIM" "$elf" "$WORK/out" "$@" >/dev/null 2>&1
    echo $?
}

# --- 1. the happy path still works, so a wrong code below means something ---
make -s -C "$GEMM" riscv GEMM_M=8 GEMM_N=8 GEMM_K=8 GEMM_SEED=1 >/dev/null 2>&1
cp "$GEMM/gemm.elf" "$WORK/good.elf"
check "a correct workload finishes" 0 "$(run_sim "$WORK/good.elf")" \
      "exit 0, tohost written"

# --- 2. a file that is not an ELF ---
head -c 4096 /dev/urandom > "$WORK/garbage.elf"
check "a file that is not an ELF is rejected" 2 "$(run_sim "$WORK/garbage.elf")" \
      "exit 2, bad ELF"

# --- 3. a path that does not exist ---
check "a missing file is rejected" 2 "$(run_sim "$WORK/nope.elf")" \
      "exit 2, cannot open"

# --- 4. a truncated ELF: a valid header, then nothing ---
head -c 64 "$WORK/good.elf" > "$WORK/truncated.elf"
check "a truncated ELF is rejected" 2 "$(run_sim "$WORK/truncated.elf")" \
      "exit 2, segments past the end of file"

# --- 5. an ELF with no result_ptr/result_nwords: the simulator cannot dump,
#        and must say so rather than write a garbage tensor ---
cat > "$WORK/nosym.c" <<'EOF'
/* no result_ptr, no result_nwords */
volatile unsigned int tohost_dummy;
void workload_main(void) { tohost_dummy = 1; }
EOF
$CC_RISCV $FLAGS -T "$REPO/workload/common/link.ld" -o "$WORK/nosym.elf" \
    "$REPO/workload/common/crt0.S" "$WORK/nosym.c" >/dev/null 2>&1
check "an ELF with no result symbols is rejected" 2 "$(run_sim "$WORK/nosym.elf")" \
      "exit 2, missing symbol"
check "  ... but --no-dump runs it anyway" 0 "$(run_sim "$WORK/nosym.elf" --no-dump)" \
      "exit 0, the dump was the only thing missing"

# --- 6. a runaway program must hit the instruction limit, not spin forever ---
cat > "$WORK/spin.c" <<'EOF'
/* never returns, so crt0 never writes tohost */
int spin_result[1];
int *result_ptr = spin_result;
unsigned int result_nwords = 1;
void workload_main(void) { for (;;) { __asm__ volatile(""); } }
EOF
$CC_RISCV $FLAGS -T "$REPO/workload/common/link.ld" -o "$WORK/spin.elf" \
    "$REPO/workload/common/crt0.S" "$WORK/spin.c" >/dev/null 2>&1
check "an infinite loop hits the instruction limit" 4 \
      "$(run_sim "$WORK/spin.elf" --max-insns 1000000)" \
      "exit 4, stopped instead of hanging"

# --- 7. an illegal instruction with no trap handler must stop with the cause,
#        not carry on executing whatever follows ---
cat > "$WORK/illegal.c" <<'EOF'
/* mtvec is 0, so the trap has nowhere to go */
int ill_result[1];
int *result_ptr = ill_result;
unsigned int result_nwords = 1;
void workload_main(void) { __asm__ volatile(".word 0xffffffff"); }
EOF
$CC_RISCV $FLAGS -T "$REPO/workload/common/link.ld" -o "$WORK/illegal.elf" \
    "$REPO/workload/common/crt0.S" "$WORK/illegal.c" >/dev/null 2>&1
check "an unhandled illegal instruction stops" 3 "$(run_sim "$WORK/illegal.elf")" \
      "exit 3, trap with mtvec == 0"

# --- 8. Harvard rules: a load from the instruction memory must be refused.
#        The two SRAMs are physically separate in the RTL, so a simulator that
#        allowed this would pass a program the hardware cannot run. ---
cat > "$WORK/cross.c" <<'EOF'
/* read from IMEM (address 0) with a load: not allowed on a Harvard core */
int cross_result[1];
int *result_ptr = cross_result;
unsigned int result_nwords = 1;
void workload_main(void) {
    volatile int *imem = (volatile int *)0x0;
    cross_result[0] = *imem;
}
EOF
$CC_RISCV $FLAGS -T "$REPO/workload/common/link.ld" -o "$WORK/cross.elf" \
    "$REPO/workload/common/crt0.S" "$WORK/cross.c" >/dev/null 2>&1
got=$(run_sim "$WORK/cross.elf")
if [ "$got" != "0" ]; then
    printf '%-44s %-8s %-8s %s\n' "a load from IMEM is refused" "non-zero" "$got" \
           "ok -- Harvard rule enforced" >> "$LOG"
    pass=$((pass + 1))
else
    printf '%-44s %-8s %-8s %s\n' "a load from IMEM is refused" "non-zero" "$got" \
           "FAIL -- the simulator allowed a cross-memory access" >> "$LOG"
    fail=$((fail + 1))
fi

# --- 9. the usage contract: too few arguments must fail, not guess ---
bash "$SIM" >/dev/null 2>&1; got=$?
check "no arguments is a usage error" 2 "$got" "exit 2, usage printed"

# --- 10. the output directory is created if it does not exist ---
rm -rf "$WORK/deep"
bash "$SIM" "$WORK/good.elf" "$WORK/deep/a/b" >/dev/null 2>&1
if [ -f "$WORK/deep/a/b/output_tensor.hex" ] && [ -f "$WORK/deep/a/b/report.json" ]; then
    printf '%-44s %-8s %-8s %s\n' "a missing output directory is created" "files" "files" \
           "ok -- both output files written" >> "$LOG"
    pass=$((pass + 1))
else
    printf '%-44s %-8s %-8s %s\n' "a missing output directory is created" "files" "missing" \
           "FAIL" >> "$LOG"
    fail=$((fail + 1))
fi

# --- 11. report.json must be valid JSON with the keys the tooling reads ---
if python3 -c "
import json,sys
d = json.load(open('$WORK/deep/a/b/report.json'))
sys.exit(0 if 'instructions_executed' in d and d['instructions_executed'] > 0 else 1)
" 2>/dev/null; then
    printf '%-44s %-8s %-8s %s\n' "report.json is valid and has a count" "valid" "valid" \
           "ok -- scripts parse it" >> "$LOG"
    pass=$((pass + 1))
else
    printf '%-44s %-8s %-8s %s\n' "report.json is valid and has a count" "valid" "bad" \
           "FAIL" >> "$LOG"
    fail=$((fail + 1))
fi

make -s -C "$GEMM" clean >/dev/null 2>&1
{ echo; echo "# $pass passed, $fail failed"; } >> "$LOG"
cat "$LOG"
[ "$fail" -eq 0 ]
