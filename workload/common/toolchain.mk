# Bare-metal RV32IMA toolchain settings, included by every Makefile that
# builds an ELF for the ISA simulator or the RTL.
#
# The GCC prefix differs between distributions (Debian/Ubuntu ship
# riscv64-unknown-elf-, xPack ships riscv-none-elf-, Arch ships riscv64-elf-).
# All of them can target rv32ima with the flags below, so take the first
# prefix found in PATH. Override with `make CC_RISCV=/path/to/gcc`.
#
# The including Makefile must set COMMON to this directory first.

RISCV_PREFIXES := riscv64-unknown-elf riscv32-unknown-elf riscv-none-elf \
                  riscv64-elf riscv32-elf

ifeq ($(origin CC_RISCV),undefined)
CC_RISCV := $(firstword $(foreach p,$(RISCV_PREFIXES),\
                $(if $(shell command -v $(p)-gcc 2>/dev/null),$(p)-gcc)))
endif

CC_HOST ?= cc

# Architecture and optimization only, so that overriding RISCV_FLAGS on the
# command line cannot drop the link flags below.
RISCV_FLAGS ?= -march=rv32ima -mabi=ilp32 -O2

# No C library and no default startup code: crt0.S and link.ld replace them.
RISCV_LDFLAGS := -nostdlib -nostartfiles -T $(COMMON)/link.ld

# Use as the first line of a recipe that runs $(CC_RISCV).
define need_riscv_cc
@if [ -z "$(CC_RISCV)" ]; then \
	    echo "error: no RISC-V GCC in PATH (tried prefixes: $(RISCV_PREFIXES))" >&2; \
	    echo "       set one with: make $@ CC_RISCV=<riscv-gcc>" >&2; \
	    exit 1; \
	fi
endef
