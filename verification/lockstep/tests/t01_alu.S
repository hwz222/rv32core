# t01_alu -- every ALU instruction with NO hazard.
#
# Three nops sit between a result and its first use, so the pipeline needs
# no forwarding and no stall. If this test fails, the basic datapath
# (decode, register file, ALU, write back) is wrong, not hazard handling.
#
# Covers: lui auipc addi slti sltiu xori ori andi slli srli srai
#         add sub sll slt sltu xor srl sra or and
# Also: shift amounts use only the low 5 bits of rs2 (t2 = 33 -> shift 1).

    .text
    .globl workload_main
workload_main:
    mv    s10, ra               # keep the return address for crt0

    li    t0, 0x12345678        # lui + addi
    li    t1, -16               # 0xfffffff0
    li    t2, 33                # only bits 4:0 (= 1) are used by shifts
    nop
    nop
    nop

    # ---- register-immediate
    addi  a0, t0, -1
    slti  a1, t1, 0             # -16 < 0 -> 1
    sltiu a2, t1, 5             # 0xfffffff0 < 5 unsigned -> 0
    xori  a3, t0, -1            # bitwise not
    ori   a4, t0, 0x0f0
    andi  a5, t1, 0x0ff
    slli  a6, t0, 4
    srli  a7, t1, 4             # zero fill
    srai  s2, t1, 4             # sign fill

    # ---- register-register
    add   s3, t0, t1
    sub   s4, t0, t1
    sll   s5, t0, t2            # shift by 1, not 33
    slt   s6, t1, t0            # -16 < 0x12345678 -> 1
    sltu  s7, t1, t0            # unsigned: 0xfffffff0 > t0 -> 0
    xor   s8, t0, t1
    srl   s9, t1, t2
    sra   s11, t1, t2
    or    t3, t0, t1
    and   t4, t0, t1

    # ---- upper immediates
    lui   t5, 0xabcde
    auipc t6, 0x1               # pc + 0x1000

    mv    ra, s10
    ret
