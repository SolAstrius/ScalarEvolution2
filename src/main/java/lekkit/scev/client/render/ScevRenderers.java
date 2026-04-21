/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render;

import lekkit.scev.client.render.blockentity.TinkerpadRenderer;
import lekkit.scev.client.render.blockentity.VT100Renderer;
import lekkit.scev.client.screen.ComputerCaseScreen;
import lekkit.scev.client.screen.MachineScreen;
import lekkit.scev.client.screen.McuBoardScreen;
import lekkit.scev.client.screen.MotherboardScreen;
import lekkit.scev.main.ScevRegistry;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public final class ScevRenderers {
    private ScevRenderers() {}

    public static void registerBlockEntityRenderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(ScevRegistry.VT100_BE.get(), ctx -> new VT100Renderer());
        e.registerBlockEntityRenderer(ScevRegistry.TINKERPAD_BE.get(), ctx -> new TinkerpadRenderer());
    }

    public static void registerMenuScreens(RegisterMenuScreensEvent e) {
        e.register(ScevRegistry.COMPUTER_CASE_MENU.get(), ComputerCaseScreen::new);
        e.register(ScevRegistry.MOTHERBOARD_MENU.get(), MotherboardScreen::new);
        e.register(ScevRegistry.MACHINE_MENU.get(), MachineScreen::new);
        e.register(ScevRegistry.MCU_BOARD_MENU.get(), McuBoardScreen::new);
    }
}
