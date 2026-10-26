#!/usr/bin/env python3
"""Check that three programs give the SAME logits for the INT8 models:

  1. workload/common/tools/qref.py (Python integer reference; its logits are
     in ../ml_workspace/int8_eval/<model>/ref_logits.bin)
  2. the C code built for the host (eval_host_<model>)
  3. the same C code built for RV32IMA, run on the ISA simulator

Step 2 runs all 10,000 test images. Step 3 runs --riscv-images images
(default 1000; use 10000 for all), in ELFs of --batch images each.

A log is written next to this script. The exit code is 1 if any logit differs.
Standard library only.

    python3 verification/int8_models/check_bitexact.py --model resnet18_w16
    python3 verification/int8_models/check_bitexact.py --model all --riscv-images 10000
"""

import argparse
import array
import concurrent.futures as cf
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SIM = os.path.join(REPO, "isa_sim", "isa_sim")
MODELS = ("resnet18_w16", "mobilenet_v1_a050")
NUM_CLASSES = 10
N_TEST = 10000


def read_i32(path):
    a = array.array("i")
    with open(path, "rb") as f:
        a.frombytes(f.read())
    if sys.byteorder != "little":
        a.byteswap()
    return list(a)


def parse_hex(text):
    out = []
    for line in text.split():
        v = int(line, 16)
        out.append(v - (1 << 32) if v >= (1 << 31) else v)
    return out


def make(*args):
    subprocess.run(["make", "-s", "-C", HERE, *args], check=True, stdout=subprocess.DEVNULL)


def compare(got, ref, start):
    """Return the image numbers whose logits differ."""
    bad = []
    for i in range(len(got) // NUM_CLASSES):
        a = got[i * NUM_CLASSES:(i + 1) * NUM_CLASSES]
        b = ref[(start + i) * NUM_CLASSES:(start + i + 1) * NUM_CLASSES]
        if a != b:
            bad.append(start + i)
    return bad


def accuracy(logits, labels, start, count):
    ok = 0
    for i in range(count):
        row = logits[i * NUM_CLASSES:(i + 1) * NUM_CLASSES]
        ok += row.index(max(row)) == labels[start + i]
    return 100.0 * ok / count


def check_model(model, eval_dir, riscv_images, batch, jobs, log):
    ref = read_i32(os.path.join(eval_dir, "ref_logits.bin"))
    labels = read_i32(os.path.join(eval_dir, "labels.bin"))
    images = os.path.join(eval_dir, "test_images_q.bin")
    assert len(ref) == N_TEST * NUM_CLASSES and len(labels) == N_TEST

    # 2. host C, all images, split over parallel processes
    t0 = time.time()
    make("host", "MODEL=" + model, "EVAL_DIR=" + eval_dir)
    host = os.path.join(HERE, "eval_host_" + model)
    step = (N_TEST + jobs - 1) // jobs
    chunks = [(s, min(step, N_TEST - s)) for s in range(0, N_TEST, step)]
    with cf.ThreadPoolExecutor(jobs) as ex:
        outs = list(ex.map(lambda c: subprocess.run(
            [host, images, str(c[0]), str(c[1])], check=True, capture_output=True,
            text=True).stdout, chunks))
    host_logits = [v for o in outs for v in parse_hex(o)]
    host_bad = compare(host_logits, ref, 0)
    host_acc = accuracy(host_logits, labels, 0, N_TEST)
    log("[%s] host C   : %d images, %d differ from qref.py, accuracy %.2f%%  (%.0f s)"
        % (model, N_TEST, len(host_bad), host_acc, time.time() - t0))

    # 3. RV32IMA C on the ISA simulator
    t0 = time.time()
    make("-C", os.path.join(REPO, "isa_sim"))  # build the simulator if needed
    starts = list(range(0, riscv_images, batch))
    for s in starts:
        make("riscv", "MODEL=" + model, "START=%d" % s, "COUNT=%d" % min(batch, riscv_images - s),
             "EVAL_DIR=" + eval_dir)

    def run_sim(s):
        n = min(batch, riscv_images - s)
        elf = os.path.join(HERE, "eval_%s_%d_%d.elf" % (model, s, n))
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([SIM, elf, out, "--max-insns", "1000000000000"], check=True,
                           capture_output=True)
            got = parse_hex(open(os.path.join(out, "output_tensor.hex")).read())
            insns = json.load(open(os.path.join(out, "report.json")))["instructions_executed"]
        return s, n, got, insns

    riscv_bad, riscv_ok, total_insns = [], 0, 0
    with cf.ThreadPoolExecutor(jobs) as ex:
        for s, n, got, insns in ex.map(run_sim, starts):
            riscv_bad += compare(got, ref, s)
            riscv_ok += round(accuracy(got, labels, s, n) * n / 100.0)
            total_insns += insns
    ref_acc_same = accuracy(ref[:riscv_images * NUM_CLASSES], labels, 0, riscv_images)
    log("[%s] RV32IMA  : %d images on isa_sim, %d differ from qref.py, accuracy %.2f%% "
        "(qref.py on the same images: %.2f%%), %.0fM instructions per image  (%.0f s)"
        % (model, riscv_images, len(riscv_bad), 100.0 * riscv_ok / riscv_images, ref_acc_same,
           total_insns / riscv_images / 1e6, time.time() - t0))
    return not host_bad and not riscv_bad


def main():
    ap = argparse.ArgumentParser(description="INT8 bit-exact check")
    ap.add_argument("--model", default="all", choices=MODELS + ("all",))
    ap.add_argument("--riscv-images", type=int, default=1000)
    ap.add_argument("--batch", type=int, default=200)
    ap.add_argument("--jobs", type=int, default=os.cpu_count() or 1)
    ap.add_argument("--workspace", default=os.path.join(REPO, "..", "ml_workspace"))
    args = ap.parse_args()

    models = MODELS if args.model == "all" else (args.model,)
    log_path = os.path.join(HERE, "bitexact.log")
    with open(log_path, "a") as lf:
        def log(msg):
            print(msg, flush=True)
            lf.write(msg + "\n")
            lf.flush()              # so the log shows progress while it runs
        commit = subprocess.run(["git", "-C", REPO, "rev-parse", "--short", "HEAD"],
                                capture_output=True, text=True).stdout.strip()
        log("# %s  repo %s  riscv images %d" % (time.strftime("%Y-%m-%d %H:%M:%S"), commit,
                                                args.riscv_images))
        ok = all([check_model(m, os.path.abspath(os.path.join(args.workspace, "int8_eval", m)),
                              args.riscv_images, args.batch, args.jobs, log) for m in models])
        log("# result: %s" % ("ALL LOGITS IDENTICAL" if ok else "MISMATCH"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
