"""Train an FP32 CIFAR-10 model and export it for later INT8 quantization.

Example (run from the repository root):

    python ml/train.py --model resnet18
    python ml/train.py --model mobilenet_v1 --resume

Outputs go to ../ml_workspace/runs/<run name>/ by default. See ml/README.md.
"""

import argparse
import csv
import json
import os
import platform
import random
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
import torchvision

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from data import MEAN, STD, get_loaders  # noqa: E402
from models import MODEL_NAMES, build_model, check_graph  # noqa: E402
from model_stats import compute_stats  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
WORKSPACE = REPO_ROOT.parent / "ml_workspace"
METRIC_FIELDS = ["epoch", "lr", "train_loss", "train_acc", "test_loss", "test_acc", "epoch_time_s"]


def parse_args():
    ap = argparse.ArgumentParser(description="Train an FP32 CIFAR-10 model.")
    ap.add_argument("--model", required=True, choices=MODEL_NAMES)
    ap.add_argument("--width", type=int, default=16, help="ResNet18 base width")
    ap.add_argument("--alpha", type=float, default=0.5, help="MobileNetV1 width multiplier")
    ap.add_argument("--epochs", type=int, default=200)
    ap.add_argument("--batch-size", type=int, default=128)
    ap.add_argument("--lr", type=float, default=0.1)
    ap.add_argument("--wd", type=float, default=5e-4)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--data", default=str(WORKSPACE / "datasets"))
    ap.add_argument("--out", default=None, help="default: ../ml_workspace/runs/<run name>")
    ap.add_argument("--resume", action="store_true", help="continue from <out>/ckpt_last.pt")
    ap.add_argument("--limit-batches", type=int, default=0,
                    help="only run N batches per train/eval pass (smoke test)")
    ap.add_argument("--no-amp", action="store_true", help="train in plain FP32")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--device", default="auto", help="auto, cpu, cuda or cuda:N")
    return ap.parse_args()


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------

class Logger:
    """Print to stdout and append to train.log."""

    def __init__(self, path):
        self.f = open(path, "a", buffering=1)

    def __call__(self, msg=""):
        line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
        print(line, flush=True)
        self.f.write(line + "\n")


def seed_everything(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def git_info():
    def run(*cmd):
        try:
            return subprocess.run(["git", *cmd], cwd=REPO_ROOT, capture_output=True,
                                  text=True, check=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            return None
    status = run("status", "--porcelain")
    return {"git_commit": run("rev-parse", "HEAD"),
            "git_dirty": None if status is None else bool(status)}


def version_info(device):
    info = {
        "python": platform.python_version(),
        "torch": torch.__version__,
        "torchvision": torchvision.__version__,
        "numpy": np.__version__,
        "cuda": torch.version.cuda,
        "cudnn": torch.backends.cudnn.version() if torch.backends.cudnn.is_available() else None,
        "gpu": torch.cuda.get_device_name(device) if device.type == "cuda" else None,
        "platform": platform.platform(),
    }
    return info


def write_json(path, obj):
    tmp = str(path) + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")
    os.replace(tmp, path)


def make_scaler(enabled):
    # torch.amp.GradScaler is new in torch 2.3; fall back for older versions.
    if hasattr(torch.amp, "GradScaler"):
        return torch.amp.GradScaler("cuda", enabled=enabled)
    return torch.cuda.amp.GradScaler(enabled=enabled)


def param_groups(model, wd):
    # Weight decay only on conv and FC weights (4-D and 2-D tensors).
    # BN weights, BN biases and the FC bias (1-D tensors) get no decay.
    decay, no_decay = [], []
    for p in model.parameters():
        (decay if p.ndim > 1 else no_decay).append(p)
    return [{"params": decay, "weight_decay": wd},
            {"params": no_decay, "weight_decay": 0.0}]


# ---------------------------------------------------------------------------
# Train and evaluate
# ---------------------------------------------------------------------------

def train_one_epoch(model, loader, optimizer, scaler, device, use_amp, limit):
    model.train()
    loss_sum, correct, total = 0.0, 0, 0
    for i, (x, y) in enumerate(loader):
        if limit and i >= limit:
            break
        x = x.to(device, non_blocking=True)
        y = y.to(device, non_blocking=True)
        with torch.autocast(device_type=device.type, dtype=torch.float16, enabled=use_amp):
            logits = model(x)
            loss = F.cross_entropy(logits, y)
        optimizer.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.step(optimizer)
        scaler.update()
        loss_sum += loss.item() * y.size(0)
        correct += (logits.argmax(1) == y).sum().item()
        total += y.size(0)
    return loss_sum / total, 100.0 * correct / total


@torch.no_grad()
def evaluate(model, loader, device, limit):
    # Evaluation is always plain FP32 (no autocast).
    model.eval()
    loss_sum, correct, total = 0.0, 0, 0
    for i, (x, y) in enumerate(loader):
        if limit and i >= limit:
            break
        x = x.to(device, non_blocking=True)
        y = y.to(device, non_blocking=True)
        logits = model(x).float()
        loss_sum += F.cross_entropy(logits, y, reduction="sum").item()
        correct += (logits.argmax(1) == y).sum().item()
        total += y.size(0)
    return loss_sum / total, 100.0 * correct / total


# ---------------------------------------------------------------------------
# Checkpoint and metrics
# ---------------------------------------------------------------------------

def save_checkpoint(path, model, optimizer, scheduler, scaler, epoch, train_time_s, loader_gen):
    state = {
        "epoch": epoch,  # number of finished epochs
        "train_time_s": train_time_s,
        "model": model.state_dict(),
        "optimizer": optimizer.state_dict(),
        "scheduler": scheduler.state_dict(),
        "scaler": scaler.state_dict(),
        "rng": {
            "python": random.getstate(),
            "numpy": np.random.get_state(),
            "torch": torch.get_rng_state(),
            "cuda": torch.cuda.get_rng_state_all() if torch.cuda.is_available() else [],
            "loader": loader_gen.get_state(),
        },
    }
    tmp = str(path) + ".tmp"
    torch.save(state, tmp)
    os.replace(tmp, path)


def load_checkpoint(path, model, optimizer, scheduler, scaler, loader_gen, device):
    state = torch.load(path, map_location=device, weights_only=False)
    model.load_state_dict(state["model"])
    optimizer.load_state_dict(state["optimizer"])
    scheduler.load_state_dict(state["scheduler"])
    scaler.load_state_dict(state["scaler"])
    rng = state["rng"]
    random.setstate(rng["python"])
    np.random.set_state(rng["numpy"])
    torch.set_rng_state(rng["torch"].cpu())
    if torch.cuda.is_available() and rng["cuda"]:
        torch.cuda.set_rng_state_all([s.cpu() for s in rng["cuda"]])
    loader_gen.set_state(rng["loader"].cpu())
    return state["epoch"], state["train_time_s"]


def read_metrics(path):
    if not path.exists():
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def write_metrics(path, rows):
    tmp = str(path) + ".tmp"
    with open(tmp, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=METRIC_FIELDS)
        w.writeheader()
        w.writerows(rows)
    os.replace(tmp, path)


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------

def export_npz(model, path):
    # Keys are state_dict names. BN is NOT folded. num_batches_tracked is dropped.
    arrays = {k: v.detach().cpu().float().numpy()
              for k, v in model.state_dict().items()
              if not k.endswith("num_batches_tracked")}
    np.savez(path, **arrays)
    return arrays


def check_graph_keys(graph, arrays):
    keys = set(arrays)
    for l in graph["layers"]:
        if "weight" in l:
            assert l["weight"] in keys, "missing weight " + l["weight"]
        if "bias" in l:
            assert l["bias"] in keys, "missing bias " + l["bias"]
        if "bn" in l:
            for s in ("weight", "bias", "running_mean", "running_var"):
                assert l["bn"] + "." + s in keys, "missing BN key %s.%s" % (l["bn"], s)


# ---------------------------------------------------------------------------

def main():
    args = parse_args()

    if args.device == "auto":
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    else:
        device = torch.device(args.device)
    use_amp = device.type == "cuda" and not args.no_amp

    model = build_model(args.model, width=args.width, alpha=args.alpha)
    run_name = model.name
    out = Path(args.out) if args.out else WORKSPACE / "runs" / run_name
    out.mkdir(parents=True, exist_ok=True)
    ckpt_path = out / "ckpt_last.pt"
    metrics_path = out / "metrics.csv"
    config_path = out / "config.json"

    if args.resume and not ckpt_path.exists():
        sys.exit("error: --resume given but %s does not exist" % ckpt_path)
    if not args.resume and ckpt_path.exists():
        sys.exit("error: %s already exists; use --resume or another --out" % ckpt_path)

    log = Logger(out / "train.log")
    log("run %s, out %s, resume %s" % (run_name, out, args.resume))

    seed_everything(args.seed)
    # cudnn.benchmark picks the fastest conv algorithm. The picked algorithm
    # may change between runs, so results are not bit-exact across runs.
    torch.backends.cudnn.benchmark = True

    model.to(device)
    graph = check_graph(model)
    graph["mean"] = list(MEAN)
    graph["std"] = list(STD)
    stats = compute_stats(model, graph)

    loader_gen = torch.Generator()
    loader_gen.manual_seed(args.seed)
    train_loader, train_eval_loader, test_loader = get_loaders(
        args.data, args.batch_size, args.workers, loader_gen,
        pin_memory=device.type == "cuda")

    optimizer = torch.optim.SGD(param_groups(model, args.wd), lr=args.lr,
                                momentum=0.9, nesterov=True)
    # Cosine from lr down to 0 over all epochs, stepped once per epoch.
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs,
                                                           eta_min=0.0)
    scaler = make_scaler(use_amp)

    versions = version_info(device)
    git = git_info()
    config = {
        "run_name": run_name,
        "args": vars(args),
        "out_dir": str(out.resolve()),
        "data_dir": str(Path(args.data).resolve()),
        "seed": args.seed,
        "device": str(device),
        "amp": use_amp,
        "amp_dtype": "float16" if use_amp else None,
        "eval_precision": "float32",
        "cudnn_benchmark": torch.backends.cudnn.benchmark,
        "cudnn_deterministic": torch.backends.cudnn.deterministic,
        "optimizer": "SGD(momentum=0.9, nesterov=True); no weight decay on BN and bias",
        "lr_schedule": "CosineAnnealingLR(T_max=epochs, eta_min=0), stepped per epoch",
        "augmentation": "RandomCrop(32, padding=4), RandomHorizontalFlip",
        "mean": list(MEAN),
        "std": list(STD),
        "validation_set": "CIFAR-10 test set (10,000 images)",
        "model_selection": "last epoch (no selection on the test set)",
        "versions": versions,
        **git,
        "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }

    start_epoch, train_time_s = 0, 0.0
    if args.resume:
        start_epoch, train_time_s = load_checkpoint(ckpt_path, model, optimizer, scheduler,
                                                    scaler, loader_gen, device)
        old = json.loads(config_path.read_text()) if config_path.exists() else config
        old.setdefault("resumes", []).append({
            "at": time.strftime("%Y-%m-%d %H:%M:%S"), "from_epoch": start_epoch,
            "args": vars(args), **git, "versions": versions})
        config = old
        log("resumed from epoch %d" % start_epoch)
    write_json(config_path, config)

    # Keep only rows that the checkpoint covers (a crash may leave one more).
    rows = [r for r in read_metrics(metrics_path) if int(r["epoch"]) <= start_epoch]
    write_metrics(metrics_path, rows)

    log("device %s (%s), amp %s, torch %s, cuda %s" % (
        device, versions["gpu"], use_amp, versions["torch"], versions["cuda"]))
    log("params %d, MACs %d, DMEM %.1f%%" % (stats["params"], stats["macs"],
                                            stats["dmem_percent"]))

    for epoch in range(start_epoch, args.epochs):
        t0 = time.time()
        lr = optimizer.param_groups[0]["lr"]
        train_loss, train_acc = train_one_epoch(model, train_loader, optimizer, scaler,
                                                device, use_amp, args.limit_batches)
        scheduler.step()
        test_loss, test_acc = evaluate(model, test_loader, device, args.limit_batches)
        dt = time.time() - t0
        train_time_s += dt
        rows.append({
            "epoch": epoch + 1, "lr": "%.6g" % lr,
            "train_loss": "%.4f" % train_loss, "train_acc": "%.2f" % train_acc,
            "test_loss": "%.4f" % test_loss, "test_acc": "%.2f" % test_acc,
            "epoch_time_s": "%.1f" % dt,
        })
        save_checkpoint(ckpt_path, model, optimizer, scheduler, scaler, epoch + 1,
                        train_time_s, loader_gen)
        write_metrics(metrics_path, rows)
        log("epoch %3d/%d  lr %.5f  train %.4f / %.2f%%  test %.4f / %.2f%%  %.1fs" % (
            epoch + 1, args.epochs, lr, train_loss, train_acc, test_loss, test_acc, dt))

    # ---- export (final weights = last epoch, no best-epoch selection) ----
    last = rows[-1]
    _, train_eval_acc = evaluate(model, train_eval_loader, device, args.limit_batches)
    arrays = export_npz(model, out / "model_fp32.npz")
    check_graph_keys(graph, arrays)
    write_json(out / "model_graph.json", graph)
    summary = {
        "model": run_name,
        "params": stats["params"],
        "int8_weight_bytes": stats["int8_weight_bytes"],
        "dmem_total_bytes": stats["dmem_total_bytes"],
        "dmem_percent": round(stats["dmem_percent"], 2),
        "macs": stats["macs"],
        "epochs": int(last["epoch"]),
        "acc_unit": "percent",
        "final_test_acc": float(last["test_acc"]),
        "final_train_acc": float(last["train_acc"]),
        "final_train_acc_eval": round(train_eval_acc, 2),
        "final_test_loss": float(last["test_loss"]),
        "train_time_s": round(train_time_s, 1),
        "device": str(device),
        "gpu": versions["gpu"],
        "amp": use_amp,
        "limit_batches": args.limit_batches,
        "versions": versions,
        "git_commit": git["git_commit"],
        "git_dirty": git["git_dirty"],
        "stats": stats,
        "notes": {
            "final_train_acc": "last epoch, running value on augmented batches in train mode",
            "final_train_acc_eval": "FP32, eval mode, 50,000 train images without augmentation",
        },
    }
    write_json(out / "summary.json", summary)
    log("final test acc %.2f%%, train acc %.2f%% (eval %.2f%%), train time %.1fs" % (
        summary["final_test_acc"], summary["final_train_acc"], train_eval_acc, train_time_s))
    log("done: %s" % out)


if __name__ == "__main__":
    main()
