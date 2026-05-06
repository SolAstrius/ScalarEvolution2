/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade

import lekkit.scev.main.ScalarEvolution
import net.minecraft.resources.ResourceLocation

/**
 * Provider UIDs exposed to Jade. Each `IJadeProvider#getUid()` returns
 * one of these — they surface in Jade's in-game config as toggle rows,
 * so the names are what players see when they disable a tooltip line.
 */
internal object ScevJadeIds {
    val COMPUTER_CASE: ResourceLocation = id("computer_case")
    val MCU_BOARD: ResourceLocation = id("mcu_board")
    val CRT_MONITOR: ResourceLocation = id("crt_monitor")
    val KEYBOARD: ResourceLocation = id("keyboard")
    val FLASH_PROGRAMMER: ResourceLocation = id("flash_programmer")
    val PROCESSING_MACHINE: ResourceLocation = id("processing_machine")

    private fun id(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, path)
}
