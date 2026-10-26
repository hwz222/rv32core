#!/bin/sh
# Print the first RISC-V GCC found in PATH, the same search as toolchain.mk.
# Used by the shell scripts that compile test programs themselves.
for prefix in riscv64-unknown-elf riscv32-unknown-elf riscv-none-elf riscv64-elf riscv32-elf; do
    if command -v "$prefix-gcc" >/dev/null 2>&1; then
        echo "$prefix-gcc"
        exit 0
    fi
done
echo "error: no RISC-V GCC in PATH; set CC_RISCV" >&2
exit 1
