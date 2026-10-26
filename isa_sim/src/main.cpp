// isa_sim: functional (non-timing) RV32IMA simulator.
//
//   usage: isa_sim <elf> <out_dir> [--trace FILE] [--max-insns N] [--no-dump] [--info]
//
// Loads the ELF, runs it from the entry point until it writes tohost, then
// writes <out_dir>/output_tensor.hex (the result tensor, one %08x word per
// line) and <out_dir>/report.json. Exit codes are listed in README.md.
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/stat.h>

#include "elf.h"
#include "hart.h"
#include "memory.h"

namespace {

// Exit codes. run_isa_sim.sh passes them through unchanged.
constexpr int kExitOk = 0;
constexpr int kExitProgramFailed = 1;  // tohost = (n << 1) | 1 with n != 0
constexpr int kExitUsage = 2;          // bad arguments, bad ELF, missing symbol
constexpr int kExitTrap = 3;           // trap with no handler (mtvec == 0)
constexpr int kExitMaxInsns = 4;       // instruction limit reached
constexpr int kExitOutput = 5;         // cannot write the output files
constexpr int kExitUnknownTohost = 6;  // even non-zero tohost value (syscall)

constexpr uint64_t kDefaultMaxInsns = 2000000000ull;

const char* cause_name(uint32_t c) {
  switch (c) {
    case cause::kInsnMisaligned: return "instruction address misaligned";
    case cause::kInsnAccessFault: return "instruction access fault";
    case cause::kIllegalInsn: return "illegal instruction";
    case cause::kBreakpoint: return "breakpoint";
    case cause::kLoadMisaligned: return "load address misaligned";
    case cause::kLoadAccessFault: return "load access fault";
    case cause::kStoreMisaligned: return "store/AMO address misaligned";
    case cause::kStoreAccessFault: return "store/AMO access fault";
    case cause::kEcallFromM: return "ecall from M-mode";
    default: return "unknown";
  }
}

const char* region_name(uint32_t addr) {
  if (addr - memmap::kImemBase < memmap::kImemSize) return "IMEM";
  if (addr - memmap::kDmemBase < memmap::kDmemSize) return "DMEM";
  return "none";
}

// --info: print what the loader sees, then exit. Handy for checking an ELF.
void print_info(const ElfImage& img) {
  std::printf("entry   0x%08x\n", img.entry);
  for (size_t i = 0; i < img.segments.size(); ++i) {
    const ElfSegment& s = img.segments[i];
    std::printf("segment %zu: vaddr 0x%08x filesz 0x%06x memsz 0x%06x flags %c%c%c -> %s\n", i,
                s.vaddr, s.filesz, s.memsz, (s.flags & 4) ? 'R' : '-', (s.flags & 2) ? 'W' : '-',
                (s.flags & 1) ? 'X' : '-', region_name(s.vaddr));
  }
  for (const char* name : {"_start", "tohost", "result_ptr", "result_nwords", "_stack_top"}) {
    auto it = img.symbols.find(name);
    if (it != img.symbols.end()) std::printf("symbol  %-14s 0x%08x\n", name, it->second);
    else std::printf("symbol  %-14s (missing)\n", name);
  }
}

bool make_dir(const std::string& path) {
  // Create each missing parent directory, like "mkdir -p".
  for (size_t pos = 1; pos <= path.size(); ++pos) {
    if (pos != path.size() && path[pos] != '/') continue;
    const std::string part = path.substr(0, pos);
    if (mkdir(part.c_str(), 0755) != 0 && errno != EEXIST) return false;
  }
  return true;
}

void usage(const char* prog) {
  std::fprintf(stderr,
               "usage: %s <elf> <out_dir> [--trace FILE] [--max-insns N] [--no-dump] [--info]\n",
               prog);
}

}  // namespace

int main(int argc, char** argv) {
  std::string elf_path, out_dir, trace_path;
  uint64_t max_insns = kDefaultMaxInsns;
  bool dump = true, info = false;

  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--trace" && i + 1 < argc) trace_path = argv[++i];
    else if (arg == "--max-insns" && i + 1 < argc) max_insns = std::strtoull(argv[++i], nullptr, 0);
    else if (arg == "--no-dump") dump = false;
    else if (arg == "--info") info = true;
    else if (!arg.empty() && arg[0] == '-') { usage(argv[0]); return kExitUsage; }
    else if (elf_path.empty()) elf_path = arg;
    else if (out_dir.empty()) out_dir = arg;
    else { usage(argv[0]); return kExitUsage; }
  }
  if (elf_path.empty() || (out_dir.empty() && !info)) {
    usage(argv[0]);
    return kExitUsage;
  }

  // 1. Load the ELF into IMEM and DMEM.
  ElfImage img;
  std::string err;
  if (!load_elf(elf_path, &img, &err)) {
    std::fprintf(stderr, "isa_sim: %s\n", err.c_str());
    return kExitUsage;
  }
  Memory mem;
  for (const ElfSegment& s : img.segments) {
    if (!mem.load_segment(s.vaddr, &img.bytes[s.offset], s.filesz, s.memsz, &err)) {
      std::fprintf(stderr, "isa_sim: %s\n", err.c_str());
      return kExitUsage;
    }
  }
  if (info) {
    print_info(img);
    return kExitOk;
  }

  // 2. Find the symbols we need. Addresses come from the ELF, never from
  //    constants: they move when the GEMM shape changes.
  auto find = [&](const char* name, uint32_t* addr) {
    auto it = img.symbols.find(name);
    if (it == img.symbols.end()) return false;
    *addr = it->second;
    return true;
  };
  uint32_t tohost = 0, result_ptr = 0, result_nwords = 0;
  if (!find("tohost", &tohost)) {
    std::fprintf(stderr, "isa_sim: %s has no tohost symbol, so the program could never stop\n",
                 elf_path.c_str());
    return kExitUsage;
  }
  if (dump && (!find("result_ptr", &result_ptr) || !find("result_nwords", &result_nwords))) {
    std::fprintf(stderr, "isa_sim: %s has no result_ptr/result_nwords symbol (use --no-dump?)\n",
                 elf_path.c_str());
    return kExitUsage;
  }
  if (!make_dir(out_dir)) {
    std::fprintf(stderr, "isa_sim: cannot create %s\n", out_dir.c_str());
    return kExitOutput;
  }

  // 3. Run.
  FILE* trace = nullptr;
  if (!trace_path.empty() && !(trace = std::fopen(trace_path.c_str(), "w"))) {
    std::fprintf(stderr, "isa_sim: cannot write %s\n", trace_path.c_str());
    return kExitOutput;
  }
  Hart hart(mem, img.entry, tohost);
  hart.set_trace(trace);
  const StopInfo s = hart.run(max_insns);
  if (trace) std::fclose(trace);

  // 4. Decide the result.
  int code = kExitOk;
  const char* reason = "tohost";
  switch (s.reason) {
    case StopInfo::Reason::kToHost:
      if (s.tohost_value == 1) code = kExitOk;
      else if (s.tohost_value & 1) code = kExitProgramFailed;
      else code = kExitUnknownTohost;
      if (code == kExitProgramFailed)
        std::fprintf(stderr, "isa_sim: program reported failure, tohost=%u (test %u)\n",
                     s.tohost_value, s.tohost_value >> 1);
      if (code == kExitUnknownTohost)
        std::fprintf(stderr, "isa_sim: unsupported tohost value %u\n", s.tohost_value);
      break;
    case StopInfo::Reason::kTrap:
      reason = "trap";
      code = kExitTrap;
      std::fprintf(stderr, "isa_sim: trap with no handler: %s (mcause=%u) at pc=0x%08x tval=0x%08x\n",
                   cause_name(s.trap_cause), s.trap_cause, s.trap_pc, s.trap_tval);
      break;
    case StopInfo::Reason::kMaxInsns:
      reason = "max_insns";
      code = kExitMaxInsns;
      std::fprintf(stderr, "isa_sim: stopped after %llu steps without writing tohost "
                   "(infinite loop?)\n", static_cast<unsigned long long>(max_insns));
      break;
  }

  // 5. Dump the result tensor. result_ptr is a pointer variable: read the
  //    word stored at it to get the address of the result.
  if (code == kExitOk && dump) {
    const std::string path = out_dir + "/output_tensor.hex";
    FILE* f = std::fopen(path.c_str(), "w");
    uint32_t addr = 0, n = 0;
    if (!f || !mem.peek32(result_ptr, &addr) || !mem.peek32(result_nwords, &n)) {
      std::fprintf(stderr, "isa_sim: cannot write %s or read result_ptr/result_nwords\n",
                   path.c_str());
      code = kExitOutput;
    } else {
      for (uint32_t i = 0; i < n; ++i) {
        uint32_t w;
        if (!mem.peek32(addr + 4 * i, &w)) {
          std::fprintf(stderr, "isa_sim: result word %u at 0x%08x is outside DMEM\n", i,
                       addr + 4 * i);
          code = kExitOutput;
          break;
        }
        std::fprintf(f, "%08x\n", w);
      }
    }
    if (f) std::fclose(f);
  }

  // 6. report.json: always written, also on failure, to help debugging.
  const std::string rpath = out_dir + "/report.json";
  if (FILE* r = std::fopen(rpath.c_str(), "w")) {
    std::fprintf(r, "{\n");
    std::fprintf(r, "  \"isa\": \"rv32ima_zicsr_zifencei\",\n");
    std::fprintf(r, "  \"instructions_executed\": %llu,\n",
                 static_cast<unsigned long long>(hart.retired()));
    std::fprintf(r, "  \"stop_reason\": \"%s\",\n", reason);
    std::fprintf(r, "  \"tohost_value\": %u,\n", s.tohost_value);
    if (s.reason == StopInfo::Reason::kTrap)
      std::fprintf(r, "  \"trap\": {\"mcause\": %u, \"pc\": \"0x%08x\", \"tval\": \"0x%08x\"},\n",
                   s.trap_cause, s.trap_pc, s.trap_tval);
    std::fprintf(r, "  \"exit_code\": %d\n", code);
    std::fprintf(r, "}\n");
    std::fclose(r);
  } else if (code == kExitOk) {
    std::fprintf(stderr, "isa_sim: cannot write %s\n", rpath.c_str());
    code = kExitOutput;
  }
  return code;
}
