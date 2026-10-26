package rvsc

import chisel3._
import chisel3.util._

// Byte placement for stores and loads.
//   StoreAlign  MEM stage: copies the store data to every byte lane, makes wstrb
//   LoadAlign   WB stage:  picks the bytes of the loaded word, extends them
// Both are combinational (no registers).
//
// LEGAL INPUTS ONLY. Both modules assume:
//   - funct3 is a real load / store: the decoder marks other funct3 values
//     illegal, so memRead / memWrite = 0 and the outputs are never used.
//   - the address is aligned: half at addrLow 0 or 2, word at addrLow 0.
//     A misaligned address is caught in EX and no request is sent.
// For other inputs any output is fine; just give every output a value.
//
// Memory is little-endian: byte k of a word is at address (word address + k)
// and sits in bits 8k+7 .. 8k. addrLow = address(1, 0) picks k.
// The full address goes from ALUOutM straight to dmem.req.bits.addr; DMem
// uses address(.., 2) to pick the word.

// MEM stage. Copies the data to every lane (plan B) and enables only the
// bytes to write: DMem writes the bytes whose wstrb bit is 1.
//
//   size (funct3(1,0))  wdata                       wstrb
//   00 byte  sb         storeData(7, 0) x 4          0001 << addrLow
//   01 half  sh         storeData(15, 0) x 2         0011 << addrLow
//   10 word  sw         storeData                    1111
//
//   e.g. storeData = 0x11223344:
//   sb, addrLow 2 -> wdata 0x44444444, wstrb 0100
//   sh, addrLow 2 -> wdata 0x33443344, wstrb 1100
class StoreAlign extends Module {
  val io = IO(new Bundle {
    val addrLow   = Input(UInt(2.W))               // ALUOutM(1, 0)
    val funct3    = Input(UInt(3.W))               // only funct3(1, 0) (size) is used
    val storeData = Input(UInt(Consts.XLEN.W))     // rs2 after forwarding (StoreDataM)
    val wdata     = Output(UInt(Consts.XLEN.W))    // to dmem.req.bits.wdata
    val wstrb     = Output(UInt(4.W))              // to dmem.req.bits.wstrb
  })

  val size = io.funct3(1, 0)

  // Defaults: size 11 never comes. wstrb = 0 writes no byte, so it is safe.
  io.wdata := io.storeData
  io.wstrb := 0.U

  switch(size) {
    is("b00".U) {                                  // sb: one byte, any addrLow
      val byte = io.storeData(7, 0)
      io.wdata := Fill(4, byte)                    // the byte in every lane
      io.wstrb := 1.U << io.addrLow                // 0001, 0010, 0100 or 1000
    }
    is("b01".U) {                                  // sh: two bytes, addrLow 0 or 2
      val half = io.storeData(15, 0)
      io.wdata := Fill(2, half)                    // the half in both halves
      io.wstrb := Mux(io.addrLow(1), "b1100".U, "b0011".U)
    }
    is("b10".U) {                                  // sw: the whole word, addrLow 0
      io.wdata := io.storeData
      io.wstrb := "b1111".U
    }
  }
}

// WB stage. DMem returns the whole word; take the wanted bytes and fill the
// upper bits: with the sign bit for lb / lh, with 0 for lbu / lhu.
//
//   funct3  load  result
//   000     lb    shifted(7, 0)  sign-extended
//   001     lh    shifted(15, 0) sign-extended
//   010     lw    rdata
//   100     lbu   shifted(7, 0)  zero-extended
//   101     lhu   shifted(15, 0) zero-extended
//   where shifted = rdata >> (addrLow * 8)
//
//   e.g. rdata = 0x8899AABB:
//   lb  addrLow 2 -> 0x99   -> 0xFFFFFF99
//   lbu addrLow 2 -> 0x99   -> 0x00000099
//   lh  addrLow 0 -> 0xAABB -> 0xFFFFAABB
class LoadAlign extends Module {
  val io = IO(new Bundle {
    val addrLow  = Input(UInt(2.W))                // address(1, 0), kept in MEM/WB
    val funct3   = Input(UInt(3.W))                // funct3(1, 0) = size, funct3(2) = unsigned
    val rdata    = Input(UInt(Consts.XLEN.W))      // dmem.resp.bits.rdata, the whole word
    val loadData = Output(UInt(Consts.XLEN.W))     // LoadDataW, to the WB result mux
  })

  val size     = io.funct3(1, 0)
  val unsigned = io.funct3(2)                       // lbu, lhu

  // Move the wanted bytes down to bit 0: shift right by addrLow * 8.
  val shifted = io.rdata >> Cat(io.addrLow, 0.U(3.W))
  val byte    = shifted(7, 0)
  val half    = shifted(15, 0)

  // Default: the whole word (lw). funct3 011 / 110 / 111 never come.
  io.loadData := io.rdata

  switch(size) {
    is("b00".U) {                                   // lb, lbu
      io.loadData := Mux(unsigned, Cat(0.U(24.W), byte), Cat(Fill(24, byte(7)), byte))
    }
    is("b01".U) {                                   // lh, lhu
      io.loadData := Mux(unsigned, Cat(0.U(16.W), half), Cat(Fill(16, half(15)), half))
    }
  }
}
