/* ResNet18 standard 3x3 convolution microbenchmark.
 *
 * Runs one real 3x3 convolution of the quantized ResNet18 on its own.
 *
 * Standard means groups == 1 with a 3x3 window: each output value sums
 * over cin*3*3 inputs, which is the layer type the whole network is built
 * from and the one that dominates its instruction count. Zero padding is
 * exercised, so the edge handling in conv() is covered too. The 1x1
 * shortcut convolution between stages runs through the same conv()
 * kernel, so it counts as this operator too.
 *
 *   make bench_conv3x3_host   -> ./bench_conv3x3_host, native; the golden
 *   make bench_conv3x3_riscv  -> ./bench_conv3x3.elf, RV32IMA bare metal
 */
#include "bench_conv3x3_data.h"
#include "../common/bench_run.h"
