# t08_muldiv -- the M extension.
#
# Results that are easy to get wrong:
#   mulh/mulhsu/mulhu: the high 32 bits, with signed/unsigned operands
#     (mulhsu: rs1 signed, rs2 unsigned)
#   division by zero: div -> -1, divu -> 0xffffffff, rem/remu -> dividend
#   overflow: div(-2^31, -1) -> -2^31, rem(-2^31, -1) -> 0
#   rounding toward zero with negative operands
# None of these trap. Also: a result used right after (a multi-cycle unit
# must stall), and back-to-back dependent multiplies and divides.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    li    t0, 0x80000000        # -2^31
    li    t1, -1
    li    t2, 7
    li    t3, -3
    li    t4, 0x7fffffff
    nop
    nop
    nop

    # ---- multiply
    mul    a0, t2, t3           # -21
    mul    a1, t0, t1           # 0x80000000 (low bits)
    mulh   a2, t0, t1           # high of (-2^31 * -1) = 0
    mulh   a3, t3, t2           # high of -21 = 0xffffffff
    mulhu  a4, t1, t1           # high of 0xffffffff^2 = 0xfffffffe
    mulhsu a5, t1, t1           # (-1) * 0xffffffff, high = 0xffffffff
    mulhsu a6, t2, t1           # 7 * 0xffffffff, high = 6
    mulh   a7, t4, t4           # high of (2^31-1)^2 = 0x3fffffff

    # ---- divide and remainder
    div    s2, t2, t3           # 7 / -3 = -2 (toward zero)
    rem    s3, t2, t3           # 7 % -3 = 1
    div    s4, t3, t2           # -3 / 7 = 0
    rem    s5, t3, t2           # -3
    divu   s6, t1, t2           # 0xffffffff / 7 = 0x24924924
    remu   s7, t1, t2           # 3

    # ---- division by zero (no trap)
    div    s8, t2, x0           # -1
    divu   s9, t2, x0           # 0xffffffff
    rem    s11, t2, x0          # 7
    remu   t5, t3, x0           # 0xfffffffd

    # ---- overflow (no trap)
    div    t6, t0, t1           # -2^31
    rem    a0, t0, t1           # 0

    # ---- result used right after
    mul    a1, t2, t2           # 49
    addi   a2, a1, 1            # 50
    div    a3, a1, t2           # 7
    add    a4, a3, a3           # 14

    # ---- dependent chain
    mul    a5, t2, t2           # 49
    mul    a5, a5, t2           # 343
    div    a5, a5, t2           # 49
    rem    a5, a5, t3           # 49 % -3 = 1
    mul    a5, a5, a5           # 1

    mv    ra, s10
    ret
