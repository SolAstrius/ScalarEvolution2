/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware

import lekkit.scev.machine.firmware.ScevFirmware.Payload
import net.minecraft.network.chat.Component

/**
 * OpenSBI + U-Boot (`fw_payload.bin`). Boots to a U-Boot shell on UART
 * with a 3 s autoboot countdown. "Power-user firmware" — manual boot
 * commands, NVMe scanning, TFTP, etc.
 *
 * Named after the [OpenFirmware](https://www.openfirmware.info/)
 * convention (Sun/Apple's IEEE 1275 bootloader) as a nod to the general
 * concept, not the specific implementation — different flash contents
 * give the chip different "boot personalities".
 */
object OpenFirmware : ScevFirmware {
    const val BOOTROM_ASSET = "fw_payload.bin"

    private val PAYLOADS = listOf(Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET))

    override fun payloads(): List<Payload> = PAYLOADS
    override fun displayName(): Component = Component.literal("OpenSBI + U-Boot")
}
