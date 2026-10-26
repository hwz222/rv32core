# t12_fence -- fence, fence.i and wfi.
#
# On this core (one hart, in-order, no cache, no interrupts) all three
# act as nops: they retire with no register write and no memory write.
# fence.i does not make code written to DMEM runnable (fetch reads IMEM
# only); rv32ui-p-fence_i is an expected failure for that reason.
# A store before and a load after each one check that memory order holds.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    la    a0, buf
    li    t0, 0x1234
    nop
    nop
    nop

    sw    t0, 0(a0)
    fence
    lw    t1, 0(a0)             # 0x1234

    sw    t1, 4(a0)
    fence rw, rw
    lw    t2, 4(a0)

    fence.i
    addi  t3, t2, 1             # runs normally after fence.i

    wfi                         # no interrupt can come: a nop
    addi  t4, t3, 1

    mv    ra, s10
    ret

    .data
buf:
    .word 0
    .word 0
