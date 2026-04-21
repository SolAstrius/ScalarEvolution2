/*
 * blinky.S — Scalar Evolution bare-metal RV32IM demo firmware.
 *
 * Toggles the FRONT GPIO pin (block-relative bit 0) once per second,
 * giving a visible 0.5 Hz square wave (1 s on / 1 s off). Timing comes
 * from RVVM's CLINT mtime counter so the rate is independent of host
 * CPU speed or JIT behavior.
 *
 * Memory layout at reset (per RVVM):
 *   a0 (x10) = hartid (ignored — only hart 0 runs this code)
 *   a1 (x11) = FDT address (ignored)
 *   PC       = 0x80000000 (linked here via -Ttext)
 *
 * SiFive GPIO register map (RVVM default base 0x10060000):
 *   +0x00  INPUT       masked pin read
 *   +0x04  INPUT_EN
 *   +0x08  OUTPUT_EN
 *   +0x0C  OUTPUT      bits driven when OUTPUT_EN bit is set
 *
 * CLINT mtime (RVVM default base 0x02000000, mtimer subregion at +0x4000):
 *   0x0200BFF8  mtime low 32 bits
 *   0x0200BFFC  mtime high 32 bits (unused — 10M counts/s fits in 32 bits
 *               for 429 s before wrap, and our 1 s delay is well under the
 *               2^32 safe delta ceiling).
 *
 * Pin 0 is FRONT — see lekkit.scev.machine.GpioPinMap. This firmware's one
 * job is to prove the whole pipeline works: librvvm loads a flat binary at
 * the reset vector, the CPU runs it, the SiFive GPIO MMIO reaches the
 * Minecraft world via the per-tick bridge in ComputerCaseBlockEntity.
 *
 * Assembled as flat binary, 64 bytes. No ELF, no crt0 — loadBootrom places
 * these bytes at 0x80000000 and bin_objcopy raw-reads them on reset.
 */

.option  norvc                /* no compressed instructions; keep rv32im decode trivial */
.section .text.init, "ax"
.globl   _start

_start:
    lui     t0, 0x10060       /* t0 = SiFive GPIO base (0x10060000) */
    li      t1, 0x3F          /* enable low 6 pins */
    sw      t1, 0x04(t0)      /* INPUT_EN  = 0x3F */
    sw      t1, 0x08(t0)      /* OUTPUT_EN = 0x3F */
    li      t2, 1             /* FRONT pin mask */
    mv      t1, zero          /* OUTPUT mirror starts cleared */

    /*
     * t4 holds a base address 8 bytes past mtime_lo so the mtime load can
     * use a -8(t4) displacement — mtime's native address (0x0200BFF8) has
     * too many low bits to fit in lw's 12-bit signed immediate off of a
     * single LUI. Rolling the base up to 0x0200C000 puts the offset back
     * in range. Same trick used by OpenSBI's boot assembly.
     */
    lui     t4, 0x0200C       /* t4 = 0x0200C000 — CLINT mtime at -8(t4) */

    /*
     * t6 = 10_000_000 = 0x00989680 = one second at RVVM's 10 MHz timer.
     * LUI loads 0x00989000; ADDI sign-extends 0x680 (positive, no carry
     * correction needed because bit 11 of 0x680 is 0).
     */
    lui     t6, 0x989
    addi    t6, t6, 0x680     /* t6 = 1s worth of mtime ticks */

.toggle:
    xor     t1, t1, t2
    sw      t1, 0x0C(t0)      /* flip FRONT pin on the bus */

    lw      t5, -8(t4)        /* t5 = mtime_lo at start of wait */
.wait:
    lw      t3, -8(t4)        /* t3 = mtime_lo now */
    sub     t3, t3, t5        /* elapsed = now - start (mod 2^32, correct) */
    bltu    t3, t6, .wait     /* keep spinning while elapsed < 10M */

    j       .toggle
