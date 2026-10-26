/* Integer matrix multiply, C = A * B.
 *
 *   A is GEMM_M x GEMM_K, B is GEMM_K x GEMM_N, C is GEMM_M x GEMM_N,
 *   all int32 and row-major.
 *
 * The shape and the input values are chosen when building, not here:
 * tools/make_gemm_data.py writes them into gemm_data.h (see the Makefile).
 * The same file builds natively, where the host binary prints the golden
 * result, and for RV32IMA bare metal, where the simulator and the RTL
 * testbench dump C from data memory. The two outputs are compared word for
 * word.
 *
 * This is the plain i-j-k loop on purpose: it is the reference workload for
 * checking the simulator and the core, and its inner loop is simple enough
 * to read off the cycle count (see docs/PERFORMANCE.md).
 */
#include <stdint.h>

#include "gemm_data.h"

static int32_t gemm_c[GEMM_M * GEMM_N];

int32_t *result_ptr = gemm_c;
unsigned int result_nwords = GEMM_M * GEMM_N;

/* The inputs are compile-time constants, so at -O2 GCC may compute small
 * products while compiling and leave nothing to run. Passing each pointer
 * through an empty asm hides where it points. */
static const int32_t *hide(const int32_t *p) {
    __asm__ volatile("" : "+r"(p));
    return p;
}

static void workload_run(void) {
    const int32_t *a = hide(gemm_a);
    const int32_t *b = hide(gemm_b);
    int32_t *c = (int32_t *)hide(gemm_c);

    for (int i = 0; i < GEMM_M; i++) {
        for (int j = 0; j < GEMM_N; j++) {
            int32_t sum = 0;
            for (int k = 0; k < GEMM_K; k++)
                sum += a[i * GEMM_K + k] * b[k * GEMM_N + j];
            c[i * GEMM_N + j] = sum;
        }
    }
}

#include "harness.h"
