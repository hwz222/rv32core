# Bit-exact check of the INT8 models: host C, RISC-V C and tools/qref.py
# must give the same logits. Driven by check_bitexact.py; see README.md.
#
#   make host  MODEL=resnet18_w16                  -> eval_host_<model>
#   make riscv MODEL=resnet18_w16 START=0 COUNT=200 -> eval_<model>_<start>_<count>.elf
#
# The test images are not in the repository (see README.md). Generated
# sources and binaries are build output and are ignored by git.

MODEL ?= resnet18_w16
START ?= 0
COUNT ?= 200

COMMON   := ../../workload/common
EVAL_DIR ?= ../../../ml_workspace/int8_eval/$(MODEL)

ifeq ($(MODEL),resnet18_w16)
MODEL_DIR := ../../workload/resnet18/model_int8
else ifeq ($(MODEL),mobilenet_v1_a050)
MODEL_DIR := ../../workload/mobilenet/model_int8
else
$(error unknown MODEL $(MODEL))
endif

include $(COMMON)/toolchain.mk

GEN    := python3 $(COMMON)/tools/gen_model_c.py
MDATA  := model_$(MODEL)
IMAGES := images_$(MODEL)_$(START)_$(COUNT)
ELF    := eval_$(MODEL)_$(START)_$(COUNT).elf

.PHONY: host riscv clean
host: eval_host_$(MODEL)
riscv: $(ELF)

# model_data.h is the name eval.c includes; each model gets its own copy.
$(MDATA)/model_data.c $(MDATA)/model_data.h: $(MODEL_DIR)/model.json $(MODEL_DIR)/weights.bin
	mkdir -p $(MDATA)
	$(GEN) model --model-dir $(MODEL_DIR) --out $(MDATA)/model_data

$(IMAGES)/images_data.c $(IMAGES)/images_data.h: $(EVAL_DIR)/test_images_q.bin
	mkdir -p $(IMAGES)
	$(GEN) images --images $< --start $(START) --count $(COUNT) --out $(IMAGES)/images_data

eval_host_$(MODEL): eval.c $(COMMON)/nn_int8.c $(MDATA)/model_data.c $(MDATA)/model_data.h
	$(CC_HOST) -DHOST_BUILD -O2 -I$(COMMON) -I$(MDATA) -o $@ eval.c $(COMMON)/nn_int8.c $(MDATA)/model_data.c

$(ELF): eval.c $(COMMON)/nn_int8.c $(MDATA)/model_data.c $(IMAGES)/images_data.c $(COMMON)/crt0.S $(COMMON)/link.ld
	$(need_riscv_cc)
	$(CC_RISCV) $(RISCV_FLAGS) $(RISCV_LDFLAGS) -I$(COMMON) -I$(MDATA) -I$(IMAGES) -o $@ \
	    $(COMMON)/crt0.S eval.c $(COMMON)/nn_int8.c $(MDATA)/model_data.c $(IMAGES)/images_data.c

clean:
	rm -rf model_* images_* eval_host_* eval_*.elf
