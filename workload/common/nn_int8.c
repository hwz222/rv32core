/* INT8 layer kernels. Plain loops, integer only. Every layer follows
 * workload/common/QUANT.md; integer sums are exact, so the loop order does
 * not change the result. */
#include "nn_int8.h"

#include "qops.h"

static const int8_t *src(int8_t *arena, const int8_t *input, int32_t off) {
    return off == NN_INPUT ? input : arena + off;
}

/* Standard, depthwise (groups = cin = cout) and pointwise conv (QUANT.md 4). */
static void conv(const nn_layer *L, const int8_t *x, int8_t *y) {
    const int k = L->kernel, s = L->stride, p = L->padding;
    const int cin_g = L->cin / L->groups;
    const int cout_g = L->cout / L->groups;
    const int lo = L->relu ? 0 : -128;
    for (int co = 0; co < L->cout; co++) {
        const int ci0 = (co / cout_g) * cin_g;           /* first input channel of the group */
        const int8_t *wc = L->weight + co * cin_g * k * k;
        for (int oy = 0; oy < L->hout; oy++) {
            for (int ox = 0; ox < L->wout; ox++) {
                int32_t acc = L->bias[co];
                for (int ci = 0; ci < cin_g; ci++) {
                    const int8_t *xc = x + (ci0 + ci) * L->hin * L->win;
                    const int8_t *w = wc + ci * k * k;
                    for (int ky = 0; ky < k; ky++) {
                        const int iy = oy * s + ky - p;
                        if (iy < 0 || iy >= L->hin) continue;   /* zero padding */
                        for (int kx = 0; kx < k; kx++) {
                            const int ix = ox * s + kx - p;
                            if (ix < 0 || ix >= L->win) continue;
                            acc += (int32_t)xc[iy * L->win + ix] * (int32_t)w[ky * k + kx];
                        }
                    }
                }
                y[(co * L->hout + oy) * L->wout + ox] =
                    clamp8(requant(acc, L->m0[co], L->shift[co]), lo);
            }
        }
    }
}

/* Residual add: scale both inputs to the output scale, add, clamp (QUANT.md 5). */
static void add(const nn_layer *L, const int8_t *a, const int8_t *b, int8_t *y) {
    const int n = L->cout * L->hout * L->wout;
    const int lo = L->relu ? 0 : -128;
    for (int i = 0; i < n; i++)
        y[i] = clamp8(requant(a[i], L->m0[0], L->shift[0]) +
                      requant(b[i], L->m0[1], L->shift[1]), lo);
}

/* Global average pool; the division is inside the multiplier (QUANT.md 6). */
static void gap(const nn_layer *L, const int8_t *x, int8_t *y) {
    const int hw = L->hin * L->win;
    for (int c = 0; c < L->cin; c++) {
        int32_t sum = 0;
        for (int i = 0; i < hw; i++) sum += x[c * hw + i];
        y[c] = clamp8(requant(sum, L->m0[0], L->shift[0]), 0);
    }
}

/* Fully connected layer; the logits stay int32 (QUANT.md 6). */
static void fc(const nn_layer *L, const int8_t *x, int32_t *logits) {
    for (int j = 0; j < L->cout; j++) {
        int32_t acc = L->bias[j];
        const int8_t *w = L->weight + j * L->cin;
        for (int i = 0; i < L->cin; i++) acc += (int32_t)x[i] * (int32_t)w[i];
        logits[j] = acc;
    }
}

void nn_run(const nn_model *m, int8_t *arena, const int8_t *input, int32_t *logits) {
    for (int i = 0; i < m->num_layers; i++) {
        const nn_layer *L = &m->layers[i];
        const int8_t *x = src(arena, input, L->in0);
        switch (L->op) {
            case NN_CONV: conv(L, x, arena + L->out); break;
            case NN_ADD: add(L, x, src(arena, input, L->in1), arena + L->out); break;
            case NN_GAP: gap(L, x, arena + L->out); break;
            case NN_FC: fc(L, x, logits); break;
        }
    }
}
