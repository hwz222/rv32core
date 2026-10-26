# t10_trap -- exceptions and mret.
#
# Each case sets s11 to a resume address, then runs one instruction that
# traps. The handler reads mcause, mepc and mtval (so they show up in the
# trace), counts the trap in s9, sets mepc = s11 and returns with mret.
# The instruction right after the trapping one changes s0; it must be
# thrown away (precise exception), otherwise the trace gets an extra line.
#
# Causes: 0 fetch misaligned (jump to pc+2), 1 fetch access fault (jump
# outside IMEM), 2 illegal (all-zero word, write to a read-only CSR),
# 3 ebreak, 4 load misaligned, 5 load access fault (outside DMEM, and from
# IMEM: Harvard), 6 store misaligned, 7 store access fault, 11 ecall.
# Also: an ecall on the wrong path of a taken branch must NOT trap.

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    li    s0, 0
    li    s9, 0
    la    t0, handler
    csrw  mtvec, t0
    la    a0, data
    li    a1, 0x20000000        # outside IMEM and DMEM

    # ---- 11: ecall
    la    s11, 1f
    ecall
    addi  s0, s0, 100           # must not run
1:
    # ---- 3: ebreak
    la    s11, 2f
    ebreak
    addi  s0, s0, 100
2:
    # ---- 2: illegal (all-zero word), mtval = 0
    la    s11, 3f
    .word 0x00000000
    addi  s0, s0, 100
3:
    # ---- 2: illegal (write to read-only mhartid), mtval = the instruction
    la    s11, 4f
    csrw  mhartid, x0
    addi  s0, s0, 100
4:
    # ---- 4: load misaligned
    la    s11, 5f
    lw    t1, 1(a0)
    addi  s0, s0, 100
5:
    # ---- 6: store misaligned
    la    s11, 6f
    sh    t1, 1(a0)
    addi  s0, s0, 100
6:
    # ---- 5: load access fault (outside both regions)
    la    s11, 7f
    lw    t1, 0(a1)
    addi  s0, s0, 100
7:
    # ---- 5: load from IMEM (only fetch may read IMEM)
    la    s11, 8f
    lw    t1, 0(x0)
    addi  s0, s0, 100
8:
    # ---- 7: store access fault
    la    s11, 9f
    sw    t1, 0(a1)
    addi  s0, s0, 100
9:
    # ---- 0: fetch misaligned: the jump traps, mtval = target
    la    s11, 10f
    la    t2, 10f
    addi  t2, t2, 2
    jalr  x0, 0(t2)
    addi  s0, s0, 100
10:
    # ---- 1: fetch access fault: the fetch at the target traps
    la    s11, 11f
    jalr  x0, 0(a1)
    addi  s0, s0, 100
11:
    # ---- wrong path: the ecall after a taken branch must not trap
    beq   x0, x0, 12f
    ecall
12:
    csrw  mtvec, x0
    mv    ra, s10
    ret

# ---------------------------------------------------------------------------
    .align 2                    # mtvec (direct mode) needs a 4-byte address
handler:
    csrr  t3, mcause
    csrr  t4, mepc
    csrr  t5, mtval
    addi  s9, s9, 1
    csrw  mepc, s11
    mret

    .data
data:
    .word 0x11223344
    .word 0
