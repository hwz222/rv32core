#!/usr/bin/env python3
"""Instruction-level lockstep check: compare two retire traces.

    usage: compare_trace.py <isa.trace> <rtl.trace> [--context N]

Both traces use the isa_sim format, one line per retired instruction or
taken trap:

    <n> <pc> <insn> [x<rd>=<val>] [mem<bits>[<addr>]=<val>]
    <n> <pc> trap cause=<mcause> tval=<tval>

The lines must be equal, one by one. The only exception: an instruction
that reads a time CSR (mcycle, cycle, time and their high halves) is
compared without its x<rd> value, because isa_sim counts one cycle per
instruction and the RTL counts real cycles. minstret/instret are compared.

The check stops at the first difference and prints both lines, the fields
that differ and the lines just before it. Files are read line by line, so
long traces do not fill memory.

Exit codes: 0 = all lines match, 1 = difference found, 2 = bad arguments
or a file cannot be read. Only the Python standard library is used.
"""
import argparse
import collections
import itertools
import sys

# CSRs whose value depends on time, not on the instructions retired.
TIME_CSRS = {
    0xB00, 0xB80,   # mcycle, mcycleh
    0xC00, 0xC80,   # cycle, cycleh
    0xC01, 0xC81,   # time, timeh
}

Result = collections.namedtuple(
    "Result",
    "ok kind line a b fields context lines time_reads",
)
# ok          True when the traces match
# kind        "match", "mismatch", "a_longer" or "b_longer"
# line        1-based line number of the first difference (0 when ok)
# a, b        the two lines there (None for the shorter trace)
# fields      names of the fields that differ
# context     up to N earlier (a, b) line pairs, all equal
# lines       number of lines compared and equal
# time_reads  how many time-CSR reads were compared without x<rd>


def parse(line):
    """Splits one trace line into named fields (all values kept as text)."""
    tokens = line.split()
    fields = {"n": tokens[0] if tokens else "", "pc": tokens[1] if len(tokens) > 1 else ""}
    rest = tokens[2:]
    if rest and rest[0] == "trap":
        fields["kind"] = "trap"
        for tok in rest[1:]:
            name, _, value = tok.partition("=")
            fields[name] = value                     # cause, tval
    else:
        fields["kind"] = "insn"
        fields["insn"] = rest[0] if rest else ""
        for tok in rest[1:]:
            if tok.startswith("x"):
                fields["rd"] = tok                    # x<rd>=<val>
            elif tok.startswith("mem"):
                fields["mem"] = tok                   # mem<bits>[<addr>]=<val>
            else:
                fields["other"] = fields.get("other", "") + tok
    return fields


def reads_time_csr(insn_text):
    """True when the instruction is a CSR instruction that reads a time CSR."""
    try:
        insn = int(insn_text, 16)
    except ValueError:
        return False
    opcode = insn & 0x7F
    funct3 = (insn >> 12) & 0x7
    csr = insn >> 20
    # SYSTEM opcode; funct3 0 is ECALL/EBREAK/MRET/WFI and 4 is unused.
    return opcode == 0x73 and funct3 not in (0, 4) and csr in TIME_CSRS


FIELD_ORDER = ["n", "pc", "kind", "insn", "rd", "mem", "cause", "tval", "other"]


def diff_fields(fa, fb):
    """Names of the fields that differ between two parsed lines."""
    names = sorted(set(fa) | set(fb),
                   key=lambda k: FIELD_ORDER.index(k) if k in FIELD_ORDER else len(FIELD_ORDER))
    return [k for k in names if fa.get(k) != fb.get(k)]


def insn_of(line):
    """The <insn> text of a line, without parsing the whole line."""
    tokens = line.split(None, 3)
    return tokens[2] if len(tokens) > 2 else ""


def same(a, b):
    """Compares two lines. Returns (equal, fields_that_differ)."""
    if a == b:
        return True, []
    fa, fb = parse(a), parse(b)
    fields = diff_fields(fa, fb)
    # A time-CSR read may differ only in the value of x<rd> (same register).
    if (fields == ["rd"] and fa["kind"] == "insn" and reads_time_csr(fa.get("insn", ""))
            and fa.get("rd", "").split("=")[0] == fb.get("rd", "").split("=")[0]):
        return True, []
    return False, fields


def compare(lines_a, lines_b, context=5):
    """Compares two iterables of lines. Stops at the first difference."""
    recent = collections.deque(maxlen=context)
    count = 0
    time_reads = 0
    for i, (a, b) in enumerate(itertools.zip_longest(lines_a, lines_b), start=1):
        a = a.rstrip() if a is not None else None
        b = b.rstrip() if b is not None else None
        if a is None or b is None:
            kind = "b_longer" if a is None else "a_longer"
            return Result(False, kind, i, a, b, [], list(recent), count, time_reads)
        equal, fields = same(a, b)
        if not equal:
            return Result(False, "mismatch", i, a, b, fields, list(recent), count, time_reads)
        if reads_time_csr(insn_of(a)):
            time_reads += 1
        recent.append((a, b))
        count += 1
    return Result(True, "match", 0, None, None, [], [], count, time_reads)


def report(res, name_a, name_b, out=sys.stdout):
    """Prints the result for a person to read."""
    if res.ok:
        print(f"OK: {res.lines} lines match ({res.time_reads} time-CSR reads compared "
              f"without x<rd>)", file=out)
        return
    width = max(len(name_a), len(name_b))
    if res.kind == "mismatch":
        print(f"MISMATCH at line {res.line} (trace n={parse(res.a)['n']}), "
              f"field(s): {', '.join(res.fields)}", file=out)
    elif res.kind == "a_longer":
        print(f"LENGTH: {name_b} ends after line {res.line - 1}; {name_a} has more lines",
              file=out)
    else:
        print(f"LENGTH: {name_a} ends after line {res.line - 1}; {name_b} has more lines",
              file=out)
    print(f"  {name_a:<{width}} : {res.a if res.a is not None else '(end of file)'}", file=out)
    print(f"  {name_b:<{width}} : {res.b if res.b is not None else '(end of file)'}", file=out)
    if res.context:
        print(f"previous {len(res.context)} line(s), equal in both traces:", file=out)
        for a, _ in res.context:
            print(f"  {a}", file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Compare an isa_sim trace with an RTL trace.")
    parser.add_argument("isa_trace")
    parser.add_argument("rtl_trace")
    parser.add_argument("--context", type=int, default=5,
                        help="lines to show before a difference (default 5)")
    args = parser.parse_args(argv)
    try:
        with open(args.isa_trace) as fa, open(args.rtl_trace) as fb:
            res = compare(fa, fb, args.context)
    except OSError as e:
        print(f"compare_trace: {e}", file=sys.stderr)
        return 2
    report(res, "isa_sim", "rtl")
    return 0 if res.ok else 1


if __name__ == "__main__":
    sys.exit(main())
