// Minimal ELF32 reader for RISC-V bare-metal executables.
// It reads only what the simulator needs: the entry point, the PT_LOAD
// segments (what to copy into memory, and where), and the symbol table
// (to find tohost, result_ptr and result_nwords).
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

struct ElfSegment {
  uint32_t offset;  // where the bytes start in the file
  uint32_t vaddr;   // where they go in memory
  uint32_t filesz;  // bytes to copy from the file
  uint32_t memsz;   // bytes the segment takes in memory (memsz - filesz is .bss)
  uint32_t flags;   // PF_R / PF_W / PF_X
};

struct ElfImage {
  std::vector<uint8_t> bytes;  // the whole file
  uint32_t entry = 0;
  std::vector<ElfSegment> segments;                    // PT_LOAD only
  std::unordered_map<std::string, uint32_t> symbols;   // name -> address
};

// Reads and checks the file. Returns false and fills *err on any problem.
bool load_elf(const std::string& path, ElfImage* img, std::string* err);
