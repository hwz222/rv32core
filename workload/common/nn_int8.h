/* A small INT8 network runner for the RV32IMA core (no FPU, no libc).
 *
 * A model is a table of layers made by tools/gen_model_c.py from a quantized
 * model (model.json + weights.bin written by tools/qref.py). The same C code
 * runs on the host (the golden) and on the core. Rules: QUANT.md. */
#ifndef NN_INT8_H
#define NN_INT8_H

#include <stdint.h>

enum { NN_CONV = 0, NN_ADD = 1, NN_GAP = 2, NN_FC = 3 };

/* Where a layer reads or writes: a byte offset in the activation arena, or
 * NN_INPUT for the input image. */
#define NN_INPUT (-2)

typedef struct {
    int32_t op;                  /* NN_CONV, NN_ADD, NN_GAP or NN_FC */
    int32_t relu;                /* 1: clamp the output to [0, 127] */
    int32_t cin, hin, win;       /* input shape (NN_FC: cin = in_features) */
    int32_t cout, hout, wout;    /* output shape (NN_FC: cout = out_features) */
    int32_t kernel, stride, padding, groups;   /* NN_CONV only */
    int32_t in0, in1, out;       /* arena offsets; in1 is used by NN_ADD only */
    const int8_t *weight;        /* NN_CONV: [cout][cin/groups][k][k]; NN_FC: [out][in] */
    const int32_t *bias;         /* NN_CONV, NN_FC: one per output channel */
    const int32_t *m0;           /* requantize multipliers (see QUANT.md) */
    const int32_t *shift;
} nn_layer;

typedef struct {
    const nn_layer *layers;
    int32_t num_layers;
    int32_t num_classes;
} nn_model;

/* Runs one image. input: C*H*W int8 values in [C][H][W] order.
 * arena: at least the arena size of the model. logits: num_classes int32. */
void nn_run(const nn_model *m, int8_t *arena, const int8_t *input, int32_t *logits);

#endif
