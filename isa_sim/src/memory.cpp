#include "memory.h"

#include <cstdio>

namespace {

// True if [addr, addr + len) lies fully inside [base, base + size).
bool inside(uint32_t addr, uint32_t len, uint32_t base, uint32_t size) {
  const uint64_t a = addr, end = static_cast<uint64_t>(addr) + len;
  return a >= base && end <= static_cast<uint64_t>(base) + size;
}

}  // namespace

bool Memory::load_segment(uint32_t addr, const uint8_t* data, uint32_t filesz, uint32_t memsz,
                          std::string* err) {
  if (memsz == 0) return true;
  std::vector<uint8_t>* mem = nullptr;
  uint32_t base = 0;
  if (inside(addr, memsz, memmap::kImemBase, memmap::kImemSize)) {
    mem = &imem_;
    base = memmap::kImemBase;
  } else if (inside(addr, memsz, memmap::kDmemBase, memmap::kDmemSize)) {
    mem = &dmem_;
    base = memmap::kDmemBase;
  } else {
    char buf[160];
    std::snprintf(buf, sizeof buf,
                  "segment at 0x%08x (0x%x bytes) does not fit in IMEM or DMEM "
                  "-- check workload/common/link.ld",
                  addr, memsz);
    *err = buf;
    return false;
  }
  const uint32_t off = addr - base;
  std::memcpy(&(*mem)[off], data, filesz);
  std::memset(&(*mem)[off + filesz], 0, memsz - filesz);  // the .bss part
  return true;
}
