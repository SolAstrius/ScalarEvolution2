#!/usr/bin/env python3
"""
Tiny self-contained RV32I assembler that emits the Scalar Evolution blinky
firmware as a flat binary.

Goal: produce a reproducible, auditable 64-byte blob without requiring a
full riscv-embedded gcc toolchain in CI or dev environments. The instruction
set we emit is a proper subset of RV32IM — hand-encoded per the unprivileged
spec (Chapter 26, base instruction formats). The source of truth for the
semantics lives in ``blinky.S`` next to this script; this file exists so
``./assets/scev/firmware/blinky.bin`` can be regenerated from text in one
``python3`` invocation with no toolchain at all.

Run:
    python3 assemble_blinky.py > ../blinky.bin

Or, from the repo root:
    python3 src/main/resources/assets/scev/firmware/src/assemble_blinky.py \\
        > src/main/resources/assets/scev/firmware/blinky.bin
"""

import struct
import sys


# Register numbers for the handful of registers we touch.
ZERO = 0
T0, T1, T2 = 5, 6, 7
T3, T4, T5, T6 = 28, 29, 30, 31


def lui(rd: int, imm20: int) -> int:
    """U-type: imm[31:12] | rd | 0b0110111 (opcode 0x37)."""
    assert 0 <= imm20 < (1 << 20)
    return (imm20 << 12) | (rd << 7) | 0b0110111


def addi(rd: int, rs1: int, imm12: int) -> int:
    """I-type ADDI: imm[11:0] | rs1 | 000 | rd | 0b0010011."""
    imm = imm12 & 0xFFF
    return (imm << 20) | (rs1 << 15) | (0b000 << 12) | (rd << 7) | 0b0010011


def sw(rs2: int, rs1: int, imm12: int) -> int:
    """S-type SW: imm[11:5] | rs2 | rs1 | 010 | imm[4:0] | 0b0100011."""
    imm = imm12 & 0xFFF
    imm_hi = (imm >> 5) & 0x7F
    imm_lo = imm & 0x1F
    return (imm_hi << 25) | (rs2 << 20) | (rs1 << 15) | (0b010 << 12) | (imm_lo << 7) | 0b0100011


def lw(rd: int, rs1: int, imm12: int) -> int:
    """I-type LW: imm[11:0] | rs1 | 010 | rd | 0b0000011."""
    imm = imm12 & 0xFFF
    return (imm << 20) | (rs1 << 15) | (0b010 << 12) | (rd << 7) | 0b0000011


def xor_(rd: int, rs1: int, rs2: int) -> int:
    """R-type XOR: funct7=0 | rs2 | rs1 | 100 | rd | 0b0110011."""
    return (0 << 25) | (rs2 << 20) | (rs1 << 15) | (0b100 << 12) | (rd << 7) | 0b0110011


def sub_(rd: int, rs1: int, rs2: int) -> int:
    """R-type SUB: funct7=0x20 | rs2 | rs1 | 000 | rd | 0b0110011."""
    return (0x20 << 25) | (rs2 << 20) | (rs1 << 15) | (0b000 << 12) | (rd << 7) | 0b0110011


def bne(rs1: int, rs2: int, offset: int) -> int:
    """B-type BNE: imm[12|10:5] | rs2 | rs1 | 001 | imm[4:1|11] | 0b1100011."""
    return _btype(rs1, rs2, offset, funct3=0b001)


def bltu(rs1: int, rs2: int, offset: int) -> int:
    """B-type BLTU: imm[12|10:5] | rs2 | rs1 | 110 | imm[4:1|11] | 0b1100011.

    Branch taken when (unsigned) rs1 < rs2. Used for timer-elapsed comparisons
    where the subtraction overflow behavior makes modular arithmetic correct.
    """
    return _btype(rs1, rs2, offset, funct3=0b110)


def _btype(rs1: int, rs2: int, offset: int, funct3: int) -> int:
    """Shared B-type encoder.

    ``offset`` is in bytes, measured from the PC of this instruction.
    Must be even and in the signed 13-bit range [-4096, +4094].
    """
    assert offset % 2 == 0
    assert -4096 <= offset <= 4094
    imm = offset & 0x1FFF
    b12 = (imm >> 12) & 0x1
    b11 = (imm >> 11) & 0x1
    b10_5 = (imm >> 5) & 0x3F
    b4_1 = (imm >> 1) & 0xF
    imm_hi = (b12 << 6) | b10_5
    imm_lo = (b4_1 << 1) | b11
    return (imm_hi << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (imm_lo << 7) | 0b1100011


def jal(rd: int, offset: int) -> int:
    """J-type JAL: imm[20|10:1|11|19:12] | rd | 0b1101111.

    ``offset`` is in bytes, relative to PC of this instruction.
    Must be even and in the signed 21-bit range [-1048576, +1048574].
    """
    assert offset % 2 == 0
    assert -(1 << 20) <= offset <= ((1 << 20) - 2)
    imm = offset & 0x1FFFFF
    b20 = (imm >> 20) & 0x1
    b19_12 = (imm >> 12) & 0xFF
    b11 = (imm >> 11) & 0x1
    b10_1 = (imm >> 1) & 0x3FF
    imm_packed = (b20 << 19) | (b10_1 << 9) | (b11 << 8) | b19_12
    return (imm_packed << 12) | (rd << 7) | 0b1101111


def assemble() -> bytes:
    """Build the blinky blob.

    Layout (byte offsets are also PC offsets from 0x80000000):

    ::

        0x00  lui   t0, 0x10060        ; GPIO base
        0x04  addi  t1, zero, 0x3F     ; pin mask
        0x08  sw    t1, 0x04(t0)       ; INPUT_EN  = 0x3F
        0x0C  sw    t1, 0x08(t0)       ; OUTPUT_EN = 0x3F
        0x10  addi  t2, zero, 1        ; FRONT pin mask
        0x14  addi  t1, zero, 0        ; OUTPUT mirror
        0x18  lui   t4, 0x0200C        ; CLINT mtime base + 8
        0x1C  lui   t6, 0x989          ; 10M ≈ 0x989000
        0x20  addi  t6, t6, 0x680      ; t6 = 10_000_000 (1s @ 10MHz)

        0x24  .toggle:
        0x24  xor   t1, t1, t2
        0x28  sw    t1, 0x0C(t0)       ; publish FRONT to GPIO
        0x2C  lw    t5, -8(t4)         ; start = mtime_lo
        0x30  .wait:
        0x30  lw    t3, -8(t4)         ; now = mtime_lo
        0x34  sub   t3, t3, t5         ; elapsed = now - start
        0x38  bltu  t3, t6, .wait      ; -8 bytes (loop while elapsed < target)
        0x3C  jal   zero, .toggle      ; -24 bytes

    Total: 16 instructions × 4 bytes = 64 bytes.
    """
    insns = [
        lui(T0, 0x10060),       # 0x00
        addi(T1, ZERO, 0x3F),   # 0x04
        sw(T1, T0, 0x04),       # 0x08
        sw(T1, T0, 0x08),       # 0x0C
        addi(T2, ZERO, 1),      # 0x10
        addi(T1, ZERO, 0),      # 0x14
        lui(T4, 0x0200C),       # 0x18
        lui(T6, 0x989),         # 0x1C
        addi(T6, T6, 0x680),    # 0x20  t6 = 10_000_000

        xor_(T1, T1, T2),       # 0x24  .toggle
        sw(T1, T0, 0x0C),       # 0x28
        lw(T5, T4, -8),         # 0x2C  start = mtime_lo

        lw(T3, T4, -8),         # 0x30  .wait  now = mtime_lo
        sub_(T3, T3, T5),       # 0x34  elapsed
        bltu(T3, T6, -8),       # 0x38
        jal(ZERO, -24),         # 0x3C  j .toggle
    ]
    buf = b"".join(struct.pack("<I", w) for w in insns)
    assert len(buf) == 64
    return buf


def main() -> int:
    sys.stdout.buffer.write(assemble())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
