/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import java.util.UUID
import lekkit.scev.items.MotherboardItem

/**
 * Server-side interface implemented by anything that owns a RISC-V machine
 * (block entity or item-backed laptop). The menu + packet pipeline dispatches
 * to this.
 */
interface IMachineHandle {
    fun getMachineUUID(): UUID
    fun isValid(): Boolean
    fun powerOn()
    fun powerOff()
    fun power()
    fun reset()
    fun isPowered(): Boolean

    fun getCaseSlotCount(): Int
    fun getMaxMotherboardLevel(): Int

    /**
     * The motherboard item type or null when there is no motherboard
     * (e.g. an MCU board, where the SoC stands in for the motherboard).
     * Screens / code branching on null gracefully degrade to "no motherboard".
     */
    fun getMotherboardItem(): MotherboardItem?
}
