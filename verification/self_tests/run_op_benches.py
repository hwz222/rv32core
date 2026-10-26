#!/usr/bin/env python3
"""Run every operator layer of both quantized models through the
benchmark path, not only the one layer each benchmark defaults to.

The four benchmarks each build one default layer and diff it against a
host golden. That proves the operator works for one shape. This sweeps the
layer choice over every layer of that kind in the model, so the sweep also
covers stride 2, kernel 1 used as a projection, padding and no padding,
the widest and the narrowest channel counts, and the smallest feature maps.

Each layer: rebuild the host binary and the ELF at that layer, run both,
diff every output value.

    python3 verification/self_tests/run_op_benches.py            everything
    python3 verification/self_tests/run_op_benches.py --kind dw  one operator
    python3 verification/self_tests/run_op_benches.py --list     just list them

Writes op_benches.log next to this script. Exits 1 if any layer differs.
Standard library only.
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SIM = os.path.join(REPO, "isa_sim", "run_isa_sim.sh")

# Which benchmark can run which kind of layer. The benchmark lives in one
# workload, but its MODEL variable can point at the other workload's model,
# so every layer of both models is reachable: MobileNet's 3x3 stem goes
# through ResNet18's bench_conv3x3, and ResNet18's 1x1 projection shortcuts
# go through MobileNet's bench_pw.
BENCH_FOR_KIND = {
    "dw":      ("mobilenet", "bench_dw", "DW_LAYER"),
    "pw":      ("mobilenet", "bench_pw", "PW_LAYER"),
    "conv3x3": ("resnet18", "bench_conv3x3", "CONV_LAYER"),
    "resadd":  ("resnet18", "bench_resadd", "RESADD_LAYER"),
}
MODELS = ("mobilenet", "resnet18")


def kind_of(L):
    if L["op"] == "add":
        return "resadd"
    if L["op"] != "conv":
        return None
    if L["groups"] == L["cin"] == L["cout"]:
        return "dw"
    if L["kernel"] == 1 and L["groups"] == 1:
        return "pw"
    if L["kernel"] == 3 and L["groups"] == 1:
        return "conv3x3"
    return None


def layers_of(model_workload, kind):
    path = os.path.join(REPO, "workload", model_workload, "model_int8", "model.json")
    model = json.load(open(path))
    return [L for L in model["layers"] if kind_of(L) == kind]


def describe(L):
    if L["op"] == "add":
        c, h, w = L["shape"]
        return "shape %dx%dx%d, two input scales" % (c, h, w)
    ci, hi, wi = L["in_shape"]
    co, ho, wo = L["out_shape"]
    return ("in %dx%dx%d out %dx%dx%d k%d s%d p%d g%d"
            % (ci, hi, wi, co, ho, wo, L["kernel"], L["stride"],
               L["padding"], L["groups"]))


def run_layer(bench_workload, bench, var, model_workload, layer, log):
    cwd = os.path.join(REPO, "workload", bench_workload)
    model_dir = ("model_int8" if model_workload == bench_workload
                 else os.path.join("..", model_workload, "model_int8"))
    env_args = ["%s=%s" % (var, layer["name"]), "MODEL=" + model_dir]
    build = subprocess.run(["make", "-s", bench + "_host", bench + "_riscv"] + env_args,
                           cwd=cwd, capture_output=True, text=True)
    if build.returncode != 0:
        log("%-22s %-24s BUILD FAIL  %s" % (bench, layer["name"],
                                            build.stderr.strip()[-120:]))
        return False, 0

    host = subprocess.run([os.path.join(cwd, bench + "_host")],
                          cwd=cwd, capture_output=True, text=True)
    if host.returncode != 0:
        log("%-22s %-24s HOST FAIL" % (bench, layer["name"]))
        return False, 0

    with tempfile.TemporaryDirectory() as out:
        sim = subprocess.run(["bash", SIM, os.path.join(cwd, bench + ".elf"), out],
                             capture_output=True, text=True)
        if sim.returncode != 0:
            log("%-22s %-24s SIM FAIL    %s" % (bench, layer["name"],
                                                sim.stderr.strip()[-120:]))
            return False, 0
        got = open(os.path.join(out, "output_tensor.hex")).read()
        insns = json.load(open(os.path.join(out, "report.json")))["instructions_executed"]

    same = got.split() == host.stdout.split()
    n = len(host.stdout.split())
    log("%-10s %-14s %-22s %-11s %6d vals %9d insns  %s"
        % (model_workload, bench, layer["name"], "identical" if same else "DIFFERS",
           n, insns, describe(layer)))
    return same, insns


def main():
    ap = argparse.ArgumentParser(description="operator layer sweep")
    ap.add_argument("--kind", choices=sorted(BENCH_FOR_KIND), help="only this operator")
    ap.add_argument("--list", action="store_true", help="list the layers and stop")
    args = ap.parse_args()

    plan = []
    for model_workload in MODELS:
        for kind, (bench_workload, bench, var) in sorted(BENCH_FOR_KIND.items()):
            if args.kind and kind != args.kind:
                continue
            for L in layers_of(model_workload, kind):
                plan.append((bench_workload, bench, var, model_workload, L))

    if args.list:
        for bw, bench, _, mw, L in plan:
            print("%-10s %-14s %-22s %s" % (mw, bench, L["name"], describe(L)))
        print("%d layers" % len(plan))
        return 0

    log_path = os.path.join(HERE, "op_benches.log")
    ok_all, t0 = True, time.time()
    with open(log_path, "w") as lf:
        def log(msg):
            print(msg, flush=True)
            lf.write(msg + "\n")
            lf.flush()

        commit = subprocess.run(["git", "-C", REPO, "rev-parse", "--short", "HEAD"],
                                capture_output=True, text=True).stdout.strip()
        log("# %s  repo %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), commit))
        log("# every operator layer of both models: host golden vs isa_sim, "
            "every output value")
        log("")
        log("%-10s %-14s %-22s %-11s %s"
            % ("model", "benchmark", "layer", "result", "detail"))
        npass = nfail = 0
        for bw, bench, var, mw, L in plan:
            same, _ = run_layer(bw, bench, var, mw, L, log)
            if same:
                npass += 1
            else:
                nfail += 1
                ok_all = False
        log("")
        log("# %d layers identical, %d differ  (%.0f s)" % (npass, nfail, time.time() - t0))

    for workload in MODELS:
        subprocess.run(["make", "-s", "clean"],
                       cwd=os.path.join(REPO, "workload", workload),
                       capture_output=True)
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
