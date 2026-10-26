#include "elf.h"

#include <cstring>
#include <fstream>
#include <iterator>

namespace {

// ELF constants we use (see the ELF specification / <elf.h>).
constexpr uint8_t kClass32 = 1;       // EI_CLASS: 32-bit
constexpr uint8_t kDataLsb = 1;       // EI_DATA: little endian
constexpr uint16_t kTypeExec = 2;     // e_type: ET_EXEC
constexpr uint16_t kMachineRiscv = 243;
constexpr uint32_t kPtLoad = 1;       // p_type: PT_LOAD
constexpr uint32_t kShtSymtab = 2;    // sh_type: SHT_SYMTAB
constexpr uint8_t kBindGlobal = 1;    // STB_GLOBAL

// Little-endian readers. The file is little endian no matter what the host is.
uint16_t rd16(const std::vector<uint8_t>& b, size_t off) {
  return static_cast<uint16_t>(b[off] | (b[off + 1] << 8));
}
uint32_t rd32(const std::vector<uint8_t>& b, size_t off) {
  return static_cast<uint32_t>(b[off]) | (static_cast<uint32_t>(b[off + 1]) << 8) |
         (static_cast<uint32_t>(b[off + 2]) << 16) | (static_cast<uint32_t>(b[off + 3]) << 24);
}

bool fits(const std::vector<uint8_t>& b, uint64_t off, uint64_t len) {
  return off + len <= b.size();
}

}  // namespace

bool load_elf(const std::string& path, ElfImage* img, std::string* err) {
  std::ifstream f(path, std::ios::binary);
  if (!f) {
    *err = "cannot open " + path;
    return false;
  }
  img->bytes.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
  const std::vector<uint8_t>& b = img->bytes;

  // ELF header: 52 bytes for ELF32.
  if (b.size() < 52 || std::memcmp(b.data(), "\x7f" "ELF", 4) != 0) {
    *err = path + " is not an ELF file";
    return false;
  }
  if (b[4] != kClass32 || b[5] != kDataLsb) {
    *err = path + " is not a 32-bit little-endian ELF";
    return false;
  }
  if (rd16(b, 16) != kTypeExec || rd16(b, 18) != kMachineRiscv) {
    *err = path + " is not a RISC-V executable (ET_EXEC, EM_RISCV)";
    return false;
  }
  img->entry = rd32(b, 24);
  const uint32_t phoff = rd32(b, 28);
  const uint32_t shoff = rd32(b, 32);
  const uint16_t phentsize = rd16(b, 42);
  const uint16_t phnum = rd16(b, 44);
  const uint16_t shentsize = rd16(b, 46);
  const uint16_t shnum = rd16(b, 48);

  // Program headers: keep the PT_LOAD entries, skip everything else.
  for (uint32_t i = 0; i < phnum; ++i) {
    const uint64_t ph = static_cast<uint64_t>(phoff) + static_cast<uint64_t>(i) * phentsize;
    if (phentsize < 32 || !fits(b, ph, 32)) {
      *err = "program header table is outside the file";
      return false;
    }
    if (rd32(b, ph) != kPtLoad) continue;
    ElfSegment s{rd32(b, ph + 4), rd32(b, ph + 8), rd32(b, ph + 16), rd32(b, ph + 20),
                 rd32(b, ph + 24)};
    if (s.filesz > s.memsz || !fits(b, s.offset, s.filesz)) {
      *err = "a PT_LOAD segment has a bad size or lies outside the file";
      return false;
    }
    img->segments.push_back(s);
  }
  if (img->segments.empty()) {
    *err = path + " has no PT_LOAD segment";
    return false;
  }

  // Symbol table: find the SHT_SYMTAB section. Its sh_link field is the
  // index of the string table that holds the symbol names.
  for (uint32_t i = 0; i < shnum; ++i) {
    const uint64_t sh = static_cast<uint64_t>(shoff) + static_cast<uint64_t>(i) * shentsize;
    if (shentsize < 40 || !fits(b, sh, 40)) break;  // no usable section table
    if (rd32(b, sh + 4) != kShtSymtab) continue;

    const uint32_t sym_off = rd32(b, sh + 16);
    const uint32_t sym_size = rd32(b, sh + 20);
    const uint32_t str_index = rd32(b, sh + 24);
    const uint64_t str_sh = static_cast<uint64_t>(shoff) + static_cast<uint64_t>(str_index) * shentsize;
    if (str_index >= shnum || !fits(b, str_sh, 40) || !fits(b, sym_off, sym_size)) break;
    const uint32_t str_off = rd32(b, str_sh + 16);
    const uint32_t str_size = rd32(b, str_sh + 20);
    if (!fits(b, str_off, str_size)) break;

    // Each ELF32 symbol entry is 16 bytes:
    //   st_name(4) st_value(4) st_size(4) st_info(1) st_other(1) st_shndx(2)
    for (uint32_t e = 0; e + 16 <= sym_size; e += 16) {
      const uint32_t name = rd32(b, sym_off + e);
      if (name == 0 || name >= str_size) continue;
      const char* s = reinterpret_cast<const char*>(&b[str_off + name]);
      const size_t max_len = str_size - name;
      const std::string key(s, strnlen(s, max_len));
      const uint32_t value = rd32(b, sym_off + e + 4);
      const bool global = (b[sym_off + e + 12] >> 4) == kBindGlobal;
      // A global symbol wins over a local one with the same name.
      auto it = img->symbols.find(key);
      if (it == img->symbols.end() || global) img->symbols[key] = value;
    }
    break;
  }
  return true;
}
