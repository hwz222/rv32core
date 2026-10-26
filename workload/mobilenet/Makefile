# MobileNetV1 (alpha 0.5, CIFAR-10) operator microbenchmarks.
#
# Each of the network's two convolution kinds runs alone, on one real layer
# of the quantized model, with a host build and a bare-metal build:
#
#   make bench_dw_host    -> ./bench_dw_host      native; stdout is the golden
#   make bench_dw_riscv   -> ./bench_dw.elf       RV32IMA, for isa_sim/ and rtl/
#   make bench_pw_host    -> ./bench_pw_host
#   make bench_pw_riscv   -> ./bench_pw.elf
#
# The shape is the layer's own, so there is no shape variable as in GEMM;
# the layer is a make variable instead (DW_LAYER, PW_LAYER).
#
# The weights come from model_int8/, the committed output of the offline
# quantization in workload/common/tools/qref.py. Building needs Python's
# standard library only: no torch, no numpy.

# Layer each benchmark runs.  Leave empty to let the generator pick the
# largest layer of that kind.
DW_LAYER   ?= blocks.2.dw
PW_LAYER   ?= blocks.4.pw
BENCH_SEED ?= 1

COMMON := ../common
MODEL  := model_int8

include $(COMMON)/toolchain.mk

# The kernels are shared with the other workload and with the full network.
KERNELS := $(COMMON)/nn_int8.c
DEPS    := $(COMMON)/nn_int8.h $(COMMON)/qops.h $(COMMON)/bench_run.h $(COMMON)/harness.h

.PHONY: all bench_dw_host bench_dw_riscv bench_pw_host bench_pw_riscv clean FORCE

all: bench_dw_host bench_dw_riscv bench_pw_host bench_pw_riscv

bench_dw_host:  bench_dw_host_bin
bench_dw_riscv: bench_dw.elf
bench_pw_host:  bench_pw_host_bin
bench_pw_riscv: bench_pw.elf

# Make only compares file times, and the layer name is not a file: without
# FORCE, switching DW_LAYER would reuse the data of the last layer built.
# Generate into .gen/ every time and copy back only what changed, so an
# unchanged layer does not force a rebuild.  Only the .c carries the recipe;
# the .h rides along, otherwise make runs the generator twice.
define gen_rule
$(1)_data.c: $(MODEL)/model.json $(MODEL)/weights.bin \
             tools/gen_bench_data.py $(COMMON)/tools/bench_data.py FORCE
	@mkdir -p .gen
	@python3 tools/gen_bench_data.py $(2) --model-dir $(MODEL) \
	    $$(if $(3),--layer $(3),) --seed $(BENCH_SEED) -o .gen/$(1)_data
	@cmp -s .gen/$(1)_data.h $(1)_data.h || cp -f .gen/$(1)_data.h $(1)_data.h
	@cmp -s .gen/$(1)_data.c $(1)_data.c || cp -f .gen/$(1)_data.c $(1)_data.c

$(1)_data.h: $(1)_data.c
	@:

endef

$(eval $(call gen_rule,bench_dw,dw,$(DW_LAYER)))
$(eval $(call gen_rule,bench_pw,pw,$(PW_LAYER)))

# Host build: native compiler, main() is the entry point, stdout is the golden.
bench_dw_host_bin: bench_dw.c bench_dw_data.c bench_dw_data.h $(KERNELS) $(DEPS)
	$(CC_HOST) -DHOST_BUILD -O2 -I$(COMMON) -o bench_dw_host \
	    bench_dw.c bench_dw_data.c $(KERNELS)

bench_pw_host_bin: bench_pw.c bench_pw_data.c bench_pw_data.h $(KERNELS) $(DEPS)
	$(CC_HOST) -DHOST_BUILD -O2 -I$(COMMON) -o bench_pw_host \
	    bench_pw.c bench_pw_data.c $(KERNELS)

# Bare-metal build: crt0.S first (it provides _start), then the workload.
define riscv_link
	$(need_riscv_cc)
	$(CC_RISCV) $(RISCV_FLAGS) $(RISCV_LDFLAGS) -I$(COMMON) -o $@ \
	    $(COMMON)/crt0.S $(1) $(2) $(KERNELS)
endef

bench_dw.elf: bench_dw.c bench_dw_data.c bench_dw_data.h $(KERNELS) $(DEPS) \
              $(COMMON)/crt0.S $(COMMON)/link.ld
	$(call riscv_link,bench_dw.c,bench_dw_data.c)

bench_pw.elf: bench_pw.c bench_pw_data.c bench_pw_data.h $(KERNELS) $(DEPS) \
              $(COMMON)/crt0.S $(COMMON)/link.ld
	$(call riscv_link,bench_pw.c,bench_pw_data.c)

FORCE:

clean:
	rm -rf bench_dw_host bench_pw_host bench_dw.elf bench_pw.elf \
	       bench_dw_data.c bench_dw_data.h bench_pw_data.c bench_pw_data.h .gen
