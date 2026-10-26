# ml/ — FP32 training for the MobileNet and ResNet18 workloads

## Purpose

The core runs MobileNet and ResNet18 inference as post-training quantized
(PTQ) integer models, since RV32IMA has no floating point. This folder trains the FP32 baseline models on
CIFAR-10 and exports their weights in a form that the quantizer can read
with numpy only.

This step does **not** quantize: that is `workload/common/tools/qref.py`
(method in `workload/common/QUANT.md`).

The training code is **not** part of the build. No Makefile calls these
scripts. The repository holds the quantized weights and the quantization
reports, not the PyTorch run outputs.

## Models

Both models take a `3x32x32` CIFAR-10 image and return 10 logits.

| run name | model | params | MACs | DMEM use (INT8) |
|---|---|---|---|---|
| `resnet18_w16` | ResNet18, base width 16, 3x3 stem, no maxpool | 0.70M | 35.0M | ~753 KB (37%) |
| `mobilenet_v1_a050` | MobileNetV1, alpha 0.5, stride-1 stem | 0.81M | 12.2M | ~930 KB (45%) |

Rules that come from the core (no FPU, no SIMD, INT32 accumulators):

- Convs have no bias and are always followed by BatchNorm, so BN can be
  folded into the conv.
- The only activation is ReLU. No softmax, dropout, SE block, ReLU6 or swish.
- The C code takes the argmax of the logits.
- Total DMEM use (INT8 weights, INT32 bias, requantize data, activation
  buffers, input, stack) must stay under 80% of the 2 MB data SRAM.
  `model_stats.py` checks this.

## Files

| file | what it does |
|---|---|
| `models.py` | `ResNet18(width=16)`, `MobileNetV1(alpha=0.5)`, `build_model(name, **kw)`, and `graph()` that lists all layers in run order |
| `data.py` | CIFAR-10 datasets and loaders, `MEAN` / `STD`, augmentation |
| `model_stats.py` | parameter count, DMEM use and MACs; exits non-zero above 80% of DMEM. Needs only torch (works on CPU-only torch 2.2) |
| `train.py` | train, evaluate, save checkpoints, export weights |
| `requirements.txt` | torch, torchvision, numpy |

## Environment

Use a virtual environment with a CUDA build of PyTorch. Datasets and
run outputs live outside the repository, in `../ml_workspace/`. Exact versions
of each run are saved in its `config.json`.

```sh
source ../ml_workspace/.venv/bin/activate
```

## Commands

Run from the repository root.

```sh
# size and cost table (add --json for JSON)
python ml/model_stats.py

# quick check: 1 epoch, 20 batches
python ml/train.py --model resnet18 --epochs 1 --limit-batches 20 \
    --out ../ml_workspace/runs/_smoke_resnet18

# full training (200 epochs each)
python ml/train.py --model resnet18
python ml/train.py --model mobilenet_v1

# continue after a stop
python ml/train.py --model resnet18 --resume
```

Options: `--width 16`, `--alpha 0.5`, `--epochs 200`, `--batch-size 128`,
`--lr 0.1`, `--wd 5e-4`, `--seed 0`, `--data ../ml_workspace/datasets`,
`--out ../ml_workspace/runs/<run name>`, `--resume`, `--limit-batches N`,
`--no-amp`, `--workers 4`, `--device auto`.

Default paths are relative to the repository root, so they are the same
on the host and in the container. If `--data` has no CIFAR-10 copy,
torchvision downloads it there.

## Training setup

| item | value |
|---|---|
| data | CIFAR-10, 50,000 train images; the 10,000 test images are the validation set |
| normalize | mean `(0.4914, 0.4822, 0.4465)`, std `(0.2470, 0.2435, 0.2616)` |
| augmentation (train only) | RandomCrop(32, padding=4), RandomHorizontalFlip |
| optimizer | SGD, momentum 0.9, Nesterov, lr 0.1, weight decay 5e-4 (not on BN or bias) |
| lr schedule | cosine from 0.1 to 0, one step per epoch |
| epochs / batch | 200 / 128 |
| precision | AMP (float16) for training on GPU; evaluation is always FP32 |
| seed | 0 for Python, NumPy and PyTorch; `cudnn.benchmark = True`, so runs are not bit-exact |
| model choice | the last epoch; no best-epoch pick on the test set |

No quantization-aware training. BN, weight decay and ReLU keep the value
ranges small enough for PTQ.

## Outputs

Each run writes to `../ml_workspace/runs/<run name>/`:

| file | content |
|---|---|
| `config.json` | all options, seed, git commit, Python / torch / torchvision / CUDA versions, GPU, `cudnn.benchmark` |
| `metrics.csv` | `epoch,lr,train_loss,train_acc,test_loss,test_acc,epoch_time_s` (accuracy in percent) |
| `train.log` | full log |
| `ckpt_last.pt` | last epoch: model, optimizer, scheduler, scaler, epoch, RNG state (for `--resume`) |
| `model_fp32.npz` | final weights, float32, keys are `state_dict` names. BN is **not** folded (`weight`, `bias`, `running_mean`, `running_var`). No `num_batches_tracked` |
| `model_graph.json` | layers in run order (see below) |
| `summary.json` | params, DMEM use, MACs, final test / train accuracy, train time, device, versions, git commit |

Read the weights with numpy only:

```python
import numpy as np
w = np.load("model_fp32.npz")
print(w["stem.0.weight"].shape)   # (16, 3, 3, 3)
```

### model_graph.json

`layers` lists every layer in run order. Tensor names are `input`, `t0`,
`t1`, ... and `logits`. Layer types:

| type | fields |
|---|---|
| `conv` | `input`, `output`, `cin`, `cout`, `kernel`, `stride`, `padding`, `groups`, `weight` (npz key), `bn` (npz key prefix), `relu`, `in_shape`, `out_shape` |
| `add` | `inputs` (two tensors), `output`, `relu`, `shape` (ResNet shortcut) |
| `gap` | global average pool: `input`, `output`, `in_shape`, `out_shape` |
| `fc` | `input`, `output`, `weight`, `bias`, `in_features`, `out_features` |

A `conv` with `relu: false` has no ReLU after BN. In ResNet18 the ReLU
after the shortcut add is on the `add` layer.

The file also has `input_shape`, `num_classes`, `mean`, `std` and `bn_eps`.
