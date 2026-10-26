#include "hart.h"

#include "decode.h"

namespace {

// M extension: MUL MULH MULHSU MULHU DIV DIVU REM REMU.
// The "high" multiplies need the upper 32 bits of a 64-bit product.
// Division never traps in RISC-V; the spec fixes the result instead:
//   x / 0         -> -1 (all ones)      x % 0         -> x
//   INT_MIN / -1  -> INT_MIN            INT_MIN % -1  -> 0
// In C++ both cases are undefined behavior, so they are handled first.
uint32_t exec_m(uint32_t f3, uint32_t a, uint32_t b) {
  const int32_t sa = static_cast<int32_t>(a), sb = static_cast<int32_t>(b);
  const int32_t kIntMin = static_cast<int32_t>(0x80000000u);
  switch (f3) {
    case 0:  // MUL: low 32 bits, same for signed and unsigned
      return a * b;
    case 1:  // MULH: signed x signed
      return static_cast<uint32_t>((static_cast<int64_t>(sa) * static_cast<int64_t>(sb)) >> 32);
    case 2:  // MULHSU: signed x unsigned; |sa * b| < 2^63, so int64 is enough
      return static_cast<uint32_t>((static_cast<int64_t>(sa) * static_cast<int64_t>(b)) >> 32);
    case 3:  // MULHU: unsigned x unsigned
      return static_cast<uint32_t>((static_cast<uint64_t>(a) * static_cast<uint64_t>(b)) >> 32);
    case 4:  // DIV
      if (b == 0) return 0xffffffffu;
      if (sa == kIntMin && sb == -1) return a;
      return static_cast<uint32_t>(sa / sb);
    case 5:  // DIVU
      return b == 0 ? 0xffffffffu : a / b;
    case 6:  // REM
      if (b == 0) return a;
      if (sa == kIntMin && sb == -1) return 0;
      return static_cast<uint32_t>(sa % sb);
    default:  // 7: REMU
      return b == 0 ? a : a % b;
  }
}

// CSR numbers (RISC-V privileged spec, chapter 2).
namespace csr {
constexpr uint32_t kMstatus = 0x300, kMisa = 0x301, kMie = 0x304, kMtvec = 0x305;
constexpr uint32_t kMstatush = 0x310, kMscratch = 0x340, kMepc = 0x341, kMcause = 0x342;
constexpr uint32_t kMtval = 0x343, kMip = 0x344;
constexpr uint32_t kMcycle = 0xb00, kMinstret = 0xb02, kMcycleh = 0xb80, kMinstreth = 0xb82;
constexpr uint32_t kCycle = 0xc00, kTime = 0xc01, kInstret = 0xc02;
constexpr uint32_t kCycleh = 0xc80, kTimeh = 0xc81, kInstreth = 0xc82;
constexpr uint32_t kMvendorid = 0xf11, kMarchid = 0xf12, kMimpid = 0xf13, kMhartid = 0xf14;
constexpr uint32_t kMconfigptr = 0xf15;
}  // namespace csr

constexpr uint32_t kMstatusMie = 1u << 3;
constexpr uint32_t kMstatusMpie = 1u << 7;
constexpr uint32_t kMstatusMppM = 3u << 11;
// misa: MXL = 1 (32-bit) in bits 31:30, plus the letters A (bit 0),
// I (bit 8) and M (bit 12).
constexpr uint32_t kMisaValue = (1u << 30) | (1u << 0) | (1u << 8) | (1u << 12);

}  // namespace

StopInfo Hart::run(uint64_t max_steps) {
  while (steps_ < max_steps) {
    ++steps_;
    if (!step()) return stop_;
  }
  stop_.reason = StopInfo::Reason::kMaxInsns;
  return stop_;
}

// Takes a trap. Returns false (stop the run) when there is no handler.
bool Hart::trap(uint32_t cause, uint32_t tval) {
  if (trace_)
    std::fprintf(trace_, "%llu %08x trap cause=%u tval=%08x\n",
                 static_cast<unsigned long long>(retired_), pc_, cause, tval);
  const uint32_t base = mtvec_ & ~3u;
  if (base == 0) {
    // Our crt0 sets no trap handler, so mtvec stays 0. Jumping to 0 would
    // silently restart the program; stop and report instead.
    stop_.reason = StopInfo::Reason::kTrap;
    stop_.trap_cause = cause;
    stop_.trap_pc = pc_;
    stop_.trap_tval = tval;
    return false;
  }
  mepc_ = pc_;
  mcause_ = cause;
  mtval_ = tval;
  // MPIE <- MIE, MIE <- 0. MPP stays M-mode (the only mode).
  mstatus_ = ((mstatus_ & kMstatusMie) ? kMstatusMpie : 0);
  reserved_ = false;
  pc_ = base;
  return true;
}

bool Hart::csr_read(uint32_t addr, uint32_t* v) const {
  switch (addr) {
    case csr::kMstatus: *v = mstatus_ | kMstatusMppM; return true;
    case csr::kMisa: *v = kMisaValue; return true;
    case csr::kMtvec: *v = mtvec_; return true;
    case csr::kMscratch: *v = mscratch_; return true;
    case csr::kMepc: *v = mepc_; return true;
    case csr::kMcause: *v = mcause_; return true;
    case csr::kMtval: *v = mtval_; return true;
    case csr::kMstatush:  // no fields used on this core
    case csr::kMie:       // no interrupts
    case csr::kMip:
    case csr::kMvendorid:
    case csr::kMarchid:
    case csr::kMimpid:
    case csr::kMhartid:   // a single hart, number 0
    case csr::kMconfigptr:
      *v = 0;
      return true;
    case csr::kMcycle: case csr::kCycle: case csr::kTime:  // no timer device: time = cycle
      *v = static_cast<uint32_t>(mcycle_); return true;
    case csr::kMcycleh: case csr::kCycleh: case csr::kTimeh:
      *v = static_cast<uint32_t>(mcycle_ >> 32); return true;
    case csr::kMinstret: case csr::kInstret:
      *v = static_cast<uint32_t>(minstret_); return true;
    case csr::kMinstreth: case csr::kInstreth:
      *v = static_cast<uint32_t>(minstret_ >> 32); return true;
  }
  // Hardware performance counters 3..31 and their event selectors exist but
  // count nothing, which the privileged spec allows.
  if ((addr >= 0xb03 && addr <= 0xb1f) || (addr >= 0xb83 && addr <= 0xb9f) ||
      (addr >= 0xc03 && addr <= 0xc1f) || (addr >= 0xc83 && addr <= 0xc9f) ||
      (addr >= 0x323 && addr <= 0x33f)) {
    *v = 0;
    return true;
  }
  return false;
}

// Only called after csr_read() said the CSR exists and it is not read-only.
void Hart::csr_write(uint32_t addr, uint32_t v) {
  switch (addr) {
    case csr::kMstatus: mstatus_ = v & (kMstatusMie | kMstatusMpie); break;
    case csr::kMtvec: mtvec_ = v & ~3u; break;  // direct mode only
    case csr::kMscratch: mscratch_ = v; break;
    case csr::kMepc: mepc_ = v & ~3u; break;    // instructions are 4-byte aligned
    case csr::kMcause: mcause_ = v; break;
    case csr::kMtval: mtval_ = v; break;
    case csr::kMcycle:
      mcycle_ = (mcycle_ & 0xffffffff00000000ull) | v; mcycle_written_ = true; break;
    case csr::kMcycleh:
      mcycle_ = (mcycle_ & 0xffffffffull) | (static_cast<uint64_t>(v) << 32); mcycle_written_ = true;
      break;
    case csr::kMinstret:
      minstret_ = (minstret_ & 0xffffffff00000000ull) | v; minstret_written_ = true; break;
    case csr::kMinstreth:
      minstret_ = (minstret_ & 0xffffffffull) | (static_cast<uint64_t>(v) << 32);
      minstret_written_ = true;
      break;
    default: break;  // misa, mie, mip, hpm counters...: writes are ignored (WARL)
  }
}

bool Hart::step() {
  uint32_t insn, c;
  if (!mem_.fetch(pc_, &insn, &c)) return trap(c, pc_);

  const uint32_t rd = dec::rd(insn), rs1 = dec::rs1(insn), rs2 = dec::rs2(insn);
  const uint32_t f3 = dec::funct3(insn), f7 = dec::funct7(insn);
  // Read both sources before anything is written: rd may equal rs1 or rs2.
  const uint32_t a = x_[rs1], b = x_[rs2];

  uint32_t next = pc_ + 4;
  bool wb = false;      // does this instruction write rd?
  uint32_t wb_val = 0;
  bool st = false;      // did it store to memory? (for trace and tohost)
  uint32_t st_addr = 0, st_val = 0;
  unsigned st_size = 0;

  // A taken branch or jump to an address that is not 4-byte aligned raises
  // the exception on the jump itself, not on the later fetch.
  auto jump = [&](uint32_t target) {
    if (target & 3) return false;
    next = target;
    return true;
  };

  switch (dec::opcode(insn)) {
    case 0x37:  // LUI
      wb = true; wb_val = dec::imm_u(insn);
      break;

    case 0x17:  // AUIPC
      wb = true; wb_val = pc_ + dec::imm_u(insn);
      break;

    case 0x6f:  // JAL
      if (!jump(pc_ + dec::imm_j(insn))) return trap(cause::kInsnMisaligned, pc_ + dec::imm_j(insn));
      wb = true; wb_val = pc_ + 4;
      break;

    case 0x67: {  // JALR: bit 0 of the target is cleared
      if (f3 != 0) return trap(cause::kIllegalInsn, insn);
      const uint32_t target = (a + dec::imm_i(insn)) & ~1u;
      if (!jump(target)) return trap(cause::kInsnMisaligned, target);
      wb = true; wb_val = pc_ + 4;
      break;
    }

    case 0x63: {  // BEQ BNE BLT BGE BLTU BGEU
      bool taken;
      switch (f3) {
        case 0: taken = a == b; break;
        case 1: taken = a != b; break;
        case 4: taken = static_cast<int32_t>(a) < static_cast<int32_t>(b); break;
        case 5: taken = static_cast<int32_t>(a) >= static_cast<int32_t>(b); break;
        case 6: taken = a < b; break;
        case 7: taken = a >= b; break;
        default: return trap(cause::kIllegalInsn, insn);
      }
      if (taken) {
        const uint32_t target = pc_ + dec::imm_b(insn);
        if (!jump(target)) return trap(cause::kInsnMisaligned, target);
      }
      break;
    }

    case 0x03: {  // LB LH LW LBU LHU
      const uint32_t addr = a + dec::imm_i(insn);
      unsigned size;
      bool sign;
      switch (f3) {
        case 0: size = 1; sign = true; break;
        case 1: size = 2; sign = true; break;
        case 2: size = 4; sign = false; break;
        case 4: size = 1; sign = false; break;
        case 5: size = 2; sign = false; break;
        default: return trap(cause::kIllegalInsn, insn);
      }
      uint32_t v;
      if (!mem_.load(addr, size, &v, &c)) return trap(c, addr);
      wb = true; wb_val = sign ? dec::sext(v, size * 8) : v;
      break;
    }

    case 0x23: {  // SB SH SW
      if (f3 > 2) return trap(cause::kIllegalInsn, insn);
      const uint32_t addr = a + dec::imm_s(insn);
      const unsigned size = 1u << f3;
      if (!mem_.store(addr, size, b, &c)) return trap(c, addr);
      st = true; st_addr = addr; st_size = size;
      st_val = (size == 4) ? b : (b & ((1u << (size * 8)) - 1));
      break;
    }

    case 0x13: {  // ADDI SLTI SLTIU XORI ORI ANDI SLLI SRLI SRAI
      const uint32_t imm = dec::imm_i(insn);
      const uint32_t sh = rs2;  // shamt is the rs2 field
      wb = true;
      switch (f3) {
        case 0: wb_val = a + imm; break;
        case 2: wb_val = static_cast<int32_t>(a) < static_cast<int32_t>(imm); break;
        case 3: wb_val = a < imm; break;
        case 4: wb_val = a ^ imm; break;
        case 6: wb_val = a | imm; break;
        case 7: wb_val = a & imm; break;
        case 1:
          if (f7 != 0x00) return trap(cause::kIllegalInsn, insn);
          wb_val = a << sh;
          break;
        case 5:
          if (f7 == 0x00) wb_val = a >> sh;
          else if (f7 == 0x20) wb_val = static_cast<uint32_t>(static_cast<int32_t>(a) >> sh);
          else return trap(cause::kIllegalInsn, insn);
          break;
      }
      break;
    }

    case 0x33: {  // register-register ALU
      const uint32_t sh = b & 31;
      wb = true;
      if (f7 == 0x00) {
        switch (f3) {
          case 0: wb_val = a + b; break;
          case 1: wb_val = a << sh; break;
          case 2: wb_val = static_cast<int32_t>(a) < static_cast<int32_t>(b); break;
          case 3: wb_val = a < b; break;
          case 4: wb_val = a ^ b; break;
          case 5: wb_val = a >> sh; break;
          case 6: wb_val = a | b; break;
          case 7: wb_val = a & b; break;
        }
      } else if (f7 == 0x01) {
        wb_val = exec_m(f3, a, b);
      } else if (f7 == 0x20 && f3 == 0) {
        wb_val = a - b;
      } else if (f7 == 0x20 && f3 == 5) {
        wb_val = static_cast<uint32_t>(static_cast<int32_t>(a) >> sh);
      } else {
        return trap(cause::kIllegalInsn, insn);
      }
      break;
    }

    case 0x0f:  // FENCE, FENCE.I: one hart, no caches here -> nothing to do
      if (f3 > 1) return trap(cause::kIllegalInsn, insn);
      break;

    case 0x2f: {  // A extension, word size only
      if (f3 != 2) return trap(cause::kIllegalInsn, insn);
      const uint32_t f5 = insn >> 27;  // bits 26:25 are aq/rl: no effect on one hart
      if (f5 == 0x02) {  // LR.W: load and reserve the address
        if (rs2 != 0) return trap(cause::kIllegalInsn, insn);
        uint32_t v;
        if (!mem_.load(a, 4, &v, &c)) return trap(c, a);
        reserved_ = true;
        reserved_addr_ = a;
        wb = true; wb_val = v;
      } else if (f5 == 0x03) {  // SC.W: store only if the reservation holds
        if (a & 3) return trap(cause::kStoreMisaligned, a);
        if (reserved_ && reserved_addr_ == a) {
          if (!mem_.store(a, 4, b, &c)) return trap(c, a);
          st = true; st_addr = a; st_size = 4; st_val = b;
          wb_val = 0;  // 0 = success
        } else {
          wb_val = 1;  // 1 = failure, memory unchanged
        }
        reserved_ = false;
        wb = true;
      } else {  // AMO*.W: read old value, write old OP rs2, return old value
        if (a & 3) return trap(cause::kStoreMisaligned, a);
        uint32_t old;
        if (!mem_.load(a, 4, &old, &c)) return trap(cause::kStoreAccessFault, a);
        const int32_t so = static_cast<int32_t>(old), sb = static_cast<int32_t>(b);
        uint32_t v;
        switch (f5) {
          case 0x01: v = b; break;                     // AMOSWAP
          case 0x00: v = old + b; break;               // AMOADD
          case 0x04: v = old ^ b; break;               // AMOXOR
          case 0x0c: v = old & b; break;               // AMOAND
          case 0x08: v = old | b; break;               // AMOOR
          case 0x10: v = so < sb ? old : b; break;     // AMOMIN
          case 0x14: v = so > sb ? old : b; break;     // AMOMAX
          case 0x18: v = old < b ? old : b; break;     // AMOMINU
          case 0x1c: v = old > b ? old : b; break;     // AMOMAXU
          default: return trap(cause::kIllegalInsn, insn);
        }
        if (!mem_.store(a, 4, v, &c)) return trap(c, a);
        st = true; st_addr = a; st_size = 4; st_val = v;
        wb = true; wb_val = old;
      }
      break;
    }

    case 0x73: {  // SYSTEM
      if (f3 == 0) {
        // ECALL, EBREAK, MRET and WFI are told apart by the whole word.
        switch (insn) {
          case 0x00000073: return trap(cause::kEcallFromM, 0);  // ECALL
          case 0x00100073: return trap(cause::kBreakpoint, pc_);  // EBREAK
          case 0x30200073:  // MRET: back to mepc, MIE <- MPIE, MPIE <- 1
            next = mepc_;
            mstatus_ = ((mstatus_ & kMstatusMpie) ? kMstatusMie : 0) | kMstatusMpie;
            break;
          case 0x10500073:  // WFI: no interrupts, so there is nothing to wait for
            break;
          default:
            return trap(cause::kIllegalInsn, insn);
        }
        break;
      }
      if (f3 == 4) return trap(cause::kIllegalInsn, insn);

      // Zicsr: CSRRW CSRRS CSRRC and the immediate forms CSRRWI CSRRSI CSRRCI.
      // The immediate forms use the rs1 field as a 5-bit unsigned value.
      // Set/clear with a zero source do not write, so they may read a
      // read-only CSR. None of our CSRs has read side effects, so it is fine
      // to always read, even for CSRRW with rd = x0.
      const uint32_t num = insn >> 20;
      const uint32_t src = (f3 & 4) ? rs1 : a;
      const uint32_t op = f3 & 3;  // 1: write, 2: set bits, 3: clear bits
      const bool write = (op == 1) || (rs1 != 0);
      uint32_t old;
      if (!csr_read(num, &old)) return trap(cause::kIllegalInsn, insn);
      if (write) {
        if ((num >> 10) == 3) return trap(cause::kIllegalInsn, insn);  // read-only CSR
        csr_write(num, op == 1 ? src : (op == 2 ? (old | src) : (old & ~src)));
      }
      wb = true; wb_val = old;
      break;
    }

    default:
      return trap(cause::kIllegalInsn, insn);
  }

  // Retire the instruction.
  if (wb && rd != 0) x_[rd] = wb_val;
  if (trace_) {
    std::fprintf(trace_, "%llu %08x %08x", static_cast<unsigned long long>(retired_), pc_, insn);
    if (wb && rd != 0) std::fprintf(trace_, " x%u=%08x", rd, wb_val);
    if (st) std::fprintf(trace_, " mem%u[%08x]=%0*x", st_size * 8, st_addr, st_size * 2, st_val);
    std::fputc('\n', trace_);
  }
  pc_ = next;
  ++retired_;
  if (!mcycle_written_) ++mcycle_;
  if (!minstret_written_) ++minstret_;
  mcycle_written_ = minstret_written_ = false;

  // Stop when the program writes a non-zero value to tohost.
  if (st && st_addr < tohost_ + 4 && st_addr + st_size > tohost_) {
    uint32_t v = 0;
    mem_.peek32(tohost_, &v);
    if (v != 0) {
      stop_.reason = StopInfo::Reason::kToHost;
      stop_.tohost_value = v;
      return false;
    }
  }
  return true;
}
