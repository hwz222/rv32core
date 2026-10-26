"""Size and cost of each model on the RV32IMA core (INT8 inference).

Prints parameters, DMEM use and MAC count for every model, and exits
non-zero when a model needs more than 80% of the 2 MB data SRAM.

Only uses torch and the Python standard library, so it runs on the
container's CPU-only PyTorch.

    python ml/model_stats.py            # table
    python ml/model_stats.py --json     # JSON
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from models import build_model  # noqa: E402

KB = 1024
DMEM_BYTES = 2 * 1024 * 1024
DMEM_LIMIT = 0.80

# Fixed DMEM use, same for every model (bytes).
FIXED_BYTES = {
    "input": 3 * 32 * 32,   # INT8 input image
    "logits": 10 * 4,       # INT32 logits
    "stack": 4096,
    "misc": 1024,
}

# Default models: the two the workloads use.
DEFAULT_MODELS = [("resnet18", {"width": 16}), ("mobilenet_v1", {"alpha": 0.5})]


def _numel(shape):
    n = 1
    for d in shape:
        n *= d
    return n


def compute_stats(model, graph=None):
    """Return a dict of size and cost numbers for one model."""
    if graph is None:
        graph = model.graph()
    params = 0
    macs = 0
    out_channels = 0
    max_map = _numel(graph["input_shape"])
    for l in graph["layers"]:
        t = l["type"]
        if t == "conv":
            k2 = l["kernel"] * l["kernel"]
            cin_g = l["cin"] // l["groups"]
            params += l["cout"] * cin_g * k2
            ho, wo = l["out_shape"][1], l["out_shape"][2]
            macs += ho * wo * l["cout"] * cin_g * k2
            out_channels += l["cout"]
            max_map = max(max_map, _numel(l["in_shape"]), _numel(l["out_shape"]))
        elif t == "fc":
            params += l["in_features"] * l["out_features"]
            macs += l["in_features"] * l["out_features"]
            out_channels += l["out_features"]
        elif t == "add":
            max_map = max(max_map, _numel(l["shape"]))
        elif t == "gap":
            max_map = max(max_map, _numel(l["in_shape"]))

    int8_weight_bytes = params
    bias_bytes = 4 * out_channels        # INT32 bias per output channel
    requant_bytes = 8 * out_channels     # multiplier + shift per output channel
    act_bytes = model.act_buffers * max_map
    fixed_bytes = sum(FIXED_BYTES.values())
    total = int8_weight_bytes + bias_bytes + requant_bytes + act_bytes + fixed_bytes
    return {
        "model": graph["model"],
        "params": params,
        "int8_weight_bytes": int8_weight_bytes,
        "bias_bytes": bias_bytes,
        "requant_bytes": requant_bytes,
        "act_buffers": model.act_buffers,
        "act_max_map_bytes": max_map,
        "act_bytes": act_bytes,
        "fixed_bytes": fixed_bytes,
        "dmem_total_bytes": total,
        "dmem_bytes": DMEM_BYTES,
        "dmem_percent": 100.0 * total / DMEM_BYTES,
        "macs": macs,
    }


def format_table(rows):
    head = ("model", "params", "INT8 weight", "bias+requant", "activation",
            "fixed", "total", "DMEM %", "MAC")
    lines = [head]
    for r in rows:
        lines.append((
            r["model"],
            "%.3fM" % (r["params"] / 1e6),
            "%.1f KB" % (r["int8_weight_bytes"] / KB),
            "%.1f KB" % ((r["bias_bytes"] + r["requant_bytes"]) / KB),
            "%.0f KB x %d = %.0f KB" % (r["act_max_map_bytes"] / KB, r["act_buffers"],
                                        r["act_bytes"] / KB),
            "%.1f KB" % (r["fixed_bytes"] / KB),
            "%.1f KB" % (r["dmem_total_bytes"] / KB),
            "%.1f%%" % r["dmem_percent"],
            "%.2fM" % (r["macs"] / 1e6),
        ))
    widths = [max(len(row[i]) for row in lines) for i in range(len(head))]
    out = []
    for n, row in enumerate(lines):
        out.append("  ".join(c.ljust(w) for c, w in zip(row, widths)).rstrip())
        if n == 0:
            out.append("  ".join("-" * w for w in widths))
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--json", action="store_true", help="print JSON instead of a table")
    args = ap.parse_args()

    rows = [compute_stats(build_model(name, **kw)) for name, kw in DEFAULT_MODELS]
    if args.json:
        print(json.dumps({"dmem_limit_percent": 100 * DMEM_LIMIT, "models": rows}, indent=2))
    else:
        print(format_table(rows))
        print("DMEM = %d bytes (%d KB), limit %.0f%%; KB = 1024 bytes"
              % (DMEM_BYTES, DMEM_BYTES // KB, 100 * DMEM_LIMIT))

    over = [r["model"] for r in rows if r["dmem_total_bytes"] > DMEM_LIMIT * DMEM_BYTES]
    if over:
        print("ERROR: over %.0f%% of DMEM: %s" % (100 * DMEM_LIMIT, ", ".join(over)),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
