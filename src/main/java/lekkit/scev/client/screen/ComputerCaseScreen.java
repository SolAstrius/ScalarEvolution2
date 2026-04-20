/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.menu.ComputerCaseMenu;
import lekkit.scev.menu.SlotDef;
import lekkit.scev.network.MachineResetPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Screen for the computer case's component editor (motherboard + 14 components
 * + player inventory). Two buttons on the side:
 * <ul>
 *   <li><b>Power</b> — toggles the machine on/off. Triggers
 *       {@link lekkit.scev.blockentity.ComputerCaseBlockEntity#buildMachine}
 *       on first press so the installed components get picked up.</li>
 *   <li><b>Reset</b> — if powered, sends a CPU reset to the VM.</li>
 * </ul>
 */
public class ComputerCaseScreen extends AbstractContainerScreen<ComputerCaseMenu> {
    private static final ResourceLocation BG =
            ScalarEvolution.rl("textures/gui/computer_case_empty.png");

    public ComputerCaseScreen(ComputerCaseMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = SlotDef.FAT_IMAGE_WIDTH;
        this.imageHeight = SlotDef.FAT_IMAGE_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 93;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // Power button: top-right of the case slot column.
        int btnX = leftPos + 136;
        int btnY = topPos + 18;
        addRenderableWidget(Button.builder(Component.translatable("button.scev.power"),
                b -> PacketDistributor.sendToServer(new MachineResetPayload(false)))
                .bounds(btnX, btnY, 32, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("tooltip.scev.power")))
                .build());
        // Reset button, just below.
        addRenderableWidget(Button.builder(Component.translatable("button.scev.reset"),
                b -> PacketDistributor.sendToServer(new MachineResetPayload(true)))
                .bounds(btnX, btnY + 22, 32, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("tooltip.scev.reset")))
                .build());
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
