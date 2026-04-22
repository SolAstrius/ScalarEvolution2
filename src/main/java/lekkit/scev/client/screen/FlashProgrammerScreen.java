/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import lekkit.scev.client.screen.widget.IconButton;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.menu.FlashProgrammerMenu;
import lekkit.scev.network.FlashProgrammerWritePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Screen for the flash programmer. Two slots (source NVMe, target flash)
 * + a Write button in the middle area + a status line that reflects the
 * last operation's outcome.
 *
 * <p>Reuses the {@link McuBoardScreen}-style frame so the programmer feels
 * like a cousin to the other SCEv computer-family GUIs.
 */
public class FlashProgrammerScreen extends AbstractContainerScreen<FlashProgrammerMenu> {
    private static final ResourceLocation BG =
            ScalarEvolution.rl("textures/gui/flash_programmer.png");

    /** Shared with other SCEv screens — the power-button sprite sheet. For
     *  the programmer's "Write" button we re-use it because the three-frame
     *  active/idle/pressed pattern is exactly what we need. Swap to a
     *  dedicated write-button texture in a polish PR. */
    private static final ResourceLocation BUTTON_TEX =
            ScalarEvolution.rl("textures/gui/widget/power_button.png");

    private static final int IMAGE_WIDTH  = 176;
    private static final int IMAGE_HEIGHT = 148;

    /** Write button position — same grey gap as MCU's power button. */
    private static final int BUTTON_SIZE = 12;
    private static final int BUTTON_X    = 82;
    private static final int BUTTON_Y    = 53;

    /** Status line shows the last write outcome until faded. */
    private static final long STATUS_FADE_MS = 2500L;

    /** Lang key colors. OK = green, everything else = red. */
    private static final ChatFormatting COLOR_OK   = ChatFormatting.GREEN;
    private static final ChatFormatting COLOR_FAIL = ChatFormatting.RED;

    private @Nullable Component statusMessage;
    private long statusEndMs;
    private ChatFormatting statusColor = COLOR_OK;

    /** Last-observed DataSlot value — used to detect new status pushes. */
    private FlashProgrammerMenu.WriteStatus lastObserved = FlashProgrammerMenu.WriteStatus.IDLE;

    public FlashProgrammerScreen(FlashProgrammerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth  = IMAGE_WIDTH;
        this.imageHeight = IMAGE_HEIGHT;
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Frame has its own visual identity; don't overlay "Flash Programmer" and
        // "Inventory" labels onto it.
    }

    @Override
    protected void init() {
        super.init();
        IconButton writeBtn = new IconButton(
                leftPos + BUTTON_X, topPos + BUTTON_Y,
                BUTTON_SIZE, BUTTON_SIZE,
                BUTTON_TEX, 42, 14,
                15, 1,    // idle
                 1, 1,    // hover (same frame as the power-on "active" — reads as "armed")
                29, 1,    // pressed
                Component.translatable("button.scev.programmer.write"),
                this::onWriteClicked);
        writeBtn.setTooltip(Tooltip.create(
                Component.translatable("tooltip.scev.programmer.write")));
        addRenderableWidget(writeBtn);
    }

    private void onWriteClicked(IconButton btn) {
        PacketDistributor.sendToServer(FlashProgrammerWritePayload.INSTANCE);
        // Status pops in when the DataSlot updates; no optimistic UI here.
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // Poll the DataSlot for a new status push. When it changes, stash
        // a timestamped message to display for a couple of seconds.
        FlashProgrammerMenu.WriteStatus now = menu.lastStatus();
        if (now != lastObserved && now != FlashProgrammerMenu.WriteStatus.IDLE) {
            lastObserved = now;
            statusMessage = Component.translatable(now.langKey());
            statusColor = now == FlashProgrammerMenu.WriteStatus.OK ? COLOR_OK : COLOR_FAIL;
            statusEndMs = System.currentTimeMillis() + STATUS_FADE_MS;

            if (now != FlashProgrammerMenu.WriteStatus.OK) {
                // Error beep on fail — same "nope" note the power button uses.
                Minecraft mc = Minecraft.getInstance();
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.8F));
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        renderStatus(g);
        renderTooltip(g, mouseX, mouseY);
    }

    private void renderStatus(GuiGraphics g) {
        if (statusMessage == null) return;
        long remaining = statusEndMs - System.currentTimeMillis();
        if (remaining <= 0) {
            statusMessage = null;
            return;
        }
        float alpha = remaining > 300 ? 1.0F : remaining / 300.0F;
        int alphaByte = (int) (alpha * 0xFF);

        String text = statusMessage.getString();
        int textWidth = font.width(text);
        int padX = 6, padY = 3;
        int cx = this.width / 2;
        int y = topPos - (9 + padY * 2) - 6;

        int bgLeft   = cx - textWidth / 2 - padX;
        int bgRight  = cx + textWidth / 2 + padX;
        int bgTop    = y;
        int bgBottom = y + 9 + padY * 2;

        int bgAlpha = Math.min(alphaByte, 0xC0);
        g.fill(bgLeft, bgTop, bgRight, bgBottom, (bgAlpha << 24));
        int borderRgb = statusColor == COLOR_OK ? 0x40FF40 : 0xFF3030;
        int border = borderRgb | (alphaByte << 24);
        g.fill(bgLeft,      bgTop,        bgRight,     bgTop + 1,  border);
        g.fill(bgLeft,      bgBottom - 1, bgRight,     bgBottom,   border);
        g.fill(bgLeft,      bgTop,        bgLeft + 1,  bgBottom,   border);
        g.fill(bgRight - 1, bgTop,        bgRight,     bgBottom,   border);

        int textRgb = statusColor == COLOR_OK ? 0x60FF60 : 0xFF5050;
        int textColor = textRgb | (alphaByte << 24);
        g.drawString(font, text, cx - textWidth / 2, bgTop + padY, textColor, true);
    }
}
