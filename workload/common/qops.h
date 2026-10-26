/* INT8 requantize helpers shared by every quantized workload.
 * The rules are in workload/common/QUANT.md, section 3. The Python
 * reference (tools/qref.py) does the same operations, bit for bit. */
#ifndef QOPS_H
#define QOPS_H

#include <stdint.h>

/* round(x * M) with M = m0 * 2^-(31 + shift), one rounding, half up.
 * m0 is in [2^30, 2^31) and 31 + shift is in [1, 62], so the 64-bit product
 * is exact and cannot overflow. On RV32IMA gcc builds the 64-bit multiply
 * from mul/mulh and does the add and shift inline: no libgcc is needed. */
static inline int64_t requant(int32_t x, int32_t m0, int32_t shift) {
    const int64_t p = (int64_t)x * (int64_t)m0;
    const int k = 31 + shift;
    return (p + ((int64_t)1 << (k - 1))) >> k;
}

/* Clamp to [lo, 127] in 64 bits, then narrow. lo = 0 is the ReLU. */
static inline int8_t clamp8(int64_t v, int32_t lo) {
    return (int8_t)(v < lo ? lo : (v > 127 ? 127 : v));
}

#endif
