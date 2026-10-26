// Two separate memories, like the RTL (a Harvard design):
//   IMEM: read only by instruction fetch
//   DMEM: read and written only by loads and stores
// Any other access is a fault. Keeping the same rule as the hardware means a
// bad memory layout shows up here first, not only in the RTL.
#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "memmap.h"

// Exception codes written to mcause (RISC-V privileged spec, table 3.6).
namespace cause {
constexpr uint32_t kInsnMisaligned = 0;
constexpr uint32_t kInsnAccessFault = 1;
constexpr uint32_t kIllegalInsn = 2;
constexpr uint32_t kBreakpoint = 3;
constexpr uint32_t kLoadMisaligned = 4;
constexpr uint32_t kLoadAccessFault = 5;
constexpr uint32_t kStoreMisaligned = 6;   // also used by AMOs and SC
constexpr uint32_t kStoreAccessFault = 7;  // also used by AMOs and SC
constexpr uint32_t kEcallFromM = 11;
}  // namespace cause

class Memory {
 public:
  Memory() : imem_(memmap::kImemSize, 0), dmem_(memmap::kDmemSize, 0) {}

  // For the ELF loader only: copy filesz bytes to addr and zero the rest of
  // memsz. This ignores the fetch/data rule (the loader may fill IMEM).
  bool load_segment(uint32_t addr, const uint8_t* data, uint32_t filesz, uint32_t memsz,
                    std::string* err);

  // The access functions below return true on success. On failure they
  // return false and put the mcause value in *c. The host must be little
  // endian, like RISC-V, so memcpy gives the right byte order.

  bool fetch(uint32_t addr, uint32_t* insn, uint32_t* c) const {
    if (addr & 3) { *c = cause::kInsnMisaligned; return false; }
    const uint32_t off = addr - memmap::kImemBase;
    if (off >= memmap::kImemSize) { *c = cause::kInsnAccessFault; return false; }
    std::memcpy(insn, &imem_[off], 4);
    return true;
  }

  // size is 1, 2 or 4. The value is zero-extended; the caller sign-extends.
  bool load(uint32_t addr, unsigned size, uint32_t* value, uint32_t* c) const {
    if (addr & (size - 1)) { *c = cause::kLoadMisaligned; return false; }
    const uint32_t off = addr - memmap::kDmemBase;
    if (off >= memmap::kDmemSize) { *c = cause::kLoadAccessFault; return false; }
    uint32_t v = 0;
    std::memcpy(&v, &dmem_[off], size);
    *value = v;
    return true;
  }

  // Writes the low `size` bytes of value.
  bool store(uint32_t addr, unsigned size, uint32_t value, uint32_t* c) {
    if (addr & (size - 1)) { *c = cause::kStoreMisaligned; return false; }
    const uint32_t off = addr - memmap::kDmemBase;
    if (off >= memmap::kDmemSize) { *c = cause::kStoreAccessFault; return false; }
    std::memcpy(&dmem_[off], &value, size);
    return true;
  }

  // Reads one aligned word of DMEM for the result dump. No trap, just false.
  bool peek32(uint32_t addr, uint32_t* value) const {
    uint32_t c;
    return load(addr, 4, value, &c);
  }

 private:
  std::vector<uint8_t> imem_;
  std::vector<uint8_t> dmem_;
};
