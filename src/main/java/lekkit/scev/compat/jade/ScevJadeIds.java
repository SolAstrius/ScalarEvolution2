/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.jade;

import lekkit.scev.main.ScalarEvolution;
import net.minecraft.resources.ResourceLocation;

/**
 * Provider UIDs exposed to Jade. Each {@code IJadeProvider#getUid()} returns
 * one of these — they surface in Jade's in-game config as toggle rows, so
 * the names are what players see when they disable a tooltip line.
 */
final class ScevJadeIds {
    private ScevJadeIds() {}

    static final ResourceLocation COMPUTER_CASE    = id("computer_case");
    static final ResourceLocation MCU_BOARD        = id("mcu_board");
    static final ResourceLocation VT100            = id("vt100");
    static final ResourceLocation CRT_MONITOR      = id("crt_monitor");
    static final ResourceLocation KEYBOARD         = id("keyboard");
    static final ResourceLocation FLASH_PROGRAMMER = id("flash_programmer");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, path);
    }
}
