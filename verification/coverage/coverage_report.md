# Coverage report

Inputs: 82 runs. Cores: core (82). Run cycles: 2545133.

| category | covered | applicable | what it shows |
|---|---|---|---|
| opcodes | 66 | 68 | implemented instructions retired |
| traps | 9 | 9 | exceptions and interrupts taken |
| stalls | 4 | 4 | hazard types: cycles the front of the pipeline waited |
| flushes | 3 | 3 | hazard types: cycles younger instructions were thrown away |
| forwarding | 8 | 8 | hazard types: forwarding paths used |
| stage_patterns | 15 | 32 | pipeline states: which stages were busy together |

## opcodes

Not covered: ecall, ebreak.

| bin | count |
|---|---|
| lui | 1638 |
| auipc | 1025 |
| jal | 6156 |
| jalr | 44 |
| beq | 351 |
| bne | 313903 |
| blt | 101 |
| bge | 50 |
| bltu | 41 |
| bgeu | 6058 |
| lb | 112 |
| lh | 111 |
| lw | 607475 |
| lbu | 51 |
| lhu | 49 |
| sb | 111 |
| sh | 111 |
| sw | 12210 |
| addi | 661814 |
| slti | 31 |
| sltiu | 31 |
| xori | 20 |
| ori | 22 |
| andi | 28 |
| slli | 93 |
| srli | 31 |
| srai | 34 |
| add | 304665 |
| sub | 63 |
| sll | 57 |
| slt | 53 |
| sltu | 53 |
| xor | 42 |
| srl | 58 |
| sra | 58 |
| or | 42 |
| and | 42 |
| fence | 61 |
| fence.i | 2 |
| ecall | 0 |
| ebreak | 0 |
| mret | 71 |
| wfi | 1 |
| mul | 303689 |
| mulh | 52 |
| mulhsu | 51 |
| mulhu | 50 |
| div | 15 |
| divu | 11 |
| rem | 14 |
| remu | 11 |
| lr.w | 1027 |
| sc.w | 1032 |
| amoswap.w | 4 |
| amoadd.w | 6 |
| amoxor.w | 3 |
| amoand.w | 3 |
| amoor.w | 3 |
| amomin.w | 4 |
| amomax.w | 4 |
| amominu.w | 3 |
| amomaxu.w | 3 |
| csrrw | 377 |
| csrrs | 229 |
| csrrc | 2 |
| csrrwi | 121 |
| csrrsi | 1 |
| csrrci | 1 |

## traps

Not covered: none.
Not applicable in this design: interrupt 11, interrupt 3, interrupt 7.

| bin | count |
|---|---|
| exception 0 | 1 |
| exception 1 | 2 |
| exception 2 | 242 |
| exception 3 | 1 |
| exception 4 | 2 |
| exception 5 | 2 |
| exception 6 | 1 |
| exception 7 | 1 |
| exception 11 | 59 |
| interrupt 3 | 0 |
| interrupt 7 | 0 |
| interrupt 11 | 0 |

## stalls

Not covered: none.
Not applicable in this design: Imem.

| bin | count |
|---|---|
| LoadUse | 2294 |
| BranchUse | 5699 |
| MulDiv | 1171 |
| Imem | 0 |
| Dmem | 33 |

## flushes

Not covered: none.

| bin | count |
|---|---|
| Branch | 305391 |
| Jump | 6304 |
| Trap | 382 |

## forwarding

Not covered: none.

| bin | count |
|---|---|
| ID.rs1<-EX/MEM | 10863 |
| ID.rs1<-MEM/WB | 22622 |
| ID.rs2<-EX/MEM | 307123 |
| ID.rs2<-MEM/WB | 305497 |
| EX.rs1<-EX/MEM | 3327 |
| EX.rs1<-MEM/WB | 8794 |
| EX.rs2<-EX/MEM | 305756 |
| EX.rs2<-MEM/WB | 306303 |

## stage_patterns

Not covered: - - - - -, IF - - - -, - ID - - -, - - EX - -, - ID EX - -, - - - MEM -, - ID - MEM -, - - EX MEM -, - ID EX MEM -, - - - - WB, - ID - - WB, - - EX - WB, - ID EX - WB, - - - MEM WB, - ID - MEM WB, - - EX MEM WB, - ID EX MEM WB.

| bin | count |
|---|---|
| - - - - - | 0 |
| IF - - - - | 0 |
| - ID - - - | 0 |
| IF ID - - - | 464 |
| - - EX - - | 0 |
| IF - EX - - | 66 |
| - ID EX - - | 0 |
| IF ID EX - - | 2701 |
| - - - MEM - | 0 |
| IF - - MEM - | 4 |
| - ID - MEM - | 0 |
| IF ID - MEM - | 2859 |
| - - EX MEM - | 0 |
| IF - EX MEM - | 76 |
| - ID EX MEM - | 0 |
| IF ID EX MEM - | 314885 |
| - - - - WB | 0 |
| IF - - - WB | 382 |
| - ID - - WB | 0 |
| IF ID - - WB | 1183 |
| - - EX - WB | 0 |
| IF - EX - WB | 1775 |
| - ID EX - WB | 0 |
| IF ID EX - WB | 314391 |
| - - - MEM WB | 0 |
| IF - - MEM WB | 40 |
| - ID - MEM WB | 0 |
| IF ID - MEM WB | 314397 |
| - - EX MEM WB | 0 |
| IF - EX MEM WB | 309728 |
| - ID EX MEM WB | 0 |
| IF ID EX MEM WB | 1282182 |

## stage_busy (cycles each stage held an instruction)

| stage | cycles |
|---|---|
| IF | 2545133 |
| ID | 2233062 |
| EX | 2225804 |
| MEM | 2224171 |
| WB | 2224078 |
