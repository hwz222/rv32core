// One RISC-V hart (hardware thread): registers, pc, CSRs, and the
// fetch-decode-execute loop.
//
// ISA: RV32IMA + Zicsr + Zifencei, machine mode only, no interrupts
// (the bare-metal workloads never need them).
#pragma once

#include <cstdint>
#include <cstdio>

#include "memory.h"

struct StopInfo {
  enum class Reason { kToHost, kTrap, kMaxInsns };
  Reason reason = Reason::kMaxInsns;
  uint32_t tohost_value = 0;  // kToHost: the non-zero value written to tohost
  uint32_t trap_cause = 0;    // kTrap: a trap with no handler (mtvec == 0)
  uint32_t trap_pc = 0;
  uint32_t trap_tval = 0;
};

class Hart {
 public:
  Hart(Memory& mem, uint32_t entry_pc, uint32_t tohost_addr)
      : mem_(mem), pc_(entry_pc), tohost_(tohost_addr) {}

  // Optional trace: one line per retired instruction (see README.md).
  void set_trace(FILE* f) { trace_ = f; }

  // Runs until the program writes tohost, takes a trap with no handler, or
  // has done max_steps steps (retired instructions plus traps).
  StopInfo run(uint64_t max_steps);

  // Instructions retired so far. Program writes to minstret do not change it.
  uint64_t retired() const { return retired_; }

 private:
  bool step();  // executes one instruction; false means stop
  bool trap(uint32_t cause, uint32_t tval);
  bool csr_read(uint32_t addr, uint32_t* value) const;  // false: no such CSR
  void csr_write(uint32_t addr, uint32_t value);

  Memory& mem_;
  uint32_t x_[32] = {};
  uint32_t pc_;
  const uint32_t tohost_;
  uint64_t retired_ = 0;
  uint64_t steps_ = 0;
  FILE* trace_ = nullptr;
  StopInfo stop_;

  // LR/SC: the address reserved by the last LR.W, if any.
  bool reserved_ = false;
  uint32_t reserved_addr_ = 0;

  // Machine-mode CSRs. mstatus keeps only MIE and MPIE; MPP always reads as
  // M-mode because M is the only privilege level.
  uint32_t mstatus_ = 0;
  uint32_t mtvec_ = 0;
  uint32_t mepc_ = 0;
  uint32_t mcause_ = 0;
  uint32_t mtval_ = 0;
  uint32_t mscratch_ = 0;
  // Counters. There is no timing in an ISA simulator, so one retired
  // instruction counts as one cycle. The program may write them.
  uint64_t mcycle_ = 0;
  uint64_t minstret_ = 0;
  bool mcycle_written_ = false;    // set when the current instruction wrote
  bool minstret_written_ = false;  // the counter, so it is not also counted
};
