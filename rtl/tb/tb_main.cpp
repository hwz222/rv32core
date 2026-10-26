// Verilator testbench for SimTop.
//
//   usage: VSimTop <elf> <out_dir> [--trace FILE] [--max-cycles N]
//                  [--vcd-cycles N] [--no-dump] [--force-dump] [--core-name NAME]
//
// Steps:
//   1. Read the ELF (same reader as isa_sim), check its segments and find
//      the symbols tohost, result_ptr and result_nwords.
//   2. Load: hold reset, set hostEn = 1, and write every ELF word into
//      IMEM/DMEM through the load port (one word per cycle). .bss is 0.
//   3. Run: release reset, set hostEn = 0, and run cycle by cycle. Every
//      retire record becomes one trace line, in the same format as isa_sim.
//   4. Stop when one of these happens (same rules as isa_sim):
//        a store that touches the tohost word leaves a non-zero value there
//        a trap retires and there is no handler (trapTarget = 0)
//        the cycle limit is reached
//   5. On success, read the result through the dump port and write
//      data_sram_dump.hex. Always write cycle_count.txt, report.json and
//      coverage.json.
//
// Outputs in <out_dir>:
//   data_sram_dump.hex  result words, one %08x per line (success only)
//   cycle_count.txt     cycles from reset release to the stop
//   report.json         how the run ended (and which core: --core-name)
//   coverage.json       what the run exercised: instructions, traps, stalls,
//                       flushes, forwarding paths, pipeline states
//   waveform.vcd        the first --vcd-cycles cycles
//
// Exit codes (the same numbers as isa_sim, see isa_sim/README.md):
//   0  tohost = 1                    success
//   1  tohost odd, not 1             the program reported failure (riscv-tests)
//   2  bad arguments, bad ELF, missing symbol
//   3  trap with no handler
//   4  cycle limit reached
//   5  cannot write outputs, or the result is outside DMEM
//   6  tohost even, not 0            host call, not supported
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <map>
#include <memory>
#include <string>
#include <sys/stat.h>

#include "VSimTop.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

#include "elf.h"      // from isa_sim/src
#include "memmap.h"   // from isa_sim/src

namespace {

constexpr int kExitOk = 0;
constexpr int kExitProgramFailed = 1;
constexpr int kExitUsage = 2;
constexpr int kExitTrap = 3;
constexpr int kExitMaxCycles = 4;
constexpr int kExitOutput = 5;
constexpr int kExitUnknownTohost = 6;

constexpr uint64_t kDefaultMaxCycles = 100000000ull;  // 1e8
constexpr uint64_t kDefaultVcdCycles = 200000ull;     // 0 = whole run
constexpr int kResetHoldCycles = 5;    // extra reset cycles after loading

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

// True when [addr, addr + size) is inside [base, base + region_size).
bool inside(uint32_t addr, uint32_t size, uint32_t base, uint32_t region_size) {
  return addr >= base && addr - base <= region_size && size <= region_size - (addr - base);
}

// Create each missing parent directory, like "mkdir -p".
bool make_dir(const std::string& path) {
  for (size_t pos = 1; pos <= path.size(); ++pos) {
    if (pos != path.size() && path[pos] != '/') continue;
    const std::string part = path.substr(0, pos);
    if (mkdir(part.c_str(), 0755) != 0 && errno != EEXIST) return false;
  }
  return true;
}

void usage(const char* prog) {
  std::fprintf(stderr,
               "usage: %s <elf> <out_dir> [--trace FILE] [--max-cycles N] [--vcd-cycles N] "
               "[--no-dump] [--force-dump] [--core-name NAME]\n",
               prog);
}

// Names for the trap message (same text as isa_sim).
const char* cause_name(bool interrupt, uint32_t c) {
  if (interrupt) {
    switch (c) {
      case 3: return "machine software interrupt";
      case 7: return "machine timer interrupt";
      case 11: return "machine external interrupt";
      default: return "unknown interrupt";
    }
  }
  switch (c) {
    case 0: return "instruction address misaligned";
    case 1: return "instruction access fault";
    case 2: return "illegal instruction";
    case 3: return "breakpoint";
    case 4: return "load address misaligned";
    case 5: return "load access fault";
    case 6: return "store/AMO address misaligned";
    case 7: return "store/AMO access fault";
    case 11: return "ecall from M-mode";
    default: return "unknown";
  }
}

// Turns the ELF segments into 32-bit words: word address -> value.
// Bytes past filesz (the .bss part) are 0. Two segments that share a word
// are merged. Returns false if a segment is not fully inside IMEM or DMEM
// (same rule and message as isa_sim).
bool elf_to_words(const ElfImage& img, std::map<uint32_t, uint32_t>* words, std::string* err) {
  for (const ElfSegment& s : img.segments) {
    if (s.memsz == 0) continue;
    if (!inside(s.vaddr, s.memsz, memmap::kImemBase, memmap::kImemSize) &&
        !inside(s.vaddr, s.memsz, memmap::kDmemBase, memmap::kDmemSize)) {
      char buf[160];
      std::snprintf(buf, sizeof buf,
                    "segment at 0x%08x (0x%x bytes) does not fit in IMEM or DMEM "
                    "-- check workload/common/link.ld",
                    s.vaddr, s.memsz);
      *err = buf;
      return false;
    }
    for (uint32_t i = 0; i < s.memsz; ++i) {
      const uint32_t addr = s.vaddr + i;
      const uint32_t byte = i < s.filesz ? img.bytes[s.offset + i] : 0;
      (*words)[addr & ~3u] |= byte << (8 * (addr & 3u));  // new entries start at 0
    }
  }
  return true;
}

// ---------------------------------------------------------------------------
// One retire record, copied out of the model so it can wait in a queue.
// ---------------------------------------------------------------------------

struct Retire {
  uint64_t cycle;          // run cycle in which the record was seen
  uint32_t pc, insn;
  bool rd_we;
  uint32_t rd_addr, rd_data;
  bool mem_we;
  uint32_t mem_addr, mem_size, mem_wdata;
  bool trap, interrupt;
  uint32_t cause, tval, trap_target;

  uint32_t mcause() const { return (interrupt ? 0x80000000u : 0u) | cause; }
  uint32_t mem_bytes() const { return 1u << mem_size; }   // 1, 2 or 4
};

Retire read_retire(const VSimTop* top, uint64_t cycle) {
  Retire r;
  r.cycle = cycle;
  r.pc = top->io_retire_pc;
  r.insn = top->io_retire_insn;
  r.rd_we = top->io_retire_rdWe;
  r.rd_addr = top->io_retire_rdAddr;
  r.rd_data = top->io_retire_rdData;
  r.mem_we = top->io_retire_memWe;
  r.mem_addr = top->io_retire_memAddr;
  r.mem_size = top->io_retire_memSize;
  r.mem_wdata = top->io_retire_memWdata;
  r.trap = top->io_retire_trap;
  r.interrupt = top->io_retire_interrupt;
  r.cause = top->io_retire_cause;
  r.tval = top->io_retire_tval;
  r.trap_target = top->io_retire_trapTarget;
  return r;
}

// Writes one trace line in the isa_sim format (isa_sim/src/hart.cpp):
//   <n> <pc> <insn> [x<rd>=<val>] [mem<bits>[<addr>]=<val>]
//   <n> <pc> trap cause=<mcause> tval=<tval>
void write_trace(FILE* f, uint64_t n, const Retire& r) {
  const unsigned long long nn = static_cast<unsigned long long>(n);
  if (r.trap) {
    std::fprintf(f, "%llu %08x trap cause=%u tval=%08x\n", nn, r.pc, r.mcause(), r.tval);
    return;
  }
  std::fprintf(f, "%llu %08x %08x", nn, r.pc, r.insn);
  if (r.rd_we && r.rd_addr != 0) std::fprintf(f, " x%u=%08x", r.rd_addr, r.rd_data);
  if (r.mem_we) {
    const uint32_t bytes = r.mem_bytes();
    const uint32_t mask = bytes == 4 ? 0xffffffffu : (1u << (8 * bytes)) - 1;
    std::fprintf(f, " mem%u[%08x]=%0*x", bytes * 8, r.mem_addr, static_cast<int>(bytes * 2),
                 r.mem_wdata & mask);
  }
  std::fputc('\n', f);
}

// ---------------------------------------------------------------------------
// Coverage: counts of what one run exercised. Merge many runs with
// verification/coverage/merge_coverage.py.
// ---------------------------------------------------------------------------

// Name of an RV32IMA / Zicsr / privileged instruction, as objdump prints it
// with -M no-aliases ("unknown" for anything else).
std::string mnemonic(uint32_t insn) {
  const uint32_t op = insn & 0x7f, f3 = (insn >> 12) & 7, f7 = insn >> 25;
  switch (op) {
    case 0x37: return "lui";
    case 0x17: return "auipc";
    case 0x6f: return "jal";
    case 0x67: return f3 == 0 ? "jalr" : "unknown";
    case 0x63: {
      static const char* n[8] = {"beq", "bne", "", "", "blt", "bge", "bltu", "bgeu"};
      return *n[f3] ? n[f3] : "unknown";
    }
    case 0x03: {
      static const char* n[8] = {"lb", "lh", "lw", "", "lbu", "lhu", "", ""};
      return *n[f3] ? n[f3] : "unknown";
    }
    case 0x23: {
      static const char* n[8] = {"sb", "sh", "sw", "", "", "", "", ""};
      return *n[f3] ? n[f3] : "unknown";
    }
    case 0x13: {
      static const char* n[8] = {"addi", "slli", "slti", "sltiu", "xori", "", "ori", "andi"};
      if (f3 == 5) return f7 == 0x20 ? "srai" : "srli";
      return n[f3];
    }
    case 0x33: {
      static const char* m[8] = {"mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu"};
      static const char* n[8] = {"add", "sll", "slt", "sltu", "xor", "srl", "or", "and"};
      if (f7 == 1) return m[f3];
      if (f7 == 0x20 && f3 == 0) return "sub";
      if (f7 == 0x20 && f3 == 5) return "sra";
      return f7 == 0 ? n[f3] : "unknown";
    }
    case 0x0f: return f3 == 0 ? "fence" : f3 == 1 ? "fence.i" : "unknown";
    case 0x73: {
      if (f3 == 0) {
        switch (insn) {
          case 0x00000073: return "ecall";
          case 0x00100073: return "ebreak";
          case 0x30200073: return "mret";
          case 0x10500073: return "wfi";
          default: return "unknown";
        }
      }
      static const char* n[8] = {"", "csrrw", "csrrs", "csrrc", "", "csrrwi", "csrrsi", "csrrci"};
      return *n[f3] ? n[f3] : "unknown";
    }
    case 0x2f: {
      if (f3 != 2) return "unknown";
      switch (insn >> 27) {
        case 0x02: return "lr.w";
        case 0x03: return "sc.w";
        case 0x01: return "amoswap.w";
        case 0x00: return "amoadd.w";
        case 0x04: return "amoxor.w";
        case 0x0c: return "amoand.w";
        case 0x08: return "amoor.w";
        case 0x10: return "amomin.w";
        case 0x14: return "amomax.w";
        case 0x18: return "amominu.w";
        case 0x1c: return "amomaxu.w";
        default: return "unknown";
      }
    }
    default: return "unknown";
  }
}

struct Coverage {
  uint64_t cycles = 0;                         // run cycles sampled
  std::map<std::string, uint64_t> opcodes;     // retired instructions by name
  std::map<std::string, uint64_t> traps;       // "exception 2", "interrupt 7"
  uint64_t stall[5] = {};                      // order of kStallNames
  uint64_t flush[3] = {};                      // order of kFlushNames
  uint64_t fwd[4][2] = {};                     // [ID rs1, ID rs2, EX rs1, EX rs2][EX/MEM, MEM/WB]
  uint64_t busy[5] = {};                       // cycles each stage held an instruction
  uint64_t pattern[32] = {};                   // which stages were busy together

  // One run cycle: read the event port.
  void sample(const VSimTop* t) {
    ++cycles;
    const bool v[5] = {t->io_events_stageValid_0 != 0, t->io_events_stageValid_1 != 0,
                       t->io_events_stageValid_2 != 0, t->io_events_stageValid_3 != 0,
                       t->io_events_stageValid_4 != 0};
    unsigned p = 0;
    for (int i = 0; i < 5; ++i) {
      if (v[i]) { ++busy[i]; p |= 1u << i; }
    }
    ++pattern[p];
    const bool st[5] = {t->io_events_stallLoadUse != 0, t->io_events_stallBranchUse != 0,
                        t->io_events_stallMulDiv != 0, t->io_events_stallImem != 0,
                        t->io_events_stallDmem != 0};
    for (int i = 0; i < 5; ++i) stall[i] += st[i];
    flush[0] += t->io_events_flushBranch != 0;
    flush[1] += t->io_events_flushJump != 0;
    flush[2] += t->io_events_flushTrap != 0;
    const unsigned f[4] = {t->io_events_idFwdRs1, t->io_events_idFwdRs2,
                           t->io_events_exFwdRs1, t->io_events_exFwdRs2};
    for (int i = 0; i < 4; ++i) {
      if (f[i] == 1) ++fwd[i][0];       // Fwd.ExMem
      else if (f[i] == 2) ++fwd[i][1];  // Fwd.MemWb
    }
  }

  // One handled retire record (the same records as the trace).
  void retire(bool trap, bool interrupt, uint32_t cause, uint32_t insn) {
    if (trap) ++traps[(interrupt ? "interrupt " : "exception ") + std::to_string(cause)];
    else ++opcodes[mnemonic(insn)];
  }

  bool write(const std::string& path, const std::string& core) const {
    static const char* kStallNames[5] = {"LoadUse", "BranchUse", "MulDiv", "Imem", "Dmem"};
    static const char* kFlushNames[3] = {"Branch", "Jump", "Trap"};
    static const char* kFwdNames[4] = {"ID.rs1", "ID.rs2", "EX.rs1", "EX.rs2"};
    static const char* kStageNames[5] = {"IF", "ID", "EX", "MEM", "WB"};
    FILE* f = std::fopen(path.c_str(), "w");
    if (!f) return false;
    auto u = [](uint64_t x) { return static_cast<unsigned long long>(x); };
    auto dump_map = [&](const char* name, const std::map<std::string, uint64_t>& m, bool last) {
      std::fprintf(f, "  \"%s\": {", name);
      bool first = true;
      for (const auto& kv : m) {
        std::fprintf(f, "%s\n    \"%s\": %llu", first ? "" : ",", kv.first.c_str(), u(kv.second));
        first = false;
      }
      std::fprintf(f, "%s}%s\n", first ? "" : "\n  ", last ? "" : ",");
    };
    std::fprintf(f, "{\n  \"core\": \"%s\",\n  \"cycles\": %llu,\n", core.c_str(), u(cycles));
    dump_map("opcodes", opcodes, false);
    dump_map("traps", traps, false);
    std::fprintf(f, "  \"stalls\": {");
    for (int i = 0; i < 5; ++i)
      std::fprintf(f, "%s\"%s\": %llu", i ? ", " : "", kStallNames[i], u(stall[i]));
    std::fprintf(f, "},\n  \"flushes\": {");
    for (int i = 0; i < 3; ++i)
      std::fprintf(f, "%s\"%s\": %llu", i ? ", " : "", kFlushNames[i], u(flush[i]));
    std::fprintf(f, "},\n  \"forwarding\": {");
    for (int i = 0; i < 4; ++i)
      std::fprintf(f, "%s\"%s<-EX/MEM\": %llu, \"%s<-MEM/WB\": %llu", i ? ", " : "",
                   kFwdNames[i], u(fwd[i][0]), kFwdNames[i], u(fwd[i][1]));
    std::fprintf(f, "},\n  \"stage_busy\": {");
    for (int i = 0; i < 5; ++i)
      std::fprintf(f, "%s\"%s\": %llu", i ? ", " : "", kStageNames[i], u(busy[i]));
    // Pattern name: busy stages by name, "-" for an empty stage, e.g. "IF ID - - WB".
    std::fprintf(f, "},\n  \"stage_patterns\": {");
    for (unsigned p = 0; p < 32; ++p) {
      std::string name;
      for (int i = 0; i < 5; ++i) name += std::string(i ? " " : "") + ((p >> i) & 1 ? kStageNames[i] : "-");
      std::fprintf(f, "%s\n    \"%s\": %llu", p ? "," : "", name.c_str(), u(pattern[p]));
    }
    std::fprintf(f, "\n  }\n}\n");
    std::fclose(f);
    return true;
  }
};

// ---------------------------------------------------------------------------
// The simulated design and its clock
// ---------------------------------------------------------------------------

class Sim {
 public:
  Sim() : top_(new VSimTop) {}
  ~Sim() {
    if (vcd_) vcd_->close();
    top_->final();
  }

  VSimTop* top() { return top_.get(); }

  // Start writing a waveform. Only the first `limit` cycles are written,
  // counted from the first load cycle (0 = all cycles), so a long run
  // does not make a huge file.
  void open_vcd(const std::string& path, uint64_t limit) {
    Verilated::traceEverOn(true);
    vcd_.reset(new VerilatedVcdC);
    top_->trace(vcd_.get(), 99);   // 99 = all levels of the design
    vcd_->open(path.c_str());
    vcd_limit_ = limit;
  }

  // One clock cycle. Set the inputs before calling it; read the outputs
  // after it returns (they then show the state after the rising edge).
  void tick() {
    top_->clock = 0;
    top_->eval();
    record_wave();
    top_->clock = 1;
    top_->eval();
    record_wave();
    ++cycles_;
  }

  // Read one DMEM word through the dump port. This takes one cycle with
  // hostEn = 1 (the core waits). hostEn stays 1 afterwards; the caller
  // sets it back to 0 if the core should go on.
  uint32_t host_read(uint32_t addr) {
    top_->io_hostEn = 1;
    top_->io_load_valid = 0;
    top_->io_dumpAddr = addr;
    tick();
    return top_->io_dumpData;
  }

 private:
  // Save the value of every signal at the current time in the VCD file.
  // Called twice per cycle (clock low, clock high).
  void record_wave() {
    if (vcd_ && (vcd_limit_ == 0 || cycles_ < vcd_limit_)) vcd_->dump(time_);
    ++time_;
  }

  std::unique_ptr<VSimTop> top_;
  std::unique_ptr<VerilatedVcdC> vcd_;
  uint64_t vcd_limit_ = 0;
  uint64_t time_ = 0;      // waveform time, two steps per cycle
  uint64_t cycles_ = 0;    // all cycles, including load, reset and host reads
};

}  // namespace

int main(int argc, char** argv) {
  Verilated::commandArgs(argc, argv);

  // ---- Arguments -------------------------------------------------------------
  std::string elf_path, out_dir, trace_path;
  uint64_t max_cycles = kDefaultMaxCycles;
  uint64_t vcd_cycles = kDefaultVcdCycles;
  bool dump = true, force_dump = false;
  std::string core_name = "unknown";
  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--trace" && i + 1 < argc) trace_path = argv[++i];
    else if (arg == "--max-cycles" && i + 1 < argc) max_cycles = std::strtoull(argv[++i], nullptr, 0);
    else if (arg == "--vcd-cycles" && i + 1 < argc) vcd_cycles = std::strtoull(argv[++i], nullptr, 0);
    else if (arg == "--no-dump") dump = false;
    else if (arg == "--force-dump") force_dump = true;   // testbench self-test only
    else if (arg == "--core-name" && i + 1 < argc) core_name = argv[++i];
    else if (!arg.empty() && arg[0] == '-') { usage(argv[0]); return kExitUsage; }
    else if (elf_path.empty()) elf_path = arg;
    else if (out_dir.empty()) out_dir = arg;
    else { usage(argv[0]); return kExitUsage; }
  }
  if (elf_path.empty() || out_dir.empty() || (force_dump && !dump)) {
    usage(argv[0]);
    return kExitUsage;
  }

  // ---- 1. ELF, segments and symbols -------------------------------------------
  ElfImage img;
  std::map<uint32_t, uint32_t> words;
  std::string err;
  if (!load_elf(elf_path, &img, &err) || !elf_to_words(img, &words, &err)) {
    std::fprintf(stderr, "tb: %s\n", err.c_str());
    return kExitUsage;
  }
  // Addresses come from the ELF, never from constants: they move when the
  // GEMM shape changes.
  auto find = [&](const char* name, uint32_t* addr) {
    auto it = img.symbols.find(name);
    if (it == img.symbols.end()) return false;
    *addr = it->second;
    return true;
  };
  uint32_t tohost = 0, result_ptr = 0, result_nwords = 0;
  if (!find("tohost", &tohost)) {
    std::fprintf(stderr, "tb: %s has no tohost symbol, so the program could never stop\n",
                 elf_path.c_str());
    return kExitUsage;
  }
  if (dump && (!find("result_ptr", &result_ptr) || !find("result_nwords", &result_nwords))) {
    std::fprintf(stderr, "tb: %s has no result_ptr/result_nwords symbol (use --no-dump?)\n",
                 elf_path.c_str());
    return kExitUsage;
  }
  if (!make_dir(out_dir)) {
    std::fprintf(stderr, "tb: cannot create %s\n", out_dir.c_str());
    return kExitOutput;
  }
  FILE* trace = nullptr;
  if (!trace_path.empty() && !(trace = std::fopen(trace_path.c_str(), "w"))) {
    std::fprintf(stderr, "tb: cannot write %s\n", trace_path.c_str());
    return kExitOutput;
  }

  Sim sim;
  VSimTop* top = sim.top();
  sim.open_vcd(out_dir + "/waveform.vcd", vcd_cycles);

  // ---- 2. Load: reset = 1, hostEn = 1 -----------------------------------------
  top->reset = 1;
  top->io_hostEn = 1;
  top->io_bootAddr = img.entry;
  top->io_irq_msip = 0;
  top->io_irq_mtip = 0;
  top->io_irq_meip = 0;
  top->io_load_valid = 0;
  top->io_dumpAddr = 0;

  for (const auto& w : words) {
    top->io_load_valid = 1;
    top->io_load_bits_addr = w.first;
    top->io_load_bits_data = w.second;
    sim.tick();
  }
  top->io_load_valid = 0;
  for (int i = 0; i < kResetHoldCycles; ++i) sim.tick();

  // ---- 3. Run: reset = 0, hostEn = 0 ------------------------------------------
  top->reset = 0;
  top->io_hostEn = 0;

  uint64_t run_cycles = 0;     // cycles the core really ran (host reads not counted)
  uint64_t retired = 0;        // <n> in the trace
  std::deque<Retire> queue;    // records seen but not handled yet

  // One cycle of the core. A record that shows up is queued.
  Coverage cov;
  auto run_one_cycle = [&]() {
    sim.tick();
    ++run_cycles;
    cov.sample(top);
    if (top->io_retire_valid) queue.push_back(read_retire(top, run_cycles));
  };
  // One host read. The core may still retire an instruction in this cycle
  // (only memory requests wait), so check for a record here too.
  auto host_read = [&](uint32_t addr) {
    const uint32_t v = sim.host_read(addr);
    if (top->io_retire_valid) queue.push_back(read_retire(top, run_cycles));
    return v;
  };

  // ---- 4. Stop rules ----------------------------------------------------------
  enum class Stop { kToHost, kTrap, kMaxCycles } stop = Stop::kMaxCycles;
  uint32_t tohost_value = 0;
  uint64_t stop_cycle = 0;     // goes to cycle_count.txt
  Retire trap_rec{};

  while (true) {
    if (queue.empty()) {
      if (run_cycles >= max_cycles) {
        stop = Stop::kMaxCycles;
        stop_cycle = run_cycles;
        break;
      }
      run_one_cycle();
      continue;
    }
    const Retire r = queue.front();
    queue.pop_front();
    if (trace) write_trace(trace, retired, r);
    cov.retire(r.trap, r.interrupt, r.cause, r.insn);

    if (r.trap) {
      if (r.trap_target == 0) {   // no handler: stop, like isa_sim
        stop = Stop::kTrap;
        stop_cycle = r.cycle;
        trap_rec = r;
        break;
      }
      continue;                   // a handled trap does not count as retired
    }
    ++retired;

    // A store that touches the tohost word: read the whole word back, like
    // isa_sim. First run one more cycle, so a store that is sent in the
    // same cycle as its retire record is written before the read.
    if (r.mem_we && r.mem_addr < tohost + 4 && r.mem_addr + r.mem_bytes() > tohost) {
      run_one_cycle();
      const uint32_t v = host_read(tohost);
      if (v != 0) {
        stop = Stop::kToHost;
        stop_cycle = r.cycle;
        tohost_value = v;
        break;
      }
      top->io_hostEn = 0;         // tohost is still 0: go on
    }
  }
  if (trace) std::fclose(trace);

  // ---- 5. Exit code -------------------------------------------------------------
  int code = kExitOk;
  const char* reason = "tohost";
  switch (stop) {
    case Stop::kToHost:
      if (tohost_value == 1) code = kExitOk;
      else if (tohost_value & 1) code = kExitProgramFailed;
      else code = kExitUnknownTohost;
      if (code == kExitProgramFailed)
        std::fprintf(stderr, "tb: program reported failure, tohost=%u (test %u)\n",
                     tohost_value, tohost_value >> 1);
      if (code == kExitUnknownTohost)
        std::fprintf(stderr, "tb: unsupported tohost value %u\n", tohost_value);
      break;
    case Stop::kTrap:
      reason = "trap";
      code = kExitTrap;
      std::fprintf(stderr, "tb: trap with no handler: %s (mcause=%u) at pc=0x%08x tval=0x%08x\n",
                   cause_name(trap_rec.interrupt, trap_rec.cause), trap_rec.mcause(),
                   trap_rec.pc, trap_rec.tval);
      break;
    case Stop::kMaxCycles:
      reason = "max_cycles";
      code = kExitMaxCycles;
      std::fprintf(stderr, "tb: stopped after %llu cycles without writing tohost "
                   "(infinite loop?)\n", static_cast<unsigned long long>(max_cycles));
      break;
  }

  // ---- 6. Result dump: result_ptr is a pointer variable, so read the word
  //         stored at it to get the address of the result. ---------------------
  if ((code == kExitOk && dump) || force_dump) {
    const std::string path = out_dir + "/data_sram_dump.hex";
    FILE* f = std::fopen(path.c_str(), "w");
    const bool syms_ok = inside(result_ptr, 4, memmap::kDmemBase, memmap::kDmemSize) &&
                         inside(result_nwords, 4, memmap::kDmemBase, memmap::kDmemSize);
    if (!f || !syms_ok) {
      std::fprintf(stderr, "tb: cannot write %s or read result_ptr/result_nwords\n", path.c_str());
      code = kExitOutput;
    } else {
      const uint32_t addr = sim.host_read(result_ptr);
      const uint32_t n = sim.host_read(result_nwords);
      for (uint32_t i = 0; i < n; ++i) {
        const uint32_t a = addr + 4 * i;
        if (!inside(a, 4, memmap::kDmemBase, memmap::kDmemSize)) {
          std::fprintf(stderr, "tb: result word %u at 0x%08x is outside DMEM\n", i, a);
          code = kExitOutput;
          break;
        }
        std::fprintf(f, "%08x\n", sim.host_read(a));
      }
    }
    if (f) std::fclose(f);
  }

  // ---- 7. cycle_count.txt and report.json (always) ----------------------------
  const std::string count_path = out_dir + "/cycle_count.txt";
  if (FILE* f = std::fopen(count_path.c_str(), "w")) {
    std::fprintf(f, "%llu\n", static_cast<unsigned long long>(stop_cycle));
    std::fclose(f);
  } else {
    std::fprintf(stderr, "tb: cannot write %s\n", count_path.c_str());
    code = kExitOutput;
  }

  const std::string cpath = out_dir + "/coverage.json";
  if (!cov.write(cpath, core_name)) {
    std::fprintf(stderr, "tb: cannot write %s\n", cpath.c_str());
    code = kExitOutput;
  }

  const std::string rpath = out_dir + "/report.json";
  if (FILE* r = std::fopen(rpath.c_str(), "w")) {
    std::fprintf(r, "{\n");
    std::fprintf(r, "  \"isa\": \"rv32ima_zicsr_zifencei\",\n");
    std::fprintf(r, "  \"core\": \"%s\",\n", core_name.c_str());
    std::fprintf(r, "  \"instructions_executed\": %llu,\n",
                 static_cast<unsigned long long>(retired));
    std::fprintf(r, "  \"cycles\": %llu,\n", static_cast<unsigned long long>(stop_cycle));
    std::fprintf(r, "  \"stop_reason\": \"%s\",\n", reason);
    std::fprintf(r, "  \"tohost_value\": %u,\n", tohost_value);
    if (stop == Stop::kTrap)
      std::fprintf(r, "  \"trap\": {\"mcause\": %u, \"pc\": \"0x%08x\", \"tval\": \"0x%08x\"},\n",
                   trap_rec.mcause(), trap_rec.pc, trap_rec.tval);
    std::fprintf(r, "  \"exit_code\": %d\n", code);
    std::fprintf(r, "}\n");
    std::fclose(r);
  } else {
    std::fprintf(stderr, "tb: cannot write %s\n", rpath.c_str());
    code = kExitOutput;
  }
  return code;
}
