#!/usr/bin/env python3
"""Tests for compare_trace.py: it must pass equal traces and catch every
kind of difference at the right line.

    python3 verification/lockstep/test_compare_trace.py

The last test also uses a real isa_sim trace of workload/gemm/gemm.elf; it
is skipped when isa_sim or the ELF has not been built.
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import compare_trace as ct  # noqa: E402

# csrr x5, mcycle   = csrrs x5, 0xb00, x0
CSRR_MCYCLE = "b00022f3"
# csrr x5, minstret = csrrs x5, 0xb02, x0
CSRR_MINSTRET = "b02022f3"

BASE = [
    "0 00000000 10001197 x3=10001000",
    "1 00000004 a0018193 x3=10000a00",
    "2 00000008 0002a023 mem32[10000248]=00000000",
    f"3 0000000c {CSRR_MCYCLE} x5=00000004",
    f"4 00000010 {CSRR_MINSTRET} x5=00000004",
    "5 00000014 trap cause=11 tval=00000000",    # ecall: only a trap line
    "5 00000100 00008067",                       # first handler instruction
]


def changed(index, new_line):
    """BASE with one line replaced."""
    lines = list(BASE)
    lines[index] = new_line
    return lines


class CompareTraceTest(unittest.TestCase):

    def check_mismatch(self, rtl, line, fields):
        res = ct.compare(BASE, rtl)
        self.assertFalse(res.ok)
        self.assertEqual(res.kind, "mismatch")
        self.assertEqual(res.line, line)
        self.assertEqual(res.fields, fields)

    # ---- equal traces --------------------------------------------------------

    def test_same_trace_passes(self):
        res = ct.compare(BASE, list(BASE))
        self.assertTrue(res.ok)
        self.assertEqual(res.lines, len(BASE))
        self.assertEqual(res.time_reads, 1)            # the mcycle read

    def test_trailing_spaces_are_ignored(self):
        self.assertTrue(ct.compare(BASE, [l + "  " for l in BASE]).ok)

    # ---- one field changed: caught at the right line -------------------------

    def test_wrong_pc(self):
        self.check_mismatch(changed(1, "1 00000008 a0018193 x3=10000a00"), 2, ["pc"])

    def test_wrong_insn(self):
        self.check_mismatch(changed(1, "1 00000004 a0018113 x3=10000a00"), 2, ["insn"])

    def test_wrong_rd_value(self):
        self.check_mismatch(changed(1, "1 00000004 a0018193 x3=10000a04"), 2, ["rd"])

    def test_missing_rd(self):
        self.check_mismatch(changed(1, "1 00000004 a0018193"), 2, ["rd"])

    def test_wrong_store(self):
        self.check_mismatch(changed(2, "2 00000008 0002a023 mem32[1000024c]=00000000"),
                            3, ["mem"])

    def test_trap_instead_of_insn(self):
        self.check_mismatch(changed(6, "5 00000100 trap cause=2 tval=00008067"),
                            7, ["kind", "insn", "cause", "tval"])

    def test_wrong_trap_cause(self):
        self.check_mismatch(changed(5, "5 00000014 trap cause=3 tval=00000000"), 6, ["cause"])

    def test_wrong_count(self):
        self.check_mismatch(changed(6, "6 00000100 00008067"), 7, ["n"])

    # ---- time CSRs -----------------------------------------------------------

    def test_mcycle_value_may_differ(self):
        res = ct.compare(BASE, changed(3, f"3 0000000c {CSRR_MCYCLE} x5=00000123"))
        self.assertTrue(res.ok)

    def test_mcycle_register_must_match(self):
        self.check_mismatch(changed(3, f"3 0000000c {CSRR_MCYCLE} x6=00000004"), 4, ["rd"])

    def test_minstret_value_must_match(self):
        self.check_mismatch(changed(4, f"4 00000010 {CSRR_MINSTRET} x5=00000005"), 5, ["rd"])

    # ---- different length ----------------------------------------------------

    def test_rtl_missing_last_line(self):
        res = ct.compare(BASE, BASE[:-1])
        self.assertEqual((res.ok, res.kind, res.line), (False, "a_longer", len(BASE)))

    def test_rtl_extra_line(self):
        res = ct.compare(BASE, BASE + ["6 00000104 00000013"])
        self.assertEqual((res.ok, res.kind, res.line), (False, "b_longer", len(BASE) + 1))

    # ---- context -------------------------------------------------------------

    def test_context_shows_previous_lines(self):
        res = ct.compare(BASE, changed(6, "5 00000100 00008068"), context=3)
        self.assertEqual([a for a, _ in res.context], BASE[3:6])

    # ---- real isa_sim trace --------------------------------------------------

    @unittest.skipUnless(os.path.exists(os.path.join(ROOT, "isa_sim", "isa_sim")) and
                         os.path.exists(os.path.join(ROOT, "workload", "gemm", "gemm.elf")),
                         "isa_sim or workload/gemm/gemm.elf not built")
    def test_real_gemm_trace(self):
        with tempfile.TemporaryDirectory() as tmp:
            trace = os.path.join(tmp, "isa.trace")
            subprocess.run([os.path.join(ROOT, "isa_sim", "isa_sim"),
                            os.path.join(ROOT, "workload", "gemm", "gemm.elf"),
                            os.path.join(tmp, "out"), "--trace", trace],
                           check=True, capture_output=True)
            with open(trace) as f:
                lines = f.read().splitlines()
            self.assertTrue(ct.compare(lines, list(lines)).ok)
            # Change one register value in the middle.
            k = len(lines) // 2
            while " x" not in lines[k]:
                k += 1
            head, _, value = lines[k].rpartition("=")
            bad = list(lines)
            bad[k] = f"{head}={int(value, 16) ^ 1:08x}"
            res = ct.compare(lines, bad)
            self.assertEqual((res.ok, res.line, res.fields), (False, k + 1, ["rd"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
