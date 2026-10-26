/* ResNet18 residual-add microbenchmark.
 *
 * Runs one real skip-connection addition of the quantized ResNet18.
 *
 * The two tensors being added were quantized with different scales, so
 * they cannot simply be added: each side is first requantized to the
 * output scale with its own (m0, shift) pair, then the two are added and
 * clamped. This is the only operator with two input tensors, and the only
 * one whose kernel does two requantize steps per value instead of one
 * (QUANT.md section 5).
 *
 *   make bench_resadd_host   -> ./bench_resadd_host, native; the golden
 *   make bench_resadd_riscv  -> ./bench_resadd.elf, RV32IMA bare metal
 */
#include "bench_resadd_data.h"
#include "../common/bench_run.h"
