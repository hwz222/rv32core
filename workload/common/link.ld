/* ============================================================================
 * workload/common/link.ld
 * Linker script shared by all workloads (gemm, mobilenet, resnet18).
 *
 * What this file does:
 *   It turns our memory map into rules for the linker. The linker uses these
 *   rules to give every section and symbol its final address, and writes the
 *   addresses into the ELF. The ISA simulator and the RTL testbench read the
 *   addresses from the ELF, so no address is hardcoded anywhere else.
 *
 * You decide only two things. The linker computes every exact address.
 *   1. MEMORY   : which memory blocks exist, and the start and size of each.
 *                 These must match the SRAMs in your RTL.
 *   2. SECTIONS : which block each kind of section goes into, and in what order.
 *
 *   final address of a symbol = ORIGIN of its memory block
 *                             + total size of the sections placed before it
 *                             + offset of the symbol inside its own section
 *                               (first column of "objdump -t")
 *
 * Syntax notes:
 *   - Comments: only C block comments (slash-star ... star-slash).
 *     "//" and "#" do not work.
 *   - Section rule:  name : { rules } > BLOCK     no ";" at the end
 *   - Assignment:    symbol = expression;         needs ";" at the end
 *   - Sizes can be written as 64K or 1M. Addresses use hex (0x...).
 *
 * Check the result after EVERY change. If a section goes to the wrong place,
 * the linker usually does NOT report an error.
 *   riscv64-unknown-elf-nm -n gemm.elf          final address of each symbol
 *   riscv64-unknown-elf-readelf -S gemm.elf     address (Addr) of each section
 *   riscv64-unknown-elf-readelf -h gemm.elf     entry point
 * ========================================================================== */

OUTPUT_ARCH(riscv)      /* target architecture */
ENTRY(_start)           /* entry point in the ELF header = address of _start */


/* ----------------------------------------------------------------------------
 * MEMORY: describes the hardware.
 *   Format:      NAME (attributes) : ORIGIN = start address, LENGTH = size
 *   Attributes:  r = read, w = write, x = execute
 *   The start and size here must be the same as the IMEM / DMEM in your RTL.
 *   This is the contract between software and hardware.
 *
 *   Address space:
 *     0x0000_0000 - 0x0000_FFFF   IMEM   64 KB
 *     0x0001_0000 - 0x0FFF_FFFF   (nothing)
 *     0x1000_0000 - 0x101F_FFFF   DMEM   2 MB
 *     0x1020_0000 - 0xFFFF_FFFF   (nothing)
 *   The RTL can tell IMEM from DMEM by addr[31:28] alone (0x0 or 0x1).
 * ------------------------------------------------------------------------- */
MEMORY {
  /* IMEM: instruction scratchpad. Only instruction fetch uses it.
   *   ORIGIN 0x0:  the core starts fetching here after reset.
   *   LENGTH 64K:  gemm's .text is only about 0x88 bytes. We link with
   *                -nostdlib (no libc), so even MobileNet / ResNet18 code
   *                should need only a few KB. 64K = 16K instructions. */
  IMEM (rx) : ORIGIN = 0x00000000, LENGTH = 64K

  /* DMEM: data SRAM. Every load and store goes here.
   *   ORIGIN 0x10000000:  does not overlap IMEM, and "0x1..." is easy to
   *                see as a data address in a waveform or a log.
   *   LENGTH 2M:   sized for the INT8 models. Weights, biases, requantize
   *                parameters and activation buffers take about 753 KB for
   *                ResNet18 (width 16) and 930 KB for MobileNetV1 (alpha
   *                0.5), under half of 2 MB (1 MB would be 91% full for
   *                MobileNet). GEMM needs (M*K + K*N + M*N) * 4 bytes plus
   *                the stack, so 2M holds up to 417x417x417. A bigger SRAM
   *                costs almost nothing in Verilator. */
  DMEM (rw) : ORIGIN = 0x10000000, LENGTH = 2M
}


/* ----------------------------------------------------------------------------
 * SECTIONS: describes how the software is placed into the hardware.
 *   output_section : { *(input sections to collect) } > BLOCK
 *     *        = all input files (now gemm.o; later also crt0.o)
 *     .text.*  = wildcard; also collects names like .text.startup that the
 *                compiler sometimes creates
 *
 * Key point 1 -- Which block? Ask "who accesses it?"
 *   read by instruction fetch        -> IMEM
 *   read or written by load / store  -> DMEM  (this includes read-only .rodata)
 *
 * Key point 2 -- Order matters. Inside one block, what you write first is
 *   placed first (at the lower address).
 *
 * Key point 3 -- Which names to collect? Run "readelf -S gemm.o" and find
 *   every section whose Flg column has "A". A section that no rule collects
 *   is an "orphan section": the linker picks a place for it by itself, and
 *   does not report an error.
 * ------------------------------------------------------------------------- */
SECTIONS {

  /* Code -> IMEM.
   * .text.init holds crt0's _start and is listed first, so _start gets the
   * lowest address in IMEM, which is the address the core fetches from after
   * reset. KEEP() stops the linker from removing the section: nothing calls
   * _start, so without KEEP the linker may treat it as unused code. */
  .text : {
    KEEP(*(.text.init))
    *(.text .text.*)
  } > IMEM

  /* Read-only data -> DMEM.
   * A const array is read with load instructions. Loads use the data port and
   * cannot reach IMEM, so read-only data must also live in DMEM. */
  .rodata : {
    *(.rodata .rodata.* .srodata .srodata.*)
  } > DMEM

  /* Global variables with an initial value -> DMEM.
   * .sdata holds small variables (8 bytes or less). The compiler groups them
   * so that one register, gp, can reach them all with a single instruction:
   * an offset from gp is only 12 bits signed, so gp can reach 2 KB below and
   * 2 KB above itself. We therefore point gp at ".sdata start + 2 KB".
   * crt0 loads this value into gp at run time (step 1-3). */
  .data : {
    *(.data .data.*)
    __global_pointer$ = . + 0x800;
    *(.sdata .sdata.*)
  } > DMEM

  /* The stop flag. The program writes to tohost when it is finished, and the
   * ISA simulator and the RTL testbench watch that address. This is the same
   * convention riscv-tests uses, so the same stop logic also works for the
   * riscv-tests compliance suite. KEEP() is needed because nothing in C
   * refers to this section. */
  .tohost : ALIGN(64) {
    KEEP(*(.tohost))
  } > DMEM

  /* Global variables with no initial value -> DMEM.
   * NOBITS: the ELF file stores only the size, not the content, so this
   * memory must be set to 0 before the program runs. crt0 does that, and it
   * needs to know where the area starts and ends: the two symbols below give
   * it those addresses. crt0 clears 4 bytes at a time, so both ends are
   * aligned to 4.
   * COMMON: old-style C code may put uninitialized globals here, so we
   * collect them too. */
  .bss : ALIGN(4) {
    __bss_start = .;
    *(.sbss .sbss.* .bss .bss.* COMMON)
    . = ALIGN(4);
    __bss_end = .;
  } > DMEM

  /* Top of the stack. The stack is not a section: the linker does not know
   * it exists, so we only define an address here and crt0 loads it into sp.
   * ORIGIN() and LENGTH() read the MEMORY block above, so changing the DMEM
   * size in one place is enough.
   *   _stack_top = 0x10000000 + 2M = 0x10200000
   * This is one address past the end of DMEM. That is correct because the
   * stack always subtracts from sp before it stores, so the first value
   * written still lands inside DMEM.
   * The check below makes sure the data never reaches the stack. */
  _stack_top = ORIGIN(DMEM) + LENGTH(DMEM);

  /* How much room we keep free for the stack. GEMM uses only a few dozen
   * bytes, but a workload with deeper calls needs more. */
  __stack_size = 4K;

  /* The linker checks that every section fits in its memory block, but the
   * stack is not a section, so that check does not cover it. Without the
   * line below, data that nearly fills DMEM would link with no error and
   * then be overwritten by the stack at run time, which is very hard to
   * debug. ASSERT() stops the build instead.
   *
   * Rule of thumb for workloads: put large buffers in global variables, not
   * in local arrays. Globals land in .bss and are covered by this check;
   * local arrays live on the stack and are not. */
  ASSERT(__bss_end + __stack_size <= _stack_top,
         "DMEM too small: data plus the reserved stack does not fit -- use a smaller problem size, or raise LENGTH of DMEM above")
}
