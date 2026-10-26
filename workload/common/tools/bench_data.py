#!/usr/bin/env python3
"""Build-time generator for the operator microbenchmarks.

A benchmark runs ONE layer of a quantized model. This tool reads that
model (model.json + weights.bin, written by qref.py) and writes a C header
holding everything the benchmark needs as const arrays:

  - the layer's real INT8 weights, int32 bias and (m0, shift) pairs
  - one input activation tensor
  - a filled-in nn_layer, so the benchmark calls the same kernel the full
    network calls

The bare-metal program cannot open files, so everything is compiled in.
Standard library only: building a benchmark must not need numpy or torch.

The weights are real. The input activation is not: producing a real one
means running the earlier layers, which needs a framework this tool may
not have. It is pseudo-random instead, from a fixed seed, in the range the
real input would have (0..127 after a ReLU, -127..127 otherwise). That is
enough for the job: the check is that the host build and the RV32IMA build
agree on the same input, so the input only has to be the same on both
sides and to exercise the kernel's whole range.
"""

import argparse
import json
import os
import sys


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def lcg(seed):
    """Small deterministic generator. Same numbers everywhere, no imports."""
    state = (seed * 2654435761 + 1) & 0xFFFFFFFF
    while True:
        state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
        yield (state >> 16) & 0xFFFF


def c_array(ctype, name, values, per_line=24):
    rows = []
    for i in range(0, len(values), per_line):
        rows.append("    " + ", ".join(str(v) for v in values[i:i + per_line]) + ",")
    body = "\n".join(rows) if rows else "    0,"
    n = len(values) if values else 1
    return "static const %s %s[%d] = {\n%s\n};\n" % (ctype, name, n, body)


def numel(shape):
    n = 1
    for d in shape:
        n *= d
    return n


def align4(n):
    return (n + 3) & ~3


# ---------------------------------------------------------------------------
# picking the layer
# ---------------------------------------------------------------------------

def is_dw(L):
    return L["op"] == "conv" and L["groups"] == L["cin"] == L["cout"]


def is_pw(L):
    return L["op"] == "conv" and L["kernel"] == 1 and L["groups"] == 1


def is_conv3x3(L):
    return L["op"] == "conv" and L["kernel"] == 3 and L["groups"] == 1


def is_add(L):
    return L["op"] == "add"


KINDS = {"dw": is_dw, "pw": is_pw, "conv3x3": is_conv3x3, "resadd": is_add}


def pick_layer(model, kind, name):
    """The named layer, or the largest one of that kind if no name is given."""
    layers = model["layers"]
    if name:
        for L in layers:
            if L["name"] == name:
                if not KINDS[kind](L):
                    sys.exit("layer %s is not a %s layer" % (name, kind))
                return L
        sys.exit("no layer named %s in the model" % name)
    candidates = [L for L in layers if KINDS[kind](L)]
    if not candidates:
        sys.exit("the model has no %s layer" % kind)
    return max(candidates, key=lambda L: numel(L.get("out_shape", L.get("shape"))))


def input_is_relu_output(model, layer):
    """True if every tensor feeding this layer came out of a ReLU, so the
    input is non-negative. Decides the range of the generated input; a
    residual add has two inputs, hence the loop."""
    names = layer.get("inputs") or [layer.get("input")]
    produced_by = {L.get("output"): L for L in model["layers"]}
    for name in names:
        src_layer = produced_by.get(name)
        if src_layer is None or not src_layer.get("relu"):
            return False        # the input image, or a layer with no ReLU
    return True


# ---------------------------------------------------------------------------
# emitting
# ---------------------------------------------------------------------------

def read_weights(model_dir, model, layer):
    if "weight_count" not in layer:
        return []
    path = os.path.join(model_dir, model["weights_file"])
    with open(path, "rb") as f:
        f.seek(layer["weight_offset"])
        raw = f.read(layer["weight_count"])
    if len(raw) != layer["weight_count"]:
        sys.exit("short read of %s" % path)
    return [b - 256 if b >= 128 else b for b in raw]


def random_activation(n, seed, signed):
    """n pseudo-random INT8 values. signed=False means a post-ReLU tensor."""
    gen = lcg(seed)
    lo, span = (-127, 255) if signed else (0, 128)
    return [lo + (next(gen) % span) for _ in range(n)]


def emit(model_dir, kind, layer_name, seed, out_prefix):
    model = json.load(open(os.path.join(model_dir, "model.json")))
    L = pick_layer(model, kind, layer_name)
    signed = not input_is_relu_output(model, L)

    if L["op"] == "add":
        shape = L["shape"]
        n_in = n_out = numel(shape)
        cout, hout, wout = shape
        cin, hin, win = shape
    else:
        n_in = numel(L["in_shape"])
        n_out = numel(L["out_shape"])
        cin, hin, win = L["in_shape"]
        cout, hout, wout = L["out_shape"]

    inp = random_activation(n_in, seed, signed)
    inp2 = random_activation(n_in, seed + 1, signed) if L["op"] == "add" else None

    # Arena: the second input of an add (if any) first, then the output.
    in1_off = 0 if inp2 is not None else -1
    out_off = align4(n_in) if inp2 is not None else 0
    arena = out_off + n_out

    h, c = out_prefix + ".h", out_prefix + ".c"
    guard = os.path.basename(out_prefix).upper() + "_H"

    with open(h, "w") as f:
        f.write("/* Generated by tools/gen_bench_data.py -- do not edit.\n"
                " * model: %s   layer: %s (%s)   input seed: %d */\n"
                % (model["model"], L["name"], kind, seed))
        f.write("#ifndef %s\n#define %s\n\n" % (guard, guard))
        f.write('#include "nn_int8.h"\n\n')
        f.write('#define BENCH_NAME        "%s"\n' % L["name"])
        f.write("#define BENCH_IN_BYTES    %d\n" % n_in)
        f.write("#define BENCH_OUT_BYTES   %d\n" % n_out)
        f.write("#define BENCH_ARENA_BYTES %d\n" % arena)
        f.write("#define BENCH_OUT_OFF     %d\n" % out_off)
        f.write("#define BENCH_IN1_OFF     %d\n" % in1_off)
        f.write("\nextern const int8_t bench_input[BENCH_IN_BYTES];\n")
        if inp2 is not None:
            f.write("extern const int8_t bench_input2[BENCH_IN_BYTES];\n")
        f.write("extern const nn_model bench_model;\n")
        f.write("\n#endif\n")

    with open(c, "w") as f:
        f.write("/* Generated by tools/gen_bench_data.py -- do not edit. */\n")
        f.write('#include "%s"\n\n' % os.path.basename(h))
        w = read_weights(model_dir, model, L)
        if w:
            f.write(c_array("int8_t", "bench_weight", w))
        if "bias" in L:
            f.write(c_array("int32_t", "bench_bias", L["bias"]))
        f.write(c_array("int32_t", "bench_m0", L["m0"]))
        f.write(c_array("int32_t", "bench_shift", L["shift"]))
        f.write("\n")
        f.write(c_array("int8_t", "bench_input_", inp).replace(
            "static const int8_t bench_input_", "const int8_t bench_input"))
        if inp2 is not None:
            f.write(c_array("int8_t", "bench_input2_", inp2).replace(
                "static const int8_t bench_input2_", "const int8_t bench_input2"))
        f.write("\nstatic const nn_layer bench_layers[1] = {{\n")
        f.write("    .op = %s, .relu = %d,\n"
                % ({"conv": "NN_CONV", "add": "NN_ADD", "gap": "NN_GAP",
                    "fc": "NN_FC"}[L["op"]], int(L.get("relu", 0))))
        f.write("    .cin = %d, .hin = %d, .win = %d,\n" % (cin, hin, win))
        f.write("    .cout = %d, .hout = %d, .wout = %d,\n" % (cout, hout, wout))
        f.write("    .kernel = %d, .stride = %d, .padding = %d, .groups = %d,\n"
                % (L.get("kernel", 0), L.get("stride", 1),
                   L.get("padding", 0), L.get("groups", 1)))
        f.write("    .in0 = NN_INPUT, .in1 = %d, .out = %d,\n" % (in1_off, out_off))
        f.write("    .weight = %s, .bias = %s,\n"
                % ("bench_weight" if w else "0", "bench_bias" if "bias" in L else "0"))
        f.write("    .m0 = bench_m0, .shift = bench_shift,\n")
        f.write("}};\n\n")
        f.write("const nn_model bench_model = { bench_layers, 1, 0 };\n")

    print("generated %s: %s layer %s, in %dB out %dB"
          % (os.path.basename(out_prefix), kind, L["name"], n_in, n_out))


def main(default_model_dir="model_int8"):
    ap = argparse.ArgumentParser(description="operator benchmark data generator")
    ap.add_argument("kind", choices=sorted(KINDS))
    ap.add_argument("--model-dir", default=default_model_dir)
    ap.add_argument("--layer", default=None, help="layer name; default: the largest")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("-o", "--out", required=True, help="output prefix (no extension)")
    args = ap.parse_args()
    emit(args.model_dir, args.kind, args.layer, args.seed, args.out)


if __name__ == "__main__":
    main()
