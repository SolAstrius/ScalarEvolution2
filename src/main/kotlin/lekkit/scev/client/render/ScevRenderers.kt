/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render

import lekkit.scev.client.render.blockentity.TinkerpadRenderer
import lekkit.scev.client.render.blockentity.TerminalRenderer
import lekkit.scev.client.screen.ComputerCaseScreen
import lekkit.scev.client.screen.FlashProgrammerScreen
import lekkit.scev.client.screen.MachineScreen
import lekkit.scev.client.screen.McuBoardScreen
import lekkit.scev.client.screen.MotherboardScreen
import lekkit.scev.client.screen.DryerScreen
import lekkit.scev.client.screen.InkMixerScreen
import lekkit.scev.client.screen.PulperScreen
import lekkit.scev.client.screen.RibbonImpregnatorScreen
import lekkit.scev.client.screen.SheetFormerScreen
import lekkit.scev.client.screen.TeletypeScreen
import lekkit.scev.client.screen.TerminalScreen
import lekkit.scev.client.screen.WinderScreen
import lekkit.scev.main.ScevRegistry
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent

object ScevRenderers {
    @JvmStatic fun registerBlockEntityRenderers(e: EntityRenderersEvent.RegisterRenderers) {
        e.registerBlockEntityRenderer(ScevRegistry.TINKERPAD_BE.get()) { TinkerpadRenderer() }
        // VT100: live mlterm texture on the front face when
        // TerminalActiveHost is hosting the matching UUID. See
        // TerminalRenderer kdoc for the single-active-block model.
        e.registerBlockEntityRenderer(ScevRegistry.TERMINAL_BE.get()) { TerminalRenderer() }
    }

    @JvmStatic fun registerMenuScreens(e: RegisterMenuScreensEvent) {
        e.register(ScevRegistry.COMPUTER_CASE_MENU.get(),    ::ComputerCaseScreen)
        e.register(ScevRegistry.MOTHERBOARD_MENU.get(),      ::MotherboardScreen)
        e.register(ScevRegistry.MACHINE_MENU.get(),          ::MachineScreen)
        e.register(ScevRegistry.MCU_BOARD_MENU.get(),        ::McuBoardScreen)
        e.register(ScevRegistry.FLASH_PROGRAMMER_MENU.get(), ::FlashProgrammerScreen)
        e.register(ScevRegistry.TERMINAL_MENU.get(),            ::TerminalScreen)
        e.register(ScevRegistry.PULPER_MENU.get(),             ::PulperScreen)
        e.register(ScevRegistry.SHEET_FORMER_MENU.get(),       ::SheetFormerScreen)
        e.register(ScevRegistry.DRYER_MENU.get(),              ::DryerScreen)
        e.register(ScevRegistry.WINDER_MENU.get(),             ::WinderScreen)
        e.register(ScevRegistry.INK_MIXER_MENU.get(),          ::InkMixerScreen)
        e.register(ScevRegistry.RIBBON_IMPREGNATOR_MENU.get(), ::RibbonImpregnatorScreen)
        e.register(ScevRegistry.TELETYPE_MENU.get(),           ::TeletypeScreen)
    }
}
