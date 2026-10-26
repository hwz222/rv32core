#!/usr/bin/env python3
"""INT8 post-training quantization and the integer reference model.

This tool follows workload/common/QUANT.md exactly. It:

  1. folds BatchNorm into the convs (float64),
  2. calibrates activation scales on the first 1000 training images,
  3. quantizes weights, biases and requantize multipliers,
  4. runs the INT8 model with integer arithmetic only and measures the
     accuracy on the 10,000 CIFAR-10 test images,
  5. writes the quantized model (for the C code generator) and, optionally,
     the quantized test images with their reference logits (to check that
     the C code gives the same logits, bit for bit).

It is an offline tool (numpy + torch). No Makefile calls it.

    python workload/common/tools/qref.py \
        --run ../ml_workspace/runs/resnet18_w16 \
        --out workload/resnet18/model_int8 \
        --data ../ml_workspace/datasets \
        --eval-out ../ml_workspace/int8_eval/resnet18_w16

Why this file never calls F.conv2d: on PyTorch 2.2 (CPU), float64 conv2d
gave wrong results when it used more than one thread (ResNet18 scored 67%
instead of 92.71%; with one thread it was right). Every conv here is an
unfold (it only moves data) plus a matrix multiply, and the tool stops if
the folded FP32 model does not reproduce the accuracy of the training run.

How the integer model stays exact: every conv and FC sum is computed in
float64 on integer values. Each product is below 2^14 and each sum below
2^31, so all partial sums are exact in float64 (53-bit mantissa); the code
rounds the result to an integer and checks the rounding error is tiny.
Everything after the sum (bias, requantize, clamp) is done in int64.
"""

import argparse
import json
import math
import os
import pickle
import sys

import numpy as np
import torch
import torch.nn.functional as F

CALIB_COUNT = 1000   # first N training images (QUANT.md 2.3)
Q_MAX = 127


# ---------------------------------------------------------------------------
# CIFAR-10 from the original python batches (no torchvision needed)
# ---------------------------------------------------------------------------

def load_cifar(root, split):
    """Return (images uint8 [N,3,32,32], labels int64 [N]) in file order."""
    base = os.path.join(root, "cifar-10-batches-py")
    names = ["data_batch_%d" % i for i in range(1, 6)] if split == "train" else ["test_batch"]
    xs, ys = [], []
    for n in names:
        with open(os.path.join(base, n), "rb") as f:
            d = pickle.load(f, encoding="bytes")
        xs.append(d[b"data"].reshape(-1, 3, 32, 32))
        ys.append(np.array(d[b"labels"], dtype=np.int64))
    return np.concatenate(xs), np.concatenate(ys)


def normalize(pixels, mean, std):
    x = pixels.astype(np.float64) / 255.0
    m = np.array(mean, dtype=np.float64).reshape(1, 3, 1, 1)
    s = np.array(std, dtype=np.float64).reshape(1, 3, 1, 1)
    return (x - m) / s


def input_scale(mean, std):
    # QUANT.md 2.4: one fixed scale that covers every possible pixel value.
    worst = max(max(m / s, (1.0 - m) / s) for m, s in zip(mean, std))
    return worst / Q_MAX


def quantize_input(x_float, s_in):
    return np.clip(np.rint(x_float / s_in), -Q_MAX, Q_MAX).astype(np.int64)


# ---------------------------------------------------------------------------
# Requantize multiplier (QUANT.md section 3)
# ---------------------------------------------------------------------------

def multiplier(m):
    """Return (M0, shift) with M = M0 * 2^-(31 + shift)."""
    assert m > 0, "requantize multiplier must be positive"
    frac, exp = math.frexp(m)          # m = frac * 2^exp, 0.5 <= frac < 1
    m0 = int(round(frac * (1 << 31)))
    if m0 == (1 << 31):
        m0 = 1 << 30
        exp += 1
    shift = -exp
    assert (1 << 30) <= m0 < (1 << 31), "M0 out of range"
    assert 1 <= 31 + shift <= 62, "shift out of range for M = %g" % m
    return m0, shift


def requant(x, m0, shift):
    """round(x * M) with one rounding, half up. x, m0, shift: int64 tensors."""
    k = 31 + shift
    p = x * m0                                      # exact, |p| < 2^62
    return (p + torch.bitwise_left_shift(torch.ones_like(k), k - 1)) >> k


def clamp8(v, lo):
    return torch.clamp(v, lo, Q_MAX)


# ---------------------------------------------------------------------------
# Float model with folded BN (used for calibration)
# ---------------------------------------------------------------------------

def fold_bn(graph, arrays):
    """Return {layer name: (w_f, b_f)} as float64 numpy arrays."""
    eps = graph["bn_eps"]
    folded = {}
    for l in graph["layers"]:
        if l["type"] == "conv":
            w = arrays[l["weight"]].astype(np.float64)
            bn = l["bn"]
            g = arrays[bn + ".weight"].astype(np.float64)
            b = arrays[bn + ".bias"].astype(np.float64)
            mu = arrays[bn + ".running_mean"].astype(np.float64)
            var = arrays[bn + ".running_var"].astype(np.float64)
            a = g / np.sqrt(var + eps)
            folded[l["name"]] = (w * a.reshape(-1, 1, 1, 1), b - mu * a)
        elif l["type"] == "fc":
            folded[l["name"]] = (arrays[l["weight"]].astype(np.float64),
                                 arrays[l["bias"]].astype(np.float64))
    return folded


def float_forward(graph, folded, x, record=None):
    """Run the folded float model. If record is a dict, keep max |t| per tensor."""
    t = {"input": torch.from_numpy(x)}
    logits = None
    for l in graph["layers"]:
        op = l["type"]
        if op == "conv":
            w, b = folded[l["name"]]
            y = conv64(t[l["input"]], torch.from_numpy(w), l["stride"], l["padding"],
                       l["groups"]) + torch.from_numpy(b).reshape(1, -1, 1, 1)
            if l["relu"]:
                y = F.relu(y)
        elif op == "add":
            y = t[l["inputs"][0]] + t[l["inputs"][1]]
            if l["relu"]:
                y = F.relu(y)
        elif op == "gap":
            y = t[l["input"]].mean(dim=(2, 3))
        elif op == "fc":
            w, b = folded[l["name"]]
            logits = t[l["input"]] @ torch.from_numpy(w).T + torch.from_numpy(b)
            continue
        t[l["output"]] = y
        if record is not None:
            m = float(y.abs().max())
            record[l["output"]] = max(record.get(l["output"], 0.0), m)
    return logits


# ---------------------------------------------------------------------------
# Quantize the model (QUANT.md section 2)
# ---------------------------------------------------------------------------

def quantize_model(graph, folded, act_max, s_in):
    scale = {"input": s_in}
    for name, m in act_max.items():
        scale[name] = (m / Q_MAX) if m > 0 else 1.0

    qlayers = []
    weights = []          # int8 arrays, in layer order
    offset = 0
    for l in graph["layers"]:
        op = l["type"]
        q = {"op": op, "name": l["name"]}
        if op == "conv":
            w_f, b_f = folded[l["name"]]
            cout = w_f.shape[0]
            s_x, s_y = scale[l["input"]], scale[l["output"]]
            # QUANT.md 2.2: per output channel, big enough to cover the weights
            # and to keep |b_q| <= 2^30. The second term matters for "dead"
            # channels whose weights weight decay pushed to almost zero.
            wmax = np.abs(w_f.reshape(cout, -1)).max(axis=1)
            s_w = np.maximum(wmax / Q_MAX, np.abs(b_f) / (s_x * 2.0 ** 30))
            s_w = np.where(s_w > 0, s_w, 1.0)
            w_q = np.clip(np.rint(w_f / s_w.reshape(-1, 1, 1, 1)), -Q_MAX, Q_MAX).astype(np.int8)
            b_q = np.rint(b_f / (s_x * s_w)).astype(np.int64)
            mult = [multiplier(s_x * sw / s_y) for sw in s_w]
            q.update({
                "input": l["input"], "output": l["output"], "relu": l["relu"],
                "cin": l["cin"], "cout": l["cout"], "kernel": l["kernel"],
                "stride": l["stride"], "padding": l["padding"], "groups": l["groups"],
                "in_shape": l["in_shape"], "out_shape": l["out_shape"],
                "weight_offset": offset, "weight_count": int(w_q.size),
                "bias": [int(v) for v in b_q],
                "m0": [m for m, _ in mult], "shift": [s for _, s in mult],
                "weight_scale": [float(v) for v in s_w],
            })
            fan_in = (l["cin"] // l["groups"]) * l["kernel"] ** 2
        elif op == "add":
            a, b = l["inputs"]
            s_y = scale[l["output"]]
            ma, mb = multiplier(scale[a] / s_y), multiplier(scale[b] / s_y)
            q.update({"inputs": [a, b], "output": l["output"], "relu": l["relu"],
                      "shape": l["shape"], "m0": [ma[0], mb[0]], "shift": [ma[1], mb[1]]})
            w_q, fan_in = None, 0
        elif op == "gap":
            c, h, w = l["in_shape"]
            m = multiplier(scale[l["input"]] / (h * w * scale[l["output"]]))
            q.update({"input": l["input"], "output": l["output"], "in_shape": l["in_shape"],
                      "m0": [m[0]], "shift": [m[1]]})
            w_q, fan_in = None, 0
        elif op == "fc":
            w_f, b_f = folded[l["name"]]
            s_w = max(float(np.abs(w_f).max()) / Q_MAX,             # one scale for the matrix
                      float(np.abs(b_f).max()) / (scale[l["input"]] * 2.0 ** 30))
            w_q = np.clip(np.rint(w_f / s_w), -Q_MAX, Q_MAX).astype(np.int8)
            b_q = np.rint(b_f / (scale[l["input"]] * s_w)).astype(np.int64)
            q.update({"input": l["input"], "output": "logits",
                      "in_features": l["in_features"], "out_features": l["out_features"],
                      "weight_offset": offset, "weight_count": int(w_q.size),
                      "bias": [int(v) for v in b_q], "weight_scale": s_w,
                      "logit_scale": scale[l["input"]] * s_w})
            fan_in = l["in_features"]
        else:
            raise ValueError("unknown layer type " + op)

        # QUANT.md section 8: accumulator and bias must fit in int32.
        if w_q is not None:
            bmax = max(abs(v) for v in q["bias"])
            assert bmax < 2 ** 31, "bias overflows int32 in " + l["name"]
            assert fan_in * 128 * Q_MAX + bmax < 2 ** 31, "accumulator may overflow in " + l["name"]
            weights.append(w_q.reshape(-1))
            offset += w_q.size
        qlayers.append(q)

    return qlayers, np.concatenate(weights), scale


# ---------------------------------------------------------------------------
# Integer model (QUANT.md sections 4 to 6)
# ---------------------------------------------------------------------------

def conv64(x, w, stride, padding, groups):
    """float64 conv without bias: unfold (only moves data) + matrix multiply.

    Used instead of F.conv2d, which was wrong on PyTorch 2.2 CPU with more
    than one thread (see the top of this file).
    """
    n, _, h, wd = x.shape
    cout, cg, k, _ = w.shape
    ho = (h + 2 * padding - k) // stride + 1
    wo = (wd + 2 * padding - k) // stride + 1
    w = w.to(torch.float64)
    if not (cg == 1 and cout == groups):
        cols = F.unfold(x.to(torch.float64), k, padding=padding, stride=stride)   # [n, cin*k*k, L]
    if groups == 1:
        # One big [cout, K] x [K, n*L] multiply: much faster than a batched one.
        kk, L = cols.shape[1], cols.shape[2]
        flat = cols.permute(1, 0, 2).reshape(kk, n * L)
        y = (w.reshape(cout, -1) @ flat).reshape(cout, n, L).permute(1, 0, 2)
    elif cg == 1 and cout == groups:
        # Depthwise: add up k*k shifted copies of the padded input, each times
        # its per-channel weight. No unfold: it would copy the data k*k times.
        xp = F.pad(x.to(torch.float64), (padding, padding, padding, padding))
        y = torch.zeros(n, cout, ho, wo, dtype=torch.float64)
        for ky in range(k):
            for kx in range(k):
                patch = xp[:, :, ky:ky + stride * (ho - 1) + 1:stride,
                           kx:kx + stride * (wo - 1) + 1:stride]
                y += patch * w[:, 0, ky, kx].reshape(1, cout, 1, 1)
        return y
    else:
        cols = cols.reshape(n, groups, cg * k * k, -1)
        wg = w.reshape(groups, cout // groups, cg * k * k)
        y = torch.einsum("gok,ngkl->ngol", wg, cols)
    return y.reshape(n, cout, ho, wo)


def conv_sum(x, w, stride, padding, groups):
    """Integer conv sum (no bias) as int64, checked to be exact."""
    return _exact_int(conv64(x, w, stride, padding, groups))


def _exact_int(y):
    """float64 sums of integers -> int64, after checking they are exact."""
    r = torch.round(y)
    err = float((y - r).abs().max()) if y.numel() else 0.0
    assert err < 1e-6, "integer sum not exact (error %g)" % err
    return r.to(torch.int64)


def int_forward(qlayers, weights, x_q):
    """x_q: int64 [N,3,32,32] -> int64 logits [N,10]."""
    t = {"input": x_q}
    wt = torch.from_numpy(weights.astype(np.int64))
    for q in qlayers:
        op = q["op"]
        if op == "conv":
            cout = q["cout"]
            k = q["kernel"]
            w = wt[q["weight_offset"]:q["weight_offset"] + q["weight_count"]]
            w = w.reshape(cout, q["cin"] // q["groups"], k, k)
            acc = conv_sum(t[q["input"]], w, q["stride"], q["padding"], q["groups"])
            acc = acc + torch.tensor(q["bias"], dtype=torch.int64).reshape(1, -1, 1, 1)
            m0 = torch.tensor(q["m0"], dtype=torch.int64).reshape(1, -1, 1, 1)
            sh = torch.tensor(q["shift"], dtype=torch.int64).reshape(1, -1, 1, 1)
            y = clamp8(requant(acc, m0, sh), 0 if q["relu"] else -128)
        elif op == "add":
            a, b = (t[n] for n in q["inputs"])
            m0 = torch.tensor(q["m0"], dtype=torch.int64)
            sh = torch.tensor(q["shift"], dtype=torch.int64)
            y = clamp8(requant(a, m0[0], sh[0]) + requant(b, m0[1], sh[1]),
                       0 if q["relu"] else -128)
        elif op == "gap":
            s = t[q["input"]].sum(dim=(2, 3))
            y = clamp8(requant(s, torch.tensor(q["m0"][0]), torch.tensor(q["shift"][0])), 0)
        elif op == "fc":
            w = wt[q["weight_offset"]:q["weight_offset"] + q["weight_count"]]
            w = w.reshape(q["out_features"], q["in_features"])
            acc = _exact_int(t[q["input"]].to(torch.float64) @ w.to(torch.float64).T)
            return acc + torch.tensor(q["bias"], dtype=torch.int64)
        t[q["output"]] = y
    raise ValueError("model has no fc layer")


# ---------------------------------------------------------------------------

def batched(fn, x, bs=250):
    return torch.cat([fn(x[i:i + bs]) for i in range(0, len(x), bs)])


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--run", required=True, help="training run dir with model_fp32.npz and model_graph.json")
    ap.add_argument("--out", required=True, help="output dir for model.json and weights.bin")
    ap.add_argument("--data", required=True, help="dir that holds cifar-10-batches-py")
    ap.add_argument("--eval-out", help="optional dir for test_images_q.bin, labels.bin, ref_logits.bin")
    args = ap.parse_args()
    torch.set_num_threads(os.cpu_count() or 1)

    graph = json.load(open(os.path.join(args.run, "model_graph.json")))
    arrays = dict(np.load(os.path.join(args.run, "model_fp32.npz")))
    mean, std = graph["mean"], graph["std"]
    folded = fold_bn(graph, arrays)

    # Calibration: first 1000 training images, no augmentation.
    train_px, _ = load_cifar(args.data, "train")
    act_max = {}
    with torch.no_grad():
        float_forward(graph, folded, normalize(train_px[:CALIB_COUNT], mean, std), act_max)

    s_in = input_scale(mean, std)
    qlayers, weights, scale = quantize_model(graph, folded, act_max, s_in)

    # Evaluate FP32 (folded) and INT8 on the 10,000 test images.
    test_px, labels = load_cifar(args.data, "test")
    x = normalize(test_px, mean, std)
    with torch.no_grad():
        fp_logits = batched(lambda b: float_forward(graph, folded, b.numpy()), torch.from_numpy(x))
        x_q = torch.from_numpy(quantize_input(x, s_in))
        q_logits = batched(lambda b: int_forward(qlayers, weights, b), x_q)
    assert int(q_logits.abs().max()) < 2 ** 31, "logit overflows int32"
    lab = torch.from_numpy(labels)
    fp_acc = 100.0 * float((fp_logits.argmax(1) == lab).double().mean())
    train_acc = json.load(open(os.path.join(args.run, "summary.json")))["final_test_acc"]
    assert abs(fp_acc - train_acc) <= 0.1, (
        "folded FP32 model gives %.2f%%, training run gave %.2f%%: the float path is wrong"
        % (fp_acc, train_acc))
    q_acc = 100.0 * float((q_logits.argmax(1) == lab).double().mean())
    agree = 100.0 * float((fp_logits.argmax(1) == q_logits.argmax(1)).double().mean())

    os.makedirs(args.out, exist_ok=True)
    model = {
        "model": graph["model"],
        "rules": "workload/common/QUANT.md",
        "input_shape": graph["input_shape"],
        "num_classes": graph["num_classes"],
        "mean": mean, "std": std, "input_scale": s_in,
        "calibration": {"set": "CIFAR-10 train, images 0..%d" % (CALIB_COUNT - 1),
                        "method": "max abs (min-max)"},
        "tensor_scale": scale,
        "weights_file": "weights.bin",
        "weights_bytes": int(weights.size),
        "layers": qlayers,
    }
    with open(os.path.join(args.out, "model.json"), "w") as f:
        json.dump(model, f, indent=1)
        f.write("\n")
    weights.astype(np.int8).tofile(os.path.join(args.out, "weights.bin"))

    result = {
        "model": graph["model"],
        "test_images": int(len(labels)),
        "fp32_folded_acc": round(fp_acc, 2),
        "int8_acc": round(q_acc, 2),
        "drop": round(fp_acc - q_acc, 2),
        "top1_agreement_fp32_vs_int8": round(agree, 2),
    }
    with open(os.path.join(args.out, "accuracy.json"), "w") as f:
        json.dump(result, f, indent=2)
        f.write("\n")

    if args.eval_out:
        os.makedirs(args.eval_out, exist_ok=True)
        x_q.numpy().astype(np.int8).tofile(os.path.join(args.eval_out, "test_images_q.bin"))
        labels.astype(np.int32).tofile(os.path.join(args.eval_out, "labels.bin"))
        q_logits.numpy().astype(np.int32).tofile(os.path.join(args.eval_out, "ref_logits.bin"))

    print(json.dumps(result))


if __name__ == "__main__":
    sys.exit(main())
