/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.menu.MotherboardMenu;
import lekkit.scev.menu.SlotDef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class MotherboardScreen extends AbstractContainerScreen<MotherboardMenu> {
    private static final ResourceLocation BG =
            ScalarEvolution.rl("textures/gui/motherboard1.png");

    public MotherboardScreen(MotherboardMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = SlotDef.FAT_IMAGE_WIDTH;
        this.imageHeight = SlotDef.FAT_IMAGE_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 93;
        this.titleLabelY = 6;
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
