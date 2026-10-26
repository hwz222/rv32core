# t11_amo -- the A extension.
#
# LR/SC: one reservation for one word. SC succeeds (rd = 0, memory
# written) only after an LR to the same word with no SC in between;
# otherwise it fails (rd = 1, no write). Every SC uses up the reservation.
# AMO: read the old word into rd, write op(old, rs2) back; the core does
# this as a read and then a write (two DMEM accesses).
# Covers: SC success; SC again without LR; SC with no LR at all; SC to a
# different word than the LR; all nine AMOs (signed and unsigned min/max
# with a negative value); an AMO result used right after; a value forwarded
# from WB to EX while the AMO holds the pipeline.
# Not covered on purpose: an ordinary store between LR and SC. Whether that
# breaks the reservation is a design choice and must match isa_sim.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    la    a0, w0
    la    a1, w1
    li    t5, 5
    li    t6, -8
    nop
    nop
    nop

    # ---- LR/SC
    lr.w  t0, (a0)              # t0 = 10, reserve w0
    addi  t0, t0, 1
    sc.w  t1, t0, (a0)          # success: t1 = 0, w0 = 11
    sc.w  t2, t0, (a0)          # no reservation left: t2 = 1, no write
    sc.w  t3, t0, (a1)          # never reserved: t3 = 1
    lr.w  t0, (a0)              # reserve w0
    sc.w  t4, t0, (a1)          # other word: fails, t4 = 1
    lw    s2, 0(a0)             # 11
    lw    s3, 0(a1)             # 3 (unchanged)

    # ---- AMOs on w0 (starts at 11)
    amoswap.w s4, t5, (a0)      # s4 = 11, w0 = 5
    amoadd.w  s5, t5, (a0)      # s5 = 5,  w0 = 10
    amoxor.w  s6, t5, (a0)      # s6 = 10, w0 = 15
    amoand.w  s7, t5, (a0)      # s7 = 15, w0 = 5
    amoor.w   s8, t6, (a0)      # s8 = 5,  w0 = -8 | 5 = -3
    amomin.w  s9, t5, (a0)      # s9 = -3, w0 = min(-3, 5) = -3
    amomax.w  s11, t5, (a0)     # s11 = -3, w0 = 5
    amominu.w a2, t6, (a0)      # a2 = 5,  w0 = minu(5, 0xfffffff8) = 5
    amomaxu.w a3, t6, (a0)      # a3 = 5,  w0 = 0xfffffff8
    lw        a4, 0(a0)         # -8

    # ---- AMO result used right after
    amoadd.w  a5, t5, (a1)      # a5 = 3, w1 = 8
    add       a6, a5, a5        # 6

    # ---- a value forwarded from WB while the AMO holds EX. The add
    # is in ID while the lw is in MEM: a load is never forwarded from MEM,
    # so the add gets t1 in EX, from WB, in the AMO's first MEM cycle. In
    # the second cycle the lw has left WB; the add must still use -8.
    lw        t1, 0(a0)         # -8
    amoswap.w t2, t5, (a1)      # t2 = 8, w1 = 5
    add       t3, t1, t1        # -16
    sub       t4, t3, t2        # -24

    mv    ra, s10
    ret

    .data
    .align 2
w0: .word 10
w1: .word 3
