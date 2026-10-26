# Builds the Verilator testbench: rtl/obj_dir/VSimTop
#
#   cd rtl
#   sbt "runMain rvsc.Main"    # first: write generated/SimTop.v
#   make -C tb                 # then: build obj_dir/VSimTop
#
# Verilator turns SimTop.v into a C++ model (class VSimTop), then --build
# compiles it together with tb_main.cpp and isa_sim's ELF reader.
# Nothing is rebuilt when SimTop.v and the C++ files have not changed.

RTL_DIR   := $(abspath ..)
ISA_SRC   := $(abspath ../../isa_sim/src)
VERILOG   := $(RTL_DIR)/generated/SimTop.v
OBJ_DIR   := $(RTL_DIR)/obj_dir
BIN       := $(OBJ_DIR)/VSimTop

TB_SRCS   := $(abspath tb_main.cpp) $(ISA_SRC)/elf.cpp
TB_HDRS   := $(ISA_SRC)/elf.h $(ISA_SRC)/memmap.h

VERILATOR ?= verilator
VFLAGS    := --cc --exe --build --trace -O2 \
             --top-module SimTop --Mdir $(OBJ_DIR) \
             -CFLAGS "-O2 -I$(ISA_SRC)"

.PHONY: all clean

all: $(BIN)

$(BIN): $(VERILOG) $(TB_SRCS) $(TB_HDRS) Makefile
	$(VERILATOR) $(VFLAGS) $(VERILOG) $(TB_SRCS)

# SimTop.v is written by sbt, not by this Makefile.
$(VERILOG):
	@echo "missing $@: run sbt \"runMain rvsc.Main\" in rtl/ first"; exit 1

clean:
	rm -rf $(OBJ_DIR)
