# t13_mix -- all steps: a small kernel with mixed hazards in a loop.
#
# Dot product of two 8-word vectors, plus a count of the odd products.
# Every iteration has: two loads, a load-use into mul, mul result into add,
# an address update, an inner branch on a fresh ALU result, and the loop
# branch right after its counter update. The result is stored and read back.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    la    a0, va
    la    a1, vb
    li    t0, 8                 # loop count
    li    t1, 0                 # sum
    li    s2, 0                 # number of odd products

loop:
    lw    t2, 0(a0)
    lw    t3, 0(a1)
    mul   t4, t2, t3            # t3 loaded just before: load-use stall
    add   t1, t1, t4
    andi  t5, t4, 1
    beq   t5, x0, even          # branch on a result from the line above
    addi  s2, s2, 1
even:
    addi  a0, a0, 4
    addi  a1, a1, 4
    addi  t0, t0, -1
    bne   t0, x0, loop

    la    a2, out
    sw    t1, 0(a2)
    sw    s2, 4(a2)
    lw    t6, 0(a2)             # sum, read back
    lw    s3, 4(a2)

    mv    ra, s10
    ret

    .data
va: .word 1, 2, 3, 4, -5, 6, 7, 8
vb: .word 3, 3, 5, -1, 2, 9, 1, 0
out:
    .word 0, 0
