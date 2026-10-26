/* MobileNet depthwise-convolution microbenchmark.
 *
 * Runs one real depthwise layer of the quantized MobileNet on its own, so
 * the operator is verified by itself and not only through the whole
 * network.
 *
 * Depthwise means groups == cin == cout: every output channel sees exactly
 * one input channel, so the work is cout*hout*wout*k*k multiply-adds
 * instead of the cin times more a standard convolution needs. The kernel
 * is the same conv() nn_int8.c uses for the full network -- only the
 * layer descriptor differs.
 *
 *   make bench_dw_host   -> ./bench_dw_host, native; stdout is the golden
 *   make bench_dw_riscv  -> ./bench_dw.elf, RV32IMA bare metal
 */
#include "bench_dw_data.h"
#include "../common/bench_run.h"
