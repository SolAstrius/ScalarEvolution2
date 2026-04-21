/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * The Scalar Evolution "hello world" firmware — a 64-byte RV32IM program that
 * toggles the FRONT GPIO pin every second, producing a 0.5 Hz square wave
 * (1 s on / 1 s off) on the block's front face.
 *
 * <p>Purpose is to verify the whole MCU pipeline end-to-end: librvvm can
 * boot a tiny flat binary at the reset vector, the SiFive GPIO MMIO reaches
 * the Minecraft world, and the per-tick bridge in
 * {@code ComputerCaseBlockEntity} translates pin-state to redstone.
 *
 * <h2>Boot shape</h2>
 *
 * <p>Single BOOTROM payload — the {@code blinky.bin} blob shipped under
 * {@code /assets/scev/firmware/}. Contains no OpenSBI, no kernel, no FDT
 * parsing. Just: setup the GPIO controller, spin on CLINT mtime, flip a
 * pin.
 *
 * <h2>RAM floor</h2>
 *
 * <p>The program itself is 64 bytes and touches no stack, no heap, no data
 * section. RVVM still requires page-aligned RAM (4 KiB minimum) and
 * auto-generates a ~1 KiB FDT at the top of RAM, so 1 MiB is the honest
 * smallest-workable floor — leaves room for the FDT and any MMIO scratch
 * without adjusting our existing minimum-memory helpers. The blinky itself
 * will happily fit in 4 KiB once the memory builder learns sub-MiB sizes.
 *
 * <h2>Cmdline</h2>
 *
 * <p>None. A bare-metal firmware has no kernel and no consumer for the
 * cmdline. Returning null skips the append step.
 *
 * <h2>Determinism</h2>
 *
 * <p>Stateless — {@link #INSTANCE} is a shared singleton, payloads list is
 * a static constant. Safe to share across machines per
 * {@link ScevFirmware}'s contract.
 */
public final class BareMetalBlinkyFirmware implements ScevFirmware {
    public static final BareMetalBlinkyFirmware INSTANCE = new BareMetalBlinkyFirmware();

    /** Classpath asset name for the blinky binary, under /assets/scev/firmware/. */
    public static final String BOOTROM_ASSET = "blinky.bin";

    private static final List<Payload> PAYLOADS =
            List.of(new Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET));

    private BareMetalBlinkyFirmware() {}

    @Override
    public List<Payload> payloads() { return PAYLOADS; }

    @Override
    public long minRamMb() { return 1; }

    @Override
    public Component displayName() { return Component.literal("Blinky (bare-metal demo)"); }
}
