// Field and immediate extraction for 32-bit RISC-V instructions.
//
//   31      25 24  20 19  15 14 12 11   7 6      0
//  [ funct7   | rs2  | rs1  | f3  | rd   | opcode ]   R-type
//  [ imm[11:0]       | rs1  | f3  | rd   | opcode ]   I-type
//  [ imm[11:5]| rs2  | rs1  | f3  |imm4:0| opcode ]   S-type
//  [ imm[12|10:5]|rs2| rs1  | f3  |imm4:1|11| opc ]   B-type
//  [ imm[31:12]                   | rd   | opcode ]   U-type
//  [ imm[20|10:1|11|19:12]        | rd   | opcode ]   J-type
#pragma once

#include <cstdint>

namespace dec {

inline uint32_t opcode(uint32_t i) { return i & 0x7f; }
inline uint32_t rd(uint32_t i) { return (i >> 7) & 0x1f; }
inline uint32_t funct3(uint32_t i) { return (i >> 12) & 0x7; }
inline uint32_t rs1(uint32_t i) { return (i >> 15) & 0x1f; }
inline uint32_t rs2(uint32_t i) { return (i >> 20) & 0x1f; }
inline uint32_t funct7(uint32_t i) { return i >> 25; }

// Sign-extend the low `bits` bits of v to 32 bits.
inline uint32_t sext(uint32_t v, unsigned bits) {
  const uint32_t m = 1u << (bits - 1);
  v &= (bits == 32) ? 0xffffffffu : ((1u << bits) - 1);
  return (v ^ m) - m;
}

// Each immediate is rebuilt bit by bit from the fields shown above, then
// sign-extended. The results are uint32_t: RISC-V arithmetic wraps, and
// unsigned math in C++ wraps too, so no signed overflow can happen.
inline uint32_t imm_i(uint32_t i) { return sext(i >> 20, 12); }
inline uint32_t imm_s(uint32_t i) { return sext(((i >> 25) << 5) | ((i >> 7) & 0x1f), 12); }
inline uint32_t imm_b(uint32_t i) {
  const uint32_t v = (((i >> 31) & 0x1) << 12) | (((i >> 7) & 0x1) << 11) |
                     (((i >> 25) & 0x3f) << 5) | (((i >> 8) & 0xf) << 1);
  return sext(v, 13);
}
inline uint32_t imm_u(uint32_t i) { return i & 0xfffff000u; }
inline uint32_t imm_j(uint32_t i) {
  const uint32_t v = (((i >> 31) & 0x1) << 20) | (((i >> 12) & 0xff) << 12) |
                     (((i >> 20) & 0x1) << 11) | (((i >> 21) & 0x3ff) << 1);
  return sext(v, 21);
}

}  // namespace dec
