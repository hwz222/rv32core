#!/usr/bin/env bash
# Run one bare-metal ELF on the RTL core (Chisel -> Verilog -> Verilator).
#
#   rtl/run_rtl_sim.sh <elf> <out_dir> [--trace FILE] [--max-cycles N] ...
#
# Writes into <out_dir>: data_sram_dump.hex (the result words, on success),
# waveform.vcd, cycle_count.txt, coverage.json and report.json. The exit
# code is the testbench's (same numbers as isa_sim, see rtl/tb/tb_main.cpp);
# a failed build also exits non-zero. Extra options go to the testbench.
#
# What it rebuilds, and only when needed:
#   1. generated/SimTop.v, with sbt, when a Scala or sbt file is newer than
#      it or when RTL_CORE names a different core than the one inside it;
#   2. the Verilator model (make -C tb; make tracks those dependencies).
#
# Environment, all optional:
#   RTL_CORE    core: "core" (the 5-stage pipeline, default) or "stub"
#   SBT         sbt command (default: sbt)
#   VERILATOR   verilator command (default: verilator)
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "usage: $0 <elf> <out_dir> [testbench options]" >&2
    exit 2
fi

# Absolute paths: the build steps below run in other directories.
elf="$(realpath -m -- "$1")"
out_dir="$(realpath -m -- "$2")"
shift 2

rtl_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core="${RTL_CORE:-core}"
sbt="${SBT:-sbt}"
export VERILATOR="${VERILATOR:-verilator}"

verilog="$rtl_dir/generated/SimTop.v"
core_mark="$rtl_dir/generated/SimTop.core"     # written by rvsc.Main

verilog_stale() {
    [ -f "$verilog" ] || return 0
    [ "$(cat "$core_mark" 2>/dev/null)" = "$core" ] || return 0
    [ -n "$(find "$rtl_dir/src/main" "$rtl_dir/build.sbt" "$rtl_dir/project/build.properties" \
                -newer "$verilog" -print -quit)" ]
}

if verilog_stale; then
    echo "run_rtl_sim: generating SimTop.v for core '$core'" >&2
    (cd "$rtl_dir" && "$sbt" -batch "runMain rvsc.Main $core") >&2
fi

make -C "$rtl_dir/tb" >&2

exec "$rtl_dir/obj_dir/VSimTop" "$elf" "$out_dir" --core-name "$(cat "$core_mark")" "$@"
