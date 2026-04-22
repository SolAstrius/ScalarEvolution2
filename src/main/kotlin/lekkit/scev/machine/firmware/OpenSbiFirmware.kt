/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware

import lekkit.scev.machine.firmware.ScevFirmware.Payload
import net.minecraft.network.chat.Component

/**
 * Bootrom-only firmware: just OpenSBI (`fw_jump.bin`). Hands off to S-mode
 * at 0x80200000; without something at that address (a kernel payload, a
 * bootable disk, etc) it traps on an illegal instruction.
 *
 * Building block for "supply the kernel separately" flows — a
 * [lekkit.scev.machine.MachineSpec.KernelSpec] with a user-authored
 * kernel, or U-Boot booting from an NVMe partition.
 */
object OpenSbiFirmware : ScevFirmware {
    const val BOOTROM_ASSET = "fw_jump.bin"

    private val PAYLOADS = listOf(Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET))

    override fun payloads(): List<Payload> = PAYLOADS
    override fun displayName(): Component = Component.literal("OpenSBI")
}
