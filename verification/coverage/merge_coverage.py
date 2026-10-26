#!/usr/bin/env python3
"""Merge coverage.json files from many RTL runs into one coverage report
(implemented opcodes, hazard types, pipeline states).

    usage: merge_coverage.py [--out-dir DIR] INPUT [INPUT ...]

INPUT is a coverage.json file or a folder; folders are searched for
coverage.json files at any depth (for example the --keep folders of
run_directed.sh and run_riscv_tests.sh).

Every run's testbench writes <out_dir>/coverage.json with counts. This
script adds the counts of all inputs and compares them with the list of
bins below. A bin is covered when its total count is above 0. Bins that
cannot happen in this design (no interrupts, no cache waits) are "not applicable"
and left out of the totals.

Writes DIR/coverage_report.json (all counts) and DIR/coverage_report.md
(a summary to read). DIR defaults to this script's folder. Only the Python
standard library is used.
"""
import argparse
import json
import os
import sys

# ---- the bins ----------------------------------------------------------------

OPCODES = [
    # RV32I
    "lui", "auipc", "jal", "jalr",
    "beq", "bne", "blt", "bge", "bltu", "bgeu",
    "lb", "lh", "lw", "lbu", "lhu", "sb", "sh", "sw",
    "addi", "slti", "sltiu", "xori", "ori", "andi", "slli", "srli", "srai",
    "add", "sub", "sll", "slt", "sltu", "xor", "srl", "sra", "or", "and",
    "fence", "fence.i", "ecall", "ebreak",
    # privileged
    "mret", "wfi",
    # M
    "mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu",
    # A
    "lr.w", "sc.w", "amoswap.w", "amoadd.w", "amoxor.w", "amoand.w", "amoor.w",
    "amomin.w", "amomax.w", "amominu.w", "amomaxu.w",
    # Zicsr
    "csrrw", "csrrs", "csrrc", "csrrwi", "csrrsi", "csrrci",
]
TRAPS = ([f"exception {c}" for c in (0, 1, 2, 3, 4, 5, 6, 7, 11)] +
         [f"interrupt {c}" for c in (3, 7, 11)])
STALLS = ["LoadUse", "BranchUse", "MulDiv", "Imem", "Dmem"]
FLUSHES = ["Branch", "Jump", "Trap"]
FORWARDING = [f"{s}<-{src}" for s in ("ID.rs1", "ID.rs2", "EX.rs1", "EX.rs2")
              for src in ("EX/MEM", "MEM/WB")]
STAGES = ["IF", "ID", "EX", "MEM", "WB"]
STAGE_PATTERNS = [" ".join(STAGES[i] if (p >> i) & 1 else "-" for i in range(5))
                  for p in range(32)]

# Cannot happen in this design: interrupt lines are tied to 0, and instruction memory
# is always ready. Dmem is counted: the extra MEM cycle of an AMO is
# reported as a DMEM wait (events.stallDmem).
NOT_APPLICABLE_M2 = {
    "traps": {"interrupt 3", "interrupt 7", "interrupt 11"},
    "stalls": {"Imem"},
}

# (category, bins, what it shows)
CATEGORIES = [
    ("opcodes", OPCODES, "implemented instructions retired"),
    ("traps", TRAPS, "exceptions and interrupts taken"),
    ("stalls", STALLS, "hazard types: cycles the front of the pipeline waited"),
    ("flushes", FLUSHES, "hazard types: cycles younger instructions were thrown away"),
    ("forwarding", FORWARDING, "hazard types: forwarding paths used"),
    ("stage_patterns", STAGE_PATTERNS, "pipeline states: which stages were busy together"),
]


def find_inputs(paths):
    """coverage.json files named directly or found under folders."""
    files = []
    for p in paths:
        if os.path.isdir(p):
            for root, _, names in os.walk(p):
                if "coverage.json" in names:
                    files.append(os.path.join(root, "coverage.json"))
        elif os.path.isfile(p):
            files.append(p)
        else:
            raise FileNotFoundError(p)
    return sorted(files)


def merge(files):
    """Adds the counts of all files. Returns (totals, cores, cycles)."""
    totals = {name: {} for name, _, _ in CATEGORIES}
    totals["stage_busy"] = {}
    cores, cycles = {}, 0
    for f in files:
        with open(f) as fh:
            d = json.load(fh)
        cores[d.get("core", "unknown")] = cores.get(d.get("core", "unknown"), 0) + 1
        cycles += d.get("cycles", 0)
        for name in totals:
            for k, v in d.get(name, {}).items():
                totals[name][k] = totals[name].get(k, 0) + v
    return totals, cores, cycles


def summarize(totals):
    """Per category: covered / applicable bins, and the uncovered ones."""
    summary = {}
    for name, bins, what in CATEGORIES:
        na = NOT_APPLICABLE_M2.get(name, set())
        applicable = [b for b in bins if b not in na]
        covered = [b for b in applicable if totals[name].get(b, 0) > 0]
        summary[name] = {
            "what": what,
            "covered": len(covered),
            "total": len(applicable),
            "uncovered": [b for b in applicable if b not in covered],
            "not_applicable": sorted(na),
            "counts": {b: totals[name].get(b, 0) for b in bins},
        }
    return summary


def write_reports(out_dir, files, totals, cores, cycles, summary):
    os.makedirs(out_dir, exist_ok=True)
    report = {
        "inputs": len(files),
        "cores": cores,
        "cycles": cycles,
        "unknown_instructions": totals["opcodes"].get("unknown", 0),
        "categories": summary,
        "stage_busy": {s: totals["stage_busy"].get(s, 0) for s in STAGES},
    }
    with open(os.path.join(out_dir, "coverage_report.json"), "w") as fh:
        json.dump(report, fh, indent=2)
        fh.write("\n")

    lines = ["# Coverage report", "",
             f"Inputs: {len(files)} runs. Cores: "
             + ", ".join(f"{k} ({v})" for k, v in sorted(cores.items()))
             + f". Run cycles: {cycles}.", ""]
    if len(cores) > 1:
        lines += ["**Warning: the inputs come from more than one core.**", ""]
    lines += ["| category | covered | applicable | what it shows |",
              "|---|---|---|---|"]
    for name, _, _ in CATEGORIES:
        s = summary[name]
        lines.append(f"| {name} | {s['covered']} | {s['total']} | {s['what']} |")
    lines.append("")
    for name, _, _ in CATEGORIES:
        s = summary[name]
        lines.append(f"## {name}")
        lines.append("")
        lines.append("Not covered: " + (", ".join(s["uncovered"]) if s["uncovered"] else "none") + ".")
        if s["not_applicable"]:
            lines.append("Not applicable in this design: " + ", ".join(s["not_applicable"]) + ".")
        lines.append("")
        lines.append("| bin | count |")
        lines.append("|---|---|")
        for b, c in s["counts"].items():
            lines.append(f"| {b} | {c} |")
        lines.append("")
    lines.append("## stage_busy (cycles each stage held an instruction)")
    lines.append("")
    lines.append("| stage | cycles |")
    lines.append("|---|---|")
    for st in STAGES:
        lines.append(f"| {st} | {totals['stage_busy'].get(st, 0)} |")
    lines.append("")
    if report["unknown_instructions"]:
        lines.append(f"Retired words that are not RV32IMA instructions: "
                     f"{report['unknown_instructions']} (StubCore retires every fetched word).")
        lines.append("")
    with open(os.path.join(out_dir, "coverage_report.md"), "w") as fh:
        fh.write("\n".join(lines))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Merge coverage.json files.")
    parser.add_argument("inputs", nargs="+", help="coverage.json files or folders")
    parser.add_argument("--out-dir", default=os.path.dirname(os.path.abspath(__file__)),
                        help="where to write the reports (default: this folder)")
    args = parser.parse_args(argv)
    try:
        files = find_inputs(args.inputs)
    except FileNotFoundError as e:
        print(f"merge_coverage: no such file or folder: {e}", file=sys.stderr)
        return 2
    if not files:
        print("merge_coverage: no coverage.json found", file=sys.stderr)
        return 2
    totals, cores, cycles = merge(files)
    summary = summarize(totals)
    write_reports(args.out_dir, files, totals, cores, cycles, summary)
    for name, _, _ in CATEGORIES:
        s = summary[name]
        print(f"{name:15s} {s['covered']:3d} / {s['total']:3d}")
    print(f"merged {len(files)} runs -> {args.out_dir}/coverage_report.{{json,md}}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
