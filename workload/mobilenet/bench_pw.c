/* MobileNet pointwise (1x1) convolution microbenchmark.
 *
 * Runs one real pointwise layer of the quantized MobileNet on its own.
 *
 * Pointwise means kernel == 1 and groups == 1: no spatial window and no
 * padding, but every output channel sums over every input channel. It is
 * the opposite extreme from bench_dw.c -- all of the channel mixing, none
 * of the spatial work -- and together the two cover what a depthwise
 * separable block does. Same conv() kernel, different layer descriptor.
 *
 *   make bench_pw_host   -> ./bench_pw_host, native; stdout is the golden
 *   make bench_pw_riscv  -> ./bench_pw.elf, RV32IMA bare metal
 */
#include "bench_pw_data.h"
#include "../common/bench_run.h"
