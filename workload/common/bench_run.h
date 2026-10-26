/* Shared body of the four operator microbenchmarks.
 *
 * Each benchmark runs ONE layer of a quantized model and prints the whole
 * output tensor, so the host build and the RV32IMA build can be diffed
 * line by line. The layer, its real weights and one input tensor come
 * from a header written at build time by tools/gen_bench_data.py.
 *
 * A benchmark includes its generated header and then this file:
 *
 *     #include "bench_dw_data.h"
 *     #include "../common/bench_run.h"
 *
 * The result is the output tensor in [channel][y][x] order, each INT8
 * value sign-extended to a 32-bit word, so it uses the same dump format
 * as every other workload (harness.h).
 */
#ifndef BENCH_RUN_H
#define BENCH_RUN_H

#include "nn_int8.h"

/* The activation arena. Holds the second input of a residual add (if the
 * layer has one) and the output tensor; gen_bench_data.py picked the
 * offsets. .bss, so the linker script puts it in the data SRAM. */
static int8_t bench_arena[BENCH_ARENA_BYTES];

/* The result, one word per output value. Exported so isa_sim and the RTL
 * testbench can find it by symbol -- its address moves when the layer or
 * the model changes, so nothing may hardcode it. */
int32_t bench_out[BENCH_OUT_BYTES];
int32_t *result_ptr = bench_out;
unsigned int result_nwords = (unsigned int)BENCH_OUT_BYTES;

static void workload_run(void) {
    int32_t logits[1];          /* unused: no benchmark runs an FC layer */

#if BENCH_IN1_OFF >= 0
    /* Residual add: the kernel reads its second input from the arena.
     * The destination is volatile on purpose. Without it gcc recognizes
     * this loop and calls memcpy, which does not exist in a -nostdlib
     * build, so the RV32IMA link fails. */
    volatile int8_t *in1 = bench_arena + BENCH_IN1_OFF;
    for (int i = 0; i < BENCH_IN_BYTES; i++)
        in1[i] = bench_input2[i];
#endif

    nn_run(&bench_model, bench_arena, bench_input, logits);

    for (int i = 0; i < BENCH_OUT_BYTES; i++)
        bench_out[i] = (int32_t)bench_arena[BENCH_OUT_OFF + i];
}

#include "harness.h"

#endif /* BENCH_RUN_H */
