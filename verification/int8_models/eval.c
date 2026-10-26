/* Runs a quantized model on many test images and outputs every logit, so
 * that the host build, the RISC-V build and tools/qref.py can be compared
 * bit for bit. Output: 10 int32 logits per image, one %08x word per line.
 *
 *   host:   eval_host <images.bin> <start> <count>   (prints to stdout)
 *   RISC-V: the images are compiled in (images_*.c); the logits are left in
 *           eval_logits and found through result_ptr / result_nwords. */
#include "model_data.h"

#ifdef HOST_BUILD

#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s <images.bin> <start> <count>\n", argv[0]);
        return 2;
    }
    const long start = atol(argv[2]), count = atol(argv[3]);
    FILE *f = fopen(argv[1], "rb");
    if (!f || fseek(f, start * NN_INPUT_BYTES, SEEK_SET) != 0) {
        fprintf(stderr, "cannot read %s\n", argv[1]);
        return 1;
    }
    int8_t image[NN_INPUT_BYTES];
    int32_t logits[NN_NUM_CLASSES];
    for (long i = 0; i < count; i++) {
        if (fread(image, 1, NN_INPUT_BYTES, f) != NN_INPUT_BYTES) {
            fprintf(stderr, "short read at image %ld\n", start + i);
            return 1;
        }
        nn_run(&NN_MODEL, nn_arena, image, logits);
        for (int j = 0; j < NN_NUM_CLASSES; j++) printf("%08x\n", (unsigned)logits[j]);
    }
    return 0;
}

#else

#include "images_data.h"

int32_t eval_logits[EVAL_COUNT][NN_NUM_CLASSES];
int32_t *result_ptr = &eval_logits[0][0];
unsigned int result_nwords = EVAL_COUNT * NN_NUM_CLASSES;

void workload_main(void) {
    for (int i = 0; i < EVAL_COUNT; i++)
        nn_run(&NN_MODEL, nn_arena, eval_images[i], eval_logits[i]);
}

#endif
