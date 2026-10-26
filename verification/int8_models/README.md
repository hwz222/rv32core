# INT8 models: bit-exact check

The INT8 accuracy in the quantization reports is
measured by `workload/common/tools/qref.py`, a Python model of the integer
arithmetic in `workload/common/QUANT.md`. That number is only true for the
core if the core computes **exactly** the same logits. This directory checks
it by running three programs on the same images and comparing every logit:

| # | Program | Images |
|---|---|---|
| 1 | `qref.py` (Python, integer arithmetic) | all 10,000 CIFAR-10 test images (reference) |
| 2 | `eval.c` + `workload/common/nn_int8.c`, built for the host | all 10,000 |
| 3 | the same C code built for RV32IMA, run on `isa_sim` | a sample (default: the first 1,000) |

Programs 2 and 3 are the same source code. Because the arithmetic is
integer-only and has no undefined behavior (sums fit in int32, see
QUANT.md section 8), the host build and the RV32IMA build must agree; step 3
checks that on the real simulator.

## How to run

The reference data comes from `qref.py --eval-out` and lives outside the
repository, in `../ml_workspace/int8_eval/<model>/` (quantized test images,
labels, reference logits).

```sh
python3 verification/int8_models/check_bitexact.py --model all
python3 verification/int8_models/check_bitexact.py --model resnet18_w16 --riscv-images 10000
```

Results are appended to `bitexact.log`. The script exits with 1 if any logit
differs.

## Files

| File | Content |
|---|---|
| `eval.c` | runs the model on many images; host: reads a file, prints logits; RV32IMA: images compiled in, logits dumped through `result_ptr` |
| `Makefile` | builds `eval_host_<model>` and `eval_<model>_<start>_<count>.elf` |
| `check_bitexact.py` | builds, runs and compares everything; writes `bitexact.log` |
| `bitexact.log` | results (kept in git as evidence) |

The generated C sources, host binaries and ELFs are build output and are
ignored by git.
