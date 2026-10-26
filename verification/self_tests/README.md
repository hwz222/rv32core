# Self-tests

Test cases chosen to break the workloads, the build and the ISA simulator,
and what happened. Every case compares the host build with the RV32IMA
build on the ISA simulator; the RTL runs the same programs in lockstep with
the simulator (`../lockstep/`).

## Summary

| # | Suite | Cases | Result | Log |
|---|---|---|---|---|
| 1 | GEMM shapes and build discipline | 36 | **36 pass, 0 fail** | [`gemm_shapes.log`](gemm_shapes.log) |
| 2 | Operator layer sweep | 55 | **55 identical, 0 differ** | [`op_benches.log`](op_benches.log) |
| 3 | ISA simulator contract | 12 | **12 pass, 0 fail** | [`sim_contract.log`](sim_contract.log) |

Two related suites live elsewhere: whole-model bit-exactness in
[`../int8_models/`](../int8_models/) and riscv-tests in
[`../isa_compliance/`](../isa_compliance/).

```sh
bash verification/self_tests/run_gemm_shapes.sh            # 1  (under a minute)
bash verification/self_tests/run_gemm_shapes.sh --quick    #    without the slow shapes
python3 verification/self_tests/run_op_benches.py          # 2  (~30 s)
bash verification/self_tests/run_sim_contract.sh           # 3  (seconds)
```

Every script exits non-zero if anything fails, so each can be used as a gate.

---

## 1. GEMM shapes and build discipline (36 cases)

`run_gemm_shapes.sh`. Part A rebuilds both targets at each shape, runs the
host binary for the golden, runs the ELF on the ISA simulator, and diffs the
two line by line. Part B checks properties that no single shape can show.

### Part A: 28 shapes

| Group | Shapes | What it is there to break |
|---|---|---|
| degenerate | 1x1x1, 1x1x64, 64x1x1, 1x64x1 | a loop written `k < K-1` passes every square shape and fails at 1. Each dimension is driven to 1 **on its own**, so a failure names the loop |
| smallest non-trivial | 2x1x1, 1x2x1, 1x1x2 | the first iteration of each loop. `1x1x2` in particular catches `sum = a*b` written where `sum += a*b` was meant: that is correct for K=1 |
| powers of two | 2x2x2, 16x16x16, 64x64x64 | the easy case a tiled implementation is built around, so a regression there is still caught |
| no shared factors | 13x7x5, 17x19x23, 7x11x13 | anything assuming a dimension is a multiple of a tile width, a cache line or an SRAM word |
| off by one | 33x33x33, 31x31x31, 65x63x64 | one past and one below a power of two: both sides of every `<=` versus `<` mistake. 65x63x64 has all three in one shape |
| each dimension largest | 64x3x5, 3x64x5, 3x5x64 | the same work with a different loop dominating |
| vector shapes | 100x1x100, 1x100x100 | matrix-vector and vector-matrix: one dimension collapses, the others do not |
| odd row | 1x16x33 | a single row against a wide, odd K |
| large | 128x96x112 | non-square, 48 KB result tensor |
| slow set | 256x256x256, 417x417x417, 1x1x200000 | 1.7e7 multiply-accumulates; the largest cube the 2 MB data SRAM holds; the deepest accumulation the memory map allows |
| seed | 8x8x8 at seed 1 and seed 13 | the result must change (B5) |

On `1x1x200000`: inputs are generated in `[-7, 7]`, so `|a*b| <= 49` and the
int32 accumulator cannot overflow until `K > 2^31 / 49 = 4.4e7`. The memory
map runs out first, at about `K = 2.6e5`, so this is the closest to the
limit that can actually be built.

### Part B: 8 build-discipline and contract checks

| Check | Why it cannot be a shape |
|---|---|
| **418x418x418 must not link** | Passing means *not* producing an ELF. 417 is the largest cube that fits; at 418 the `ASSERT` in `link.ld` must reject the link. A link that succeeded would give a binary whose `.bss` runs into the stack at run time, which is far harder to diagnose than a link error |
| `make clean` removes host, ELF and header | no shape exercises it |
| rebuild A, B, A gives A's original answer | Make compares file times, and the shape is a make variable, not a file, so a plain timestamp rule hands back the **previous** shape's binary. One shape run alone can never see that bug |
| the generator is deterministic across builds | a golden made earlier is only valid if the same `(M, N, K, seed)` gives byte-identical tensors. Checked by hashing the generated header across a clean rebuild |
| a different seed changes the result | if it does not, a cached golden or a GEMM folded at compile time would go unnoticed. The `hide()` barrier in `gemm.c` is there for this |
| isa_sim leaves the ELF byte identical | SHA-256 before and after the run |
| isa_sim gives the same answer twice | a simulator that is not deterministic cannot be the reference for the RTL |
| instruction count scales with the work | 16^3 to 32^3 is 8x the work; the count goes from 31,907 to 241,972, a factor of 7.6. A flat count would mean the GEMM was folded away or the counter is wrong |

## 2. Operator layer sweep (55 layers)

`run_op_benches.py`. Each of the four operator benchmarks builds one
default layer. This sweeps the layer over **every** layer of that kind in
both models: the benchmarks take the layer as a make variable, and `MODEL`
can point at the other workload's model, so MobileNet's 3x3 stem runs
through ResNet18's `bench_conv3x3` and ResNet18's 1x1 shortcuts run through
MobileNet's `bench_pw`.

**Result: all 55 layers bit identical between the host and the ISA simulator.**

| Operator | Layers | What the default layer alone does not cover |
|---|---|---|
| depthwise | 13 | stride 1 and stride 2; 16 to 512 channels; 32x32 down to 2x2 feature maps |
| pointwise | 16 | channel counts from 16->32 up to 512->512; **stride 2** via ResNet18's shortcut convolutions, which no MobileNet pointwise layer has |
| standard 3x3 | 18 | stride 1 and stride 2; **`cin = 3`** at both stems, the only layers whose input is the image; 16 to 128 channels |
| residual add | 8 | four tensor shapes, each with its own pair of input scales |

Two are worth calling out:

- **MobileNet's stem** holds three dead channels (weights near 1e-6 with a
  bias of order 1) and therefore the most extreme requantize multipliers in
  the project, down to `M = 8.1e-10` with a shift of `k = 61`. If the
  fixed-point path had an edge case, it would be here.
- **ResNet18's `layer*.0.shortcut`** are 1x1 convolutions with **stride 2**,
  a combination that appears nowhere in MobileNet.

## 3. ISA simulator contract (12 cases)

`run_sim_contract.sh`. The other suites only run correct ELFs that finish.
These are the cases where the simulator has to **refuse**, and refuse in a
way that names the problem: a simulator that accepts a broken ELF, or spins
forever on a runaway program, hides bugs instead of finding them. The exit
codes are the ones in `isa_sim/README.md`.

| Case | Expected | Got |
|---|---|---|
| a correct workload finishes | 0 | 0 |
| a file that is not an ELF | 2 | 2 |
| a missing file | 2 | 2 |
| a truncated ELF (valid header, no body) | 2 | 2 |
| an ELF with no `result_ptr` / `result_nwords` | 2 | 2 |
| ... the same ELF with `--no-dump` | 0 | 0 |
| an infinite loop, `--max-insns 1000000` | 4 | 4 |
| an unhandled illegal instruction (`mtvec == 0`) | 3 | 3 |
| a load from IMEM (Harvard violation) | non-zero | 3 |
| no arguments | 2 | 2 |
| a missing output directory | created | created |
| `report.json` is valid JSON with a non-zero count | valid | valid |

The Harvard case matters for the RTL: the two SRAMs are physically separate
there, so a simulator that allowed a load from instruction memory would pass
a program the hardware cannot run. A lenient reference model is more
dangerous than a strict one.
