/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.menu.MotherboardMenu;
import lekkit.scev.menu.SlotDef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Screen for the right-click-a-motherboard-in-hand inventory. Mirrors the
 * {@link ComputerCaseScreen} but without the motherboard slot or buttons —
 * the motherboard being edited IS the held item, so there's nothing to
 * install or power.
 *
 * <p>Background swaps by motherboard tier (1/2/3) so the player gets a
 * different-looking PCB for each; slot hints render on empty enabled slots.
 */
public class MotherboardScreen extends AbstractContainerScreen<MotherboardMenu> {

    private static final ResourceLocation BG_T1 =
            ScalarEvolution.rl("textures/gui/motherboard1.png");
    private static final ResourceLocation BG_T2 =
            ScalarEvolution.rl("textures/gui/motherboard2.png");
    private static final ResourceLocation BG_T3 =
            ScalarEvolution.rl("textures/gui/motherboard3.png");

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

        MotherboardItem mbItem = menu.getMotherboardItem();
        ResourceLocation bg = switch (mbItem == null ? 1 : mbItem.getLevel()) {
            case 2 -> BG_T2;
            case 3 -> BG_T3;
            default -> BG_T1; // tier-1 fallback even when mbItem is null so
                              // the screen doesn't render blank if the stack
                              // vanishes mid-session (stillValid closes it on
                              // the next tick).
        };
        // 256x256 PNG; GUI art in the top-left 176x222 region. See
        // ComputerCaseScreen.renderBg for why we pass 256 here instead of
        // imageWidth/imageHeight — otherwise the whole 256x256 gets
        // stretched into the 176x222 render area.
        g.blit(bg, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);

        if (mbItem != null) renderSlotHints(g, mbItem);
    }

    /** Overlay a hint sprite on each empty, enabled component slot. */
    private void renderSlotHints(GuiGraphics g, MotherboardItem mbItem) {
        List<SlotDef> defs = SlotDef.MOTHERBOARD;
        for (int i = 0; i < defs.size(); i++) {
            SlotDef def = defs.get(i);
            String hintKey = def.background();
            if (hintKey == null) continue;
            ResourceLocation sprite = SlotHints.spriteFor(hintKey);
            if (sprite == null) continue;

            Slot slot = menu.slots.get(i);
            if (slot.hasItem()) continue;
            if (!mbItem.isSlotEnabled(def.index())) continue;

            SlotHints.draw(g, sprite, leftPos + slot.x, topPos + slot.y);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
