# t02_fwd_dist -- forwarding into EX at every distance.
#
#   distance 1: the value comes from EX/MEM
#   distance 2: the value comes from MEM/WB
#   distance 3: the value comes from MEM/WB into ID (the register file
#               reads the old value while WB writes it)
# Each case is tried on rs1, on rs2 and on both. The "newest value wins"
# cases check that EX/MEM has priority over MEM/WB when two older
# instructions write the same register.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra

    # ---- distance 1 (EX/MEM)
    addi  t0, x0, 5
    addi  t1, t0, 1             # rs1 <- EX/MEM            t1 = 6
    addi  t0, x0, 7
    sub   t1, x0, t0            # rs2 <- EX/MEM            t1 = -7
    addi  t0, x0, 3
    add   t1, t0, t0            # rs1 and rs2 <- EX/MEM    t1 = 6

    # ---- distance 2 (MEM/WB)
    addi  t0, x0, 9
    nop
    addi  t1, t0, 1             # rs1 <- MEM/WB            t1 = 10
    addi  t0, x0, 4
    nop
    sub   t1, x0, t0            # rs2 <- MEM/WB            t1 = -4

    # ---- distance 3 (MEM/WB -> ID, then kept in ID/EX)
    addi  t0, x0, 11
    nop
    nop
    addi  t1, t0, 1             #                          t1 = 12
    addi  t0, x0, 8
    nop
    nop
    sub   t1, x0, t0            #                          t1 = -8

    # ---- two producers at once
    addi  t0, x0, 20
    addi  t2, x0, 30
    add   t1, t2, t0            # t2 <- EX/MEM, t0 <- MEM/WB   t1 = 50
    add   t3, t0, t2            # t0 at distance 3, t2 at distance 2   t3 = 50

    # ---- newest value wins (same register written twice)
    addi  t0, x0, 1
    addi  t0, x0, 2
    add   t1, t0, x0            # must see 2 (EX/MEM), not 1 (MEM/WB)
    addi  t0, x0, 3
    nop
    addi  t0, x0, 4
    add   t1, t0, x0            # must see 4

    # ---- a chain: every instruction uses the one just before
    addi  t0, x0, 1
    addi  t0, t0, 2
    addi  t0, t0, 3
    addi  t0, t0, 4             # t0 = 10
    slli  t0, t0, 2             # 40
    sub   t0, t0, t0            # 0

    # ---- lui/auipc as producers
    lui   t0, 0x10
    addi  t1, t0, 1             # 0x10001
    auipc t2, 0
    sub   t3, t2, t2            # 0

    mv    ra, s10
    ret
