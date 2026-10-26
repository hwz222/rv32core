/* ============================================================================
 * workload/common/crt0.S
 * Startup code shared by all workloads (gemm, mobilenet, resnet18, and the
 * operator benchmarks).
 *
 * link.ld places section .text.init at the start of IMEM, so _start is the
 * very first instruction the core fetches after reset.
 *
 * What this file does, in order:
 *   1. set up sp   (the stack pointer)
 *   2. call workload_main(), which every workload provides
 *   3. stop
 *
 * C code cannot do steps 1 and 3, and cannot control its own address, so this
 * part must be written in assembly.
 * ========================================================================== */
    .section .text.init, "ax"       /* "a" = keep in memory, "x" = executable */
    .globl _start
_start:
    /* Set up gp (the global pointer). The compiler assumes gp already holds
     * __global_pointer$ and uses "offset(gp)" to reach small variables.
     *
     * ".option norelax" is required here. Without it the linker rewrites
     * THIS instruction into a gp-relative form as well, which becomes
     * "mv gp, gp" and leaves gp at 0. Every gp-relative access would then
     * read or write the wrong address. Only this one instruction needs the
     * option; after gp holds the right value, gp-relative code is correct
     * and saves one instruction per access. */
    .option push
    .option norelax
    la   gp, __global_pointer$
    .option pop

    /* The stack grows down from the top of DMEM. _stack_top comes from
     * link.ld, so the value follows the memory map automatically. */
    la   sp, _stack_top

    /* Clear .bss, 4 bytes at a time. Global variables with no initial value
     * live here. The ELF file does not store their contents, and SRAM does
     * not come out of reset as zero, so the program must do this itself.
     * __bss_start and __bss_end come from link.ld. */
    la   t0, __bss_start
    la   t1, __bss_end
1:  bgeu t0, t1, 2f             /* stop when t0 has reached __bss_end */
    sw   zero, 0(t0)
    addi t0, t0, 4
    j    1b

    /* Run the workload. "call" saves the return address in ra, so the
     * workload returns here when it is done. */
2:  call workload_main

    /* Tell the simulator that the program finished. riscv-tests writes
     * (exit_code << 1) | 1 to tohost; exit code 0 therefore means the
     * value 1. The simulator and the testbench stop when they see a store
     * of a non-zero value to this address. */
    li   t0, 1
    la   t1, tohost
    sw   t0, 0(t1)

    /* Stop. Without this loop the core would keep fetching past the end of
     * the program and execute whatever comes next as if it were code. */
3:  j    3b

    /* The stop flag itself. It needs its own section so that link.ld can
     * place it at a known, aligned address. fromhost is unused here, but
     * riscv-tests expects the pair, so we define both. */
    .section .tohost, "aw", @progbits
    .align 6                    /* 2^6 = 64-byte aligned */
    .globl tohost
tohost:   .word 0
    .globl fromhost
fromhost: .word 0
