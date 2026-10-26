# t05_load_use -- a load result used soon after the load.
#
#   distance 1: the user must wait one cycle (load-use stall), then take
#               the value from MEM/WB
#   distance 2: no stall, value from MEM/WB
# Also: a loaded value used as a store's data, a loaded value used as the
# next load's address, an address computed just before a load, and a load
# right after a store to the same word.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    la    a0, arr

    # ---- distance 1: stall
    lw    t0, 0(a0)             # 3
    add   t1, t0, t0            # rs1 and rs2: 6
    lw    t0, 4(a0)             # 5
    addi  t1, t0, 1             # rs1: 6
    lw    t3, 8(a0)             # -2
    sub   t4, x0, t3            # rs2: 2

    # ---- distance 2: no stall
    lw    t0, 0(a0)
    nop
    add   t1, t0, x0            # 3

    # ---- two loads, then a user of both
    lw    t0, 0(a0)             # 3
    lw    t1, 4(a0)             # 5
    add   t2, t0, t1            # t1 at distance 1 (stall), t0 at distance 2: 8

    # ---- loaded value as store data, then read back
    lw    t0, 4(a0)             # 5
    sw    t0, 16(a0)            # store data from the load just before
    lw    t1, 16(a0)            # load right after a store to the same word
    add   t2, t1, t1            # 10

    # ---- loaded value as the next load's address (pointer chasing)
    lw    t0, 20(a0)            # t0 = address of arr
    lw    t1, 8(t0)             # address from the load just before: -2

    # ---- address computed just before the load
    addi  a1, a0, 12
    lw    t2, 0(a1)             # address from EX/MEM: 7

    mv    ra, s10
    ret

    .data
arr:
    .word 3
    .word 5
    .word -2
    .word 7
    .word 0                     # 16(arr): written by the test
    .word arr                   # 20(arr): pointer to arr
