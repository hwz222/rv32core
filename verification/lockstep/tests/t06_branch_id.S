# t06_branch_id -- branches decided in ID.
#
# A branch compares its operands in ID, so an operand that is not ready
# makes it wait (stallBranchUse):
#   ALU result,      branch right after        -> 1 stall cycle
#   ALU result,      1 instruction between     -> 0 (EX/MEM -> ID)
#   load/CSR result, branch right after        -> 2 stall cycles
#   load/CSR result, 1 instruction between     -> 1 stall cycle
#   load/CSR result, 2 instructions between    -> 0 (MEM/WB -> ID)
# Each case is tried taken and not taken. In the taken cases the next
# instruction (addi s0, ...) is fetched and must be thrown away: if it
# runs, the trace gets an extra line.
# Also: all six branch types with signed / unsigned operands, and a
# backward loop.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    la    a0, vals              # vals = {0, 1}
    li    s0, 0
    nop
    nop
    nop

    # ---- ALU result, branch right after (1 stall)
    addi  t0, x0, 1
    beq   t0, x0, 1f            # not taken
    addi  s0, s0, 1             # runs
1:  addi  t0, x0, 2
    bne   t0, x0, 2f            # taken
    addi  s0, s0, 100           # thrown away
2:
    # ---- ALU result, one instruction between (no stall)
    addi  t0, x0, 3
    nop
    bne   t0, x0, 3f            # taken
    addi  s0, s0, 100
3:  addi  t0, x0, 0
    nop
    bne   t0, x0, 4f            # not taken
    addi  s0, s0, 1
4:
    # ---- load result, branch right after (2 stalls)
    lw    t1, 4(a0)             # 1
    bne   t1, x0, 5f            # taken
    addi  s0, s0, 100
5:  lw    t1, 0(a0)             # 0
    bne   t1, x0, 6f            # not taken
    addi  s0, s0, 1
6:
    # ---- load result, one instruction between (1 stall)
    lw    t1, 0(a0)             # 0
    nop
    beq   t1, x0, 7f            # taken
    addi  s0, s0, 100
7:  lw    t1, 4(a0)             # 1
    nop
    beq   t1, x0, 8f            # not taken
    addi  s0, s0, 1
8:
    # ---- load result, two instructions between (no stall)
    lw    t1, 4(a0)
    nop
    nop
    bne   t1, x0, 9f            # taken
    addi  s0, s0, 100
9:
    # ---- both operands just produced
    addi  t2, x0, 5
    addi  t3, x0, 5
    beq   t2, t3, 10f           # t3 distance 1, t2 distance 2: taken
    addi  s0, s0, 100
10:
    # ---- all branch types, -1 against 1
    li    t4, -1
    li    t5, 1
    nop
    nop
    nop
    blt   t4, t5, 11f           # signed -1 < 1: taken
    addi  s0, s0, 100
11: bltu  t4, t5, 12f           # unsigned 0xffffffff < 1: not taken
    addi  s0, s0, 1
12: bge   t5, t4, 13f           # signed 1 >= -1: taken
    addi  s0, s0, 100
13: bgeu  t5, t4, 14f           # unsigned 1 >= 0xffffffff: not taken
    addi  s0, s0, 1
14: bgeu  t4, t5, 15f           # unsigned: taken
    addi  s0, s0, 100
15: beq   t4, t4, 16f           # same register: taken
    addi  s0, s0, 100
16:
    # ---- backward loop, the counter is updated right before the branch
    li    t6, 5
loop:
    addi  t6, t6, -1
    bne   t6, x0, loop          # 1 stall every time

    mv    ra, s10
    ret

    .data
vals:
    .word 0
    .word 1
