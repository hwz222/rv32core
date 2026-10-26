// Memory map of the core. These numbers must match workload/common/link.ld
// and rtl/DESIGN.md section 4.1. If you change one, change all three.
#pragma once

#include <cstdint>

namespace memmap {

// Instruction scratchpad. Only instruction fetch can read it.
constexpr uint32_t kImemBase = 0x00000000;
constexpr uint32_t kImemSize = 64 * 1024;

// Data SRAM. Every load and store goes here, including reads of const data.
constexpr uint32_t kDmemBase = 0x10000000;
constexpr uint32_t kDmemSize = 2 * 1024 * 1024;

}  // namespace memmap
