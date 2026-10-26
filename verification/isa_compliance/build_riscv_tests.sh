#!/usr/bin/env bash
# Builds the official riscv-tests (https://github.com/riscv-software-src/riscv-tests)
# for OUR memory map, so that the ISA simulator and the RTL can run them.
#
#   Suites: rv32ui (base integer), rv32um (M), rv32ua (A), "p" environment:
#   machine mode, no virtual memory, one hart.
#
# The sources are cloned next to this script, into riscv-tests/ (ignored by
# git). Override with RISCV_TESTS_SRC and RISCV_TESTS_OUT. The ELFs are build output: they are not committed.
#
# Two things differ from the upstream build:
#   1. -T workload/common/link.ld instead of env/p/link.ld: the tests must use
#      our IMEM/DMEM layout, like every other program on this core.
#   2. -Wl,--no-relax: the tests use gp as the test-number register, but our
#      link.ld defines __global_pointer$. With relaxation on, the linker would
#      rewrite data accesses to be gp-relative and the tests would break.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="${RISCV_TESTS_SRC:-$HERE/riscv-tests}"
OUT="${RISCV_TESTS_OUT:-$SRC/build-scalar-core}"

if [ ! -d "$SRC/isa" ]; then
    git clone --recursive https://github.com/riscv-software-src/riscv-tests.git "$SRC"
fi

# Same compiler search as workload/common/toolchain.mk.
CC_RISCV="${CC_RISCV:-$("$REPO/workload/common/find_riscv_gcc.sh")}"
if [ -z "${CC_RISCV:-}" ]; then
    echo "error: no RISC-V compiler found; set CC_RISCV" >&2
    exit 1
fi

# Tests that are not part of RV32IMA and are not built at all.
#   amocas_w, amocas_d: atomic compare-and-swap is the Zacas extension, not A.
SKIP_BUILD=" amocas_w amocas_d "

# Read the official test list of a suite from its Makefrag.
list_tests() {
    make -s -f "$SRC/isa/$1/Makefrag" -f - print <<MK
print: ; @echo \$(${1}_sc_tests)
MK
}

mkdir -p "$OUT"
count=0
for suite in rv32ui rv32um rv32ua; do
    for t in $(list_tests "$suite"); do
        case "$SKIP_BUILD" in *" $t "*) continue ;; esac
        "$CC_RISCV" -march=rv32ima_zicsr_zifencei -mabi=ilp32 -static -mcmodel=medany \
            -fvisibility=hidden -nostdlib -nostartfiles \
            -I"$SRC/env/p" -I"$SRC/isa/macros/scalar" \
            -T "$REPO/workload/common/link.ld" -Wl,--no-relax \
            -o "$OUT/$suite-p-$t" "$SRC/isa/$suite/$t.S"
        count=$((count + 1))
    done
done
git -C "$SRC" rev-parse HEAD > "$OUT/COMMIT"
echo "built $count tests into $OUT (riscv-tests $(cut -c1-12 "$OUT/COMMIT"))"
