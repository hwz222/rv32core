# MobileNet quantization report

RV32IMA has no floating-point unit, so the trained FP32 model cannot run on
this core as it is. This reports the post-training quantization (PTQ) that
turns it into an integer model, and what that costs in accuracy.

**Headline: 90.79% -> 90.88% top-1 on the CIFAR-10 test set. The INT8 model
scores 0.09 points higher; see section 3 for why that is noise, not a gain.**

---

## 1. The model

`mobilenet_v1_a050` — MobileNetV1 at width multiplier alpha = 0.5, so that
it fits this core's 2 MB data SRAM.

| | |
|---|---|
| Input | 3x32x32, CIFAR-10, 10 classes |
| Stem | conv3x3 3->16, stride 1, BN, ReLU |
| Body | 13 blocks: dw3x3-BN-ReLU -> pw1x1-BN-ReLU |
| Channels | 16->32->64->64->128->128->256 x6 ->512->512 |
| Strides | 1,2,1,2,1,2,1,1,1,1,1,2,1 |
| Head | global average pool -> FC 512->10 |
| Parameters | 812,768 |
| Multiply-accumulates | 12,167,168 per image |

The depthwise-separable block is why this model has 2.9x fewer
multiply-accumulates than the ResNet18 in this repository while having more
parameters: a standard 3x3 convolution costs `cin*cout*9` per output
position, whereas dw+pw costs `cin*9 + cin*cout`.

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
offline; the core would have to sum the input tensor once per layer.
MobileNet already needs 6.44e8 instructions per image, so that was not worth
1 level in 256. A side effect is that real 0 maps to integer 0, so zero
padding stays zero padding.

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

**Weight scales are per output channel.** This matters more here than for a
plain convolutional network: a depthwise layer has one independent 3x3
kernel per channel, and their ranges differ by tens of times, so a single
scale for the layer would be set by the widest channel and would crush all
the others. The scale of channel c is the larger of two lower bounds:

  (i) `max|w_c| / 127` — so no weight clips;
  (ii) `|b_c| / (s_in * 2^30)` — so the quantized bias fits in int32 with
       room left for the multiply-accumulate term.

Bound (ii) is not theoretical here. Weight decay drives unused channels
towards zero while BN's beta is not decayed, and three channels of the stem
end up with `max|w|` below 1e-5 while keeping a bias of order 1. Under bound (i) alone
channel 1's bias would need about -2.2e10, far past int32; bound (ii) raises
its scale until the bias fits at exactly -2^30. Those channels are dead
either way — their whole multiply-accumulate term is bounded by 8636 against
a bias of -2^30, so the output is clamped regardless of the input.

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
| FP32 (folded) | **90.79%** | — |
| INT8 | **90.88%** | **+0.09 points** |

**The INT8 model scoring higher is not an improvement.** The two models
agree on **98.11%** of images; on the remaining 189 images the quantization error
flips the prediction, and it happens to flip slightly more of them towards
the correct class than away from it. Those are images the FP32 model was
already unsure about. The honest reading is that the accuracy cost of
quantizing this model is **within noise of zero**, not that INT8 is better
than FP32. At 10,000 images one standard error is about 0.29 points, which
is more than three times the observed difference.

Memory, against this core's 2 MB data SRAM:

| | Bytes |
|---|---|
| INT8 weights | 812,768 |
| int32 biases | 21,928 |
| requantize parameters (m0, shift) | 43,784 |
| activation arena (2 buffers, reused) | 65,536 |
| **total** | **944,016 (45.0% of the 2 MB DMEM)** |

Requantize multipliers: 5473 of them, `M` from 8.126e-10 to 0.7627, so
`k = 31 + shift` runs from 31 to 61. The largest `k` belongs to the dead
stem channels discussed above: near-zero weights give a near-zero scale,
which gives a near-zero M and therefore a large shift. 61 is inside the
range the arithmetic allows, and the tool refuses anything outside it.

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
never overflowed on those images — worth checking here, because the dead
stem channels push the static accumulator bound to 50% of int32. Measured
cost: 644M instructions per image on the ISA simulator.

Each of this model's two convolution kinds also has its own benchmark,
compared with the host build on the ISA simulator: `bench_dw` (depthwise,
blocks.2.dw) and `bench_pw` (pointwise, blocks.4.pw).

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
