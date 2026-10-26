# t03_x0 -- x0 is always 0.
#
# Writes to x0 must not change it, and a forwarding unit must never pass a
# "result for x0" to a later instruction that reads x0. Each write to x0
# below is followed by readers at distance 1, 2 and 3.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    la    a0, data

    addi  x0, x0, 5             # ALU writes x0
    add   t0, x0, x0            # distance 1: must be 0
    add   t1, x0, x0            # distance 2
    add   t2, x0, x0            # distance 3

    lui   x0, 0x12345           # lui writes x0
    addi  t0, x0, 1             # must be 1

    lw    x0, 0(a0)             # load writes x0
    add   t1, x0, x0            # load-use on x0: must be 0
    add   t2, x0, x0

    jal   x0, 1f                # jal writes x0 (a plain jump)
1:  add   t3, x0, x0

    csrr  x0, mscratch          # CSR read into x0
    add   t4, x0, x0

    mul   x0, a0, a0            # multiply writes x0
    add   t5, x0, x0

    mv    ra, s10
    ret

    .data
data:
    .word 0x12345678
