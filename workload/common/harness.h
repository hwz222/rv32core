/* Entry points shared by every workload.
 *
 * A workload is one C file that is built twice: natively (-DHOST_BUILD),
 * where its output on stdout is the golden reference, and for RV32IMA bare
 * metal, where the ISA simulator or the RTL testbench reads the same result
 * straight out of data memory. To make the two directly comparable a
 * workload defines, before including this file:
 *
 *     int32_t *result_ptr;          address of the result words
 *     unsigned int result_nwords;   how many 32-bit words to compare
 *     static void workload_run(void);
 *
 * result_ptr and result_nwords are found by symbol name in the ELF, so they
 * must stay global and keep these names.
 *
 * Result format, on both sides: one 32-bit word per line as %08x.
 */
#ifndef HARNESS_H
#define HARNESS_H

#if defined(HOST_BUILD)

#include <stdint.h>
#include <stdio.h>

/* Native build: run once, then print the result for the comparison. */
int main(void) {
    workload_run();
    const int32_t *word = result_ptr;
    const int32_t *end = result_ptr + result_nwords;
    while (word < end)
        printf("%08x\n", (uint32_t)*word++);
    return ferror(stdout) ? 1 : 0;
}

#else

/* crt0.S calls this after it has set up gp and sp and cleared .bss. It must
 * return: crt0 then writes 1 to tohost, which stops the simulation. */
void workload_main(void) {
    workload_run();
}

#endif

#endif /* HARNESS_H */
