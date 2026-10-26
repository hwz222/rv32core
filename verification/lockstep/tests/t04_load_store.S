# t04_load_store -- every load and store width and byte offset.
#
# Loads: lb lbu at offsets 0..3, lh lhu at 0 and 2, lw. The data has bit 7
# set in some bytes, so sign and zero extension give different results.
# Stores: sw, then sb and sh into parts of words; a lw after each one
# shows the merged word in x<rd>. Three nops keep this test free of
# load-use hazards (those are in t05).

    .text
    .globl workload_main
workload_main:
    mv    s10, ra
    la    a0, src
    la    a1, dst
    nop
    nop
    nop

    # ---- loads of every width (src = 0x80ff7f01: bytes 01 7f ff 80)
    lb    t0, 0(a0)             # 0x00000001
    lb    t1, 1(a0)             # 0x0000007f
    lb    t2, 2(a0)             # 0xffffffff
    lb    t3, 3(a0)             # 0xffffff80
    lbu   t4, 2(a0)             # 0x000000ff
    lbu   t5, 3(a0)             # 0x00000080
    lh    t6, 0(a0)             # 0x00007f01
    lh    s2, 2(a0)             # 0xffff80ff
    lhu   s3, 2(a0)             # 0x000080ff
    lw    s4, 0(a0)             # 0x80ff7f01
    lw    s5, 4(a0)             # second word
    lb    s6, -1(a1)            # negative offset: last byte of pad

    # ---- stores, each followed by a read of the whole word
    li    t0, 0x11223344
    li    t1, 0xaa
    li    t2, 0xbeef
    nop
    nop
    nop
    sw    t0, 0(a1)
    nop
    nop
    nop
    lw    s7, 0(a1)             # 0x11223344
    sb    t1, 1(a1)             # byte 1
    nop
    nop
    nop
    lw    s8, 0(a1)             # 0x1122aa44
    sb    t1, 3(a1)             # byte 3
    nop
    nop
    nop
    lw    s9, 0(a1)             # 0xaa22aa44
    sh    t2, 2(a1)             # upper half
    nop
    nop
    nop
    lw    s11, 0(a1)            # 0xbeefaa44
    sh    t2, 0(a1)             # lower half
    nop
    nop
    nop
    lw    t3, 0(a1)             # 0xbeefbeef
    lh    t4, 0(a1)             # 0xffffbeef
    lbu   t5, 1(a1)             # 0x000000be

    mv    ra, s10
    ret

    .data
src:
    .word 0x80ff7f01
    .word 0xcafef00d
pad:
    .word 0x7e000000            # its last byte (0x7e) is read with -1(dst)
dst:
    .word 0
    .word 0
