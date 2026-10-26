# GEMM workload: C = A * B for any shape, as a host binary and an RV32IMA ELF.
#
#   make host  riscv                         8x8x8, seed 1
#   make host  riscv GEMM_M=13 GEMM_N=7 GEMM_K=5 GEMM_SEED=3
#   make clean
#
# Outputs, always under the same names whatever the shape:
#   gemm_host   native; prints the result, one %08x word per line (golden)
#   gemm.elf    bare metal, for isa_sim/run_isa_sim.sh and rtl/run_rtl_sim.sh
#
# A shape that does not fit in data memory fails at link time (the stack
# check in link.ld), not at run time.

GEMM_M    ?= 8
GEMM_N    ?= 8
GEMM_K    ?= 8
GEMM_SEED ?= 1

COMMON := ../common
include $(COMMON)/toolchain.mk

host:  gemm_host
riscv: gemm.elf
.PHONY: host riscv clean FORCE

# Make decides what to rebuild from file times, and GEMM_M and friends are
# not files. So the header is generated on every run, but only replaced when
# its text changed: rebuilding the same shape twice keeps the old header
# (and its time), switching shape always replaces it.
gemm_data.h: tools/make_gemm_data.py FORCE
	@python3 tools/make_gemm_data.py $(GEMM_M) $(GEMM_N) $(GEMM_K) $(GEMM_SEED) $@.new
	@if cmp -s $@.new $@; then rm -f $@.new; \
	 else mv -f $@.new $@; echo "gemm_data.h: $(GEMM_M)x$(GEMM_N)x$(GEMM_K) seed $(GEMM_SEED)"; fi

# Both binaries are rebuilt every time as well. They take well under a
# second, and a binary left over from another shape is the expensive bug.
gemm_host: gemm.c gemm_data.h $(COMMON)/harness.h FORCE
	$(CC_HOST) -DHOST_BUILD -O2 -I$(COMMON) -o $@ gemm.c

gemm.elf: gemm.c gemm_data.h $(COMMON)/harness.h $(COMMON)/crt0.S $(COMMON)/link.ld FORCE
	$(need_riscv_cc)
	$(CC_RISCV) $(RISCV_FLAGS) $(RISCV_LDFLAGS) -I$(COMMON) -o $@ $(COMMON)/crt0.S gemm.c

FORCE:

clean:
	rm -f gemm_data.h gemm_data.h.new gemm_host gemm.elf
