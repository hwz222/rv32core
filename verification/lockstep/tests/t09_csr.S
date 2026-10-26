# t09_csr -- CSR instructions.
#
# CSR instructions read and write in MEM. Their rd value is ready as late as
# load data, so a user right after waits one cycle (the load-use stall).
# CSR instructions do not flush the pipeline.
# Covers: csrrw csrrs csrrc csrrwi csrrsi csrrci; csrrs/csrrc with rs1 = x0
# (read only, no write); rd == rs1; a CSR written and then read right after;
# read-only CSRs (misa, mhartid, mvendorid); minstret and instret (must
# match isa_sim exactly); mcycle (the trace compare skips its value).

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    li    t0, 0x1234
    li    t1, 0x00f0
    nop
    nop
    nop

    # ---- the six instructions on mscratch
    csrrw  a0, mscratch, t0     # a0 = old (0), mscratch = 0x1234
    csrrs  a1, mscratch, t1     # a1 = 0x1234, mscratch = 0x12f4
    csrrc  a2, mscratch, t1     # a2 = 0x12f4, mscratch = 0x1204
    csrrwi a3, mscratch, 5      # a3 = 0x1204, mscratch = 5
    csrrsi a4, mscratch, 0x18   # a4 = 5,      mscratch = 0x1d
    csrrci a5, mscratch, 0x1    # a5 = 0x1d,   mscratch = 0x1c
    csrrs  a6, mscratch, x0     # read only:   0x1c
    csrrc  a7, mscratch, x0     # read only:   0x1c

    # ---- CSR result used right after (load-use style stall)
    csrr   t2, mscratch
    add    t3, t2, t2           # 0x38

    # ---- write then read right after (both in MEM, in order)
    csrw   mscratch, t0
    csrr   t4, mscratch         # 0x1234

    # ---- rd == rs1: swap
    li     t5, 0x55
    csrrw  t5, mscratch, t5     # t5 = 0x1234, mscratch = 0x55
    csrr   t6, mscratch         # 0x55

    # ---- value produced just before the CSR instruction
    addi   s2, x0, 0x77
    csrw   mscratch, s2         # rs1 from EX/MEM
    csrr   s3, mscratch         # 0x77

    # ---- read-only information CSRs
    csrr   s4, misa             # 0x40001101
    csrr   s5, mhartid          # 0
    csrr   s6, mvendorid        # 0

    # ---- counters
    csrr   s7, minstret         # must equal isa_sim
    csrr   s8, minstret         # previous + 1
    csrr   s9, instret          # user-level copy
    csrr   s11, mcycle          # value not compared (isa_sim: 1 cycle per insn)

    mv    ra, s10
    ret
