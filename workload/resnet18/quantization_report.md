# ResNet18 quantization report

RV32IMA has no floating-point unit, so the trained FP32 model cannot run on
this core as it is. This reports the post-training quantization (PTQ) that
turns it into an integer model, and what that costs in accuracy.

**Headline: 92.71% -> 92.44% top-1 on the CIFAR-10 test set, a drop of 0.27
percentage points.**

---

## 1. The model

`resnet18_w16` — ResNet18 narrowed to a width of 16 to fit in the 2 MB
data SRAM; the standard width-64 ResNet18 has about 11.7M parameters.

| | |
|---|---|
| Input | 3x32x32, CIFAR-10, 10 classes |
| Stem | conv3x3 3->16, stride 1, BN, ReLU (no max pool) |
| Body | 4 stages x 2 BasicBlocks, channels 16/32/64/128, stride 1/2/2/2 |
| Block | conv3x3-BN-ReLU -> conv3x3-BN -> + shortcut -> ReLU |
| Shortcut | conv1x1 stride 2 + BN where the stage changes shape, else identity |
| Head | global average pool -> FC 128->10 |
| Parameters | 699,056 |
| Multiply-accumulates | 35,046,656 per image |

Training: SGD momentum 0.9 Nesterov, lr 0.1 cosine to 0, weight decay 5e-4
(not applied to BN or bias), 200 epochs, batch 128, RandomCrop(32, pad 4)
and horizontal flip, seed 0. The reported FP32 number is the last epoch, not
the best one — picking the best epoch by the test set would report a number
the test set no longer measures.

## 2. Quantization method

Plain **post-training** quantization: the FP32 model is trained once and
then converted. There is no quantization-aware training, no retraining and
no fine-tuning after the conversion, so the FP32 checkpoint and the INT8
model describe the same trained network.

The full derivation is in `workload/common/QUANT.md`; the implementation is
`workload/common/tools/qref.py`. In short:

**Symmetric, no zero point.** A real number `r` and its integer `q` are
related by `r = s * q` — scale only. An asymmetric scheme `r = s * (q - z)`
would use all 256 INT8 levels, but expanding a convolution under it leaves a
term `z_w * sum(q_x)` that depends on the input and so cannot be folded
offline; the core would have to sum the input tensor once per layer. ResNet18
already needs 7.97e8 instructions per image, so that was not worth 1 level in
256. A side effect is that real 0 maps to integer 0, so zero padding stays
zero padding.

**Scale denominator 127, not 128.** INT8 spans [-128, 127], which is not
symmetric. Dividing by 128 would map `+max|r|` to +128, which clips to 127,
while `-max|r|` maps to -128 and does not clip — a systematic, one-sided
error per channel. Dividing by 127 makes both ends land exactly on the
boundary, at the cost of never using -128.

**BatchNorm is folded into the convolution before it.** At inference time BN
is an affine map per channel, so its scale and shift are absorbed into the
weights and into a bias the convolution did not previously have. Nothing is
left to compute at runtime. Folded FP32 accuracy is checked against the
trained accuracy before quantizing; if they differ by more than 0.1 points
the tool stops.

**Weight scales are per output channel.** One scale for a whole layer would
be set by its widest channel, and every narrower channel would lose range.
The scale of channel c is the larger of two lower bounds:

  (i) `max|w_c| / 127` — so no weight clips;
  (ii) `|b_c| / (s_in * 2^30)` — so the quantized bias fits in int32 with
       room left for the multiply-accumulate term.

Bound (ii) exists because weight decay drives unused channels towards zero
while BN's beta is not decayed, which leaves channels with near-zero weights
and a non-trivial bias. Under bound (i) alone their bias would need about
2e10, far past int32.

**Activation scales come from calibration.** The first 1000 CIFAR-10 training
images are run in FP32 and the maximum absolute value of each intermediate
tensor is recorded; the scale is `max|t| / 127` (min-max, no percentile
clipping). The input image scale needs no data: pixels are bounded to [0, 1],
so after normalization the extremes are at p=0 and p=1, giving
`s_in = 1.6744e-2` analytically.

**Requantization uses a fixed-point multiplier.** A layer accumulates in
int32 at scale `s_in * s_w[c]`, and the result must be returned to INT8 at
the output scale. That means multiplying by `M = s_in * s_w[c] / s_out`,
which is not an integer. Each M is stored as a pair `(m0, shift)` with
`M = m0 * 2^-(31+shift)` and `2^30 <= m0 < 2^31`, so the runtime work is one
64-bit multiply, one add of the rounding constant and one arithmetic shift:
rounded exactly once, no division, no floating point. On RV32IMA the 64-bit
multiply is `mul` plus `mulh`.

**What the core actually executes.** Only these: int8 x int8 multiply-add
into int32, the requantize above, clamp to [0, 127] (ReLU folded into the
clamp) or [-128, 127], and int32 addition. No softmax — the prediction is the
argmax of the int32 logits, and argmax does not need the softmax.

## 3. Results

CIFAR-10 test set, all 10,000 images. The FP32 column is the same model
after BatchNorm folding, which is the fair baseline: folding alone is exact
and changes nothing.

| | Top-1 | vs FP32 |
|---|---|---|
| FP32 (folded) | **92.71%** | — |
| INT8 | **92.44%** | **-0.27 points** |

Top-1 agreement between the FP32 and the INT8 model: **98.51%** of images
get the same predicted class.

Memory, against this core's 2 MB data SRAM:

| | Bytes |
|---|---|
| INT8 weights | 699,056 |
| int32 biases | 4,840 |
| requantize parameters (m0, shift) | 9,736 |
| activation arena (3 buffers, reused) | 49,152 |
| **total** | **762,784 (36.4% of the 2 MB DMEM)** |

Requantize multipliers: 1217 of them, `M` from 1.727e-05 to 1.044, so
`k = 31 + shift` runs from 30 to 46 — comfortably inside the range the
arithmetic allows.

## 4. Evidence that this number describes the core

The accuracy above is measured by `qref.py`, which is Python. That only
describes what the RV32IMA core will do if the core produces the same
logits, so `verification/int8_models/` runs three programs on the same
images and diffs every logit:

1. `qref.py` — Python, sums in int64
2. `eval.c` + `nn_int8.c` built for the host — int32 accumulator
3. the same source built for RV32IMA, run on `isa_sim`

Result (`verification/int8_models/bitexact.log`): **0 logits differ**, over
10,000 images on the host and 1,000 on the simulator. Because program 1 sums
in int64 and cannot overflow, the agreement also shows the int32 accumulator
never overflowed on those images. Measured cost: 797M instructions per image
on the ISA simulator.

The model's two repeated operators also have their own benchmarks,
compared with the host build on the ISA simulator: `bench_conv3x3` (3x3
convolution, layer2.1.conv1) and `bench_resadd` (residual add,
layer1.0.add).

## 5. Files and how to reproduce

| Path | What |
|---|---|
| `model_int8/model.json` | layer table, scales, (m0, shift) pairs |
| `model_int8/weights.bin` | INT8 weights and int32 biases |
| `model_int8/accuracy.json` | the numbers in section 3 |
| `../common/QUANT.md` | the arithmetic, derived |
| `../common/tools/qref.py` | the quantizer and the integer reference |
| `../common/nn_int8.c` | the kernels the core runs |
| `../../verification/int8_models/` | the three-way bit-exact check |

Quantization is a one-time offline step, and only its output is in the
repository: building and running the workloads needs no torch, and no
Makefile calls `qref.py`. Rerunning the quantization needs torch and the
FP32 checkpoint, which is not in this repository.
