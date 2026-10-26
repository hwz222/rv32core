# t07_jal_jalr -- jumps and calls.
#
# JAL writes pc+4 to rd and jumps (decided in ID). JALR jumps to
# (rs1 + imm) with bit 0 cleared (decided in EX); its rs1 is used in EX,
# so it is forwarded like an ALU operand.
# Covers: call/return, JALR base from an ALU result right before (EX/MEM
# forward, no stall) and from a load right before (1 load-use stall),
# JALR with rd == rs1 (the target must use the old rs1), bit 0 of the
# target cleared, a nested call that saves ra on the stack, and the
# instruction after each jump thrown away.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    li    s0, 0

    # ---- plain call and return
    jal   ra, f_add             # ra = pc + 4
    addi  s1, a0, 0             # a0 from the function

    # ---- JALR base from an ALU result right before
    la    t0, f_add             # last instruction of la is addi t0, ...
    jalr  ra, 0(t0)

    # ---- JALR base from a load right before
    la    a1, fptr
    lw    t1, 0(a1)
    jalr  ra, 0(t1)

    # ---- rd == rs1: jump to the old t2, then t2 = return address
    la    t2, f_ret_t2
    jalr  t2, 0(t2)

    # ---- bit 0 of the target is cleared: (f_add + 1) & ~1 = f_add
    la    t3, f_add
    jalr  ra, 1(t3)

    # ---- nested call
    jal   ra, f_outer

    # ---- a jump forward; the next instruction must not run
    j     1f
    addi  s0, s0, 100
1:
    mv    ra, s10
    ret

# ---------------------------------------------------------------------------
f_add:                          # a0 = 11
    addi  a0, x0, 11
    ret
    addi  s0, s0, 100           # after ret: must not run

f_ret_t2:                       # called with jalr t2: return through t2
    addi  a0, x0, 22
    jr    t2

f_outer:                        # calls f_add, so it saves ra
    addi  sp, sp, -16
    sw    ra, 12(sp)
    jal   ra, f_add
    addi  a0, a0, 1             # 12
    lw    ra, 12(sp)
    addi  sp, sp, 16
    ret                         # ra loaded just before: waits for the load

    .data
fptr:
    .word f_add
