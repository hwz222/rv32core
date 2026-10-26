# Builds the directed tests: build/<name>.elf, one per t*.S file.
#
#   make -C verification/lockstep/tests
#
# Each test provides workload_main and is linked with the shared crt0.S and
# link.ld, exactly like the GEMM workload: crt0 sets sp, clears .bss, calls
# workload_main and writes 1 to tohost when it returns.

COMMON := ../../../workload/common

include $(COMMON)/toolchain.mk

FLAGS := -march=rv32ima_zicsr_zifencei -mabi=ilp32 \
         -nostdlib -nostartfiles -T $(COMMON)/link.ld

SRCS := $(wildcard t*.S)
ELFS := $(SRCS:%.S=build/%.elf)

.PHONY: all clean

all: $(ELFS)

build/%.elf: %.S $(COMMON)/crt0.S $(COMMON)/link.ld Makefile | build
	$(need_riscv_cc)
	$(CC_RISCV) $(FLAGS) -o $@ $(COMMON)/crt0.S $<

build:
	mkdir -p build

clean:
	rm -rf build
