/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware

import lekkit.scev.machine.firmware.ScevFirmware.Payload
import net.minecraft.network.chat.Component

/**
 * "Hello world" firmware — a 64-byte RV32IM program that toggles the FRONT
 * GPIO pin every second, producing a 0.5 Hz square wave (1 s on / 1 s off)
 * on the block's front face. Verifies the whole MCU pipeline end-to-end:
 * librvvm can boot a tiny flat binary at the reset vector, the SiFive GPIO
 * MMIO reaches the Minecraft world, and the per-tick bridge in
 * `ComputerCaseBlockEntity` translates pin state to redstone.
 *
 * Single BOOTROM payload, no OpenSBI, no kernel, no FDT consumption — just
 * set up the GPIO controller, spin on CLINT mtime, flip a pin.
 *
 * 1 MiB floor: the program itself is 64 bytes and touches no stack, heap,
 * or data section. RVVM requires page-aligned RAM (4 KiB minimum) and
 * auto-generates a ~1 KiB FDT at the top of RAM, so 1 MiB is the honest
 * smallest-workable floor — leaves room for the FDT and any MMIO scratch
 * without adjusting the existing minimum-memory helpers.
 */
object BareMetalBlinkyFirmware : ScevFirmware {
    const val BOOTROM_ASSET = "blinky.bin"

    private val PAYLOADS = listOf(Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET))

    override fun payloads(): List<Payload> = PAYLOADS
    override fun minRamMb(): Long = 1
    override fun displayName(): Component = Component.literal("Blinky (bare-metal demo)")
}
