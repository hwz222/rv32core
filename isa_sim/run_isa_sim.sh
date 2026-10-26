#!/usr/bin/env bash
# Run one bare-metal ELF on the ISA simulator.
#
#   isa_sim/run_isa_sim.sh <elf> <out_dir> [--trace FILE] [--max-insns N] [--no-dump]
#
# Builds the simulator first (make is a no-op when it is up to date), then
# runs it. Writes <out_dir>/output_tensor.hex on success and
# <out_dir>/report.json always. The exit code is the simulator's; the table
# is in isa_sim/README.md. Works from any current directory.
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "usage: $0 <elf> <out_dir> [--trace FILE] [--max-insns N] [--no-dump]" >&2
    exit 2
fi

sim_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
elf="$1"
out_dir="$2"
shift 2

make -s -C "$sim_dir" >&2
mkdir -p "$out_dir"
exec "$sim_dir/isa_sim" "$elf" "$out_dir" "$@"
