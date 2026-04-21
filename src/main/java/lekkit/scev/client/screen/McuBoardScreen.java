/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import lekkit.scev.blockentity.McuBoardBlockEntity;
import lekkit.scev.client.screen.widget.IconButton;
import lekkit.scev.items.FlashFirmware;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.SocItem;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.main.ScevDataComponents;
import lekkit.scev.menu.McuBoardMenu;
import lekkit.scev.network.MachineResetPayload;
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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The one MCU screen — install slots + live readout + power button on one
 * panel. Shift+right-click is a no-op on the block; plain right-click always
 * opens this screen.
 *
 * <p>Layout carries through from the drone-style {@code mcu_board.png}
 * the user recolored into the SCEv palette: black console panel top-left,
 * two slots top-right, GPIO LED strip in the middle, player inventory at
 * the bottom. Power button lives on the sidebar to the left of the panel
 * to match the rest of the machine family.
 */
public class McuBoardScreen extends AbstractContainerScreen<McuBoardMenu> {
    private static final ResourceLocation BG =
            ScalarEvolution.rl("textures/gui/mcu_board.png");

    private static final ResourceLocation POWER_BUTTON_TEX =
            ScalarEvolution.rl("textures/gui/widget/power_button.png");

    /** Drone-style panel extent — matches the PNG's opaque region. */
    private static final int IMAGE_WIDTH  = 176;
    private static final int IMAGE_HEIGHT = 148;

    /**
     * Power button placement inside the panel — the 20-px grey gap on
     * the left of the middle strip (x=[8..27]). Centered on the strip's
     * vertical center (y=53) for a visual lineup with the GPIO LED row.
     */
    private static final int POWER_BUTTON_SIZE = 12;
    private static final int POWER_BUTTON_X = 18 - POWER_BUTTON_SIZE / 2;  // = 12
    private static final int POWER_BUTTON_Y = 53 - POWER_BUTTON_SIZE / 2;  // = 47

    /** Black console area (paint live state here). */
    private static final int CONSOLE_X = 9;
    private static final int CONSOLE_Y = 9;
    private static final int CONSOLE_INNER_PAD = 2;

    /**
     * Middle black strip geometry — GPIO LED row centered inside it.
     *
     * <p>Measured off the PNG via PIL: the <i>interior</i> black run on
     * the middle band occupies x=[28..167], y=[48..59]. Centers (x=97,
     * y=53) drive the placement math below. We take the whole 6-pin GPIO
     * state as one row of LEDs — with our current single-value readPins
     * API there's no meaningful input-vs-output split to visualize, and
     * one row per face is cleaner than two redundant rows.
     *
     * <p>LED chosen at 8×8 with 4-px gaps (6*8 + 5*4 = 68 px row width),
     * comfortably inside the 140-px strip and visually matching the
     * slot bevel next to it.
     */
    private static final int LED_STRIP_CENTER_X = 97;
    private static final int LED_STRIP_CENTER_Y = 53;
    private static final int LED_SIZE  = 8;
    private static final int LED_GAP   = 4;
    private static final int LED_COUNT = 6;

    private static final int LED_ON_RGB   = 0xFFFF4040;
    private static final int LED_OFF_RGB  = 0xFF2A2A2A;
    private static final int LED_FRAME    = 0xFF606060;

    private static final int TEXT_COLOR = 0xFF33FF33;  // green-on-black terminal
    private static final int TEXT_DIM   = 0xFF1F8F1F;

    private static final long FAIL_MESSAGE_DURATION_MS = 2500L;

    private @Nullable Component failMessage;
    private long failMessageEndMs;

    public McuBoardScreen(McuBoardMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth  = IMAGE_WIDTH;
        this.imageHeight = IMAGE_HEIGHT;
    }

    /**
     * The default labels overlay "MCU Board" onto the console panel and
     * "Inventory" onto the GPIO LED row. Neither looks good here — the
     * custom console readout carries its own visual identity, and the
     * player knows their own inventory when they see it. Skip both.
     */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Intentionally empty — see javadoc.
    }

    @Override
    protected void init() {
        super.init();
        IconButton powerBtn = new IconButton(
                leftPos + POWER_BUTTON_X, topPos + POWER_BUTTON_Y,
                POWER_BUTTON_SIZE, POWER_BUTTON_SIZE,
                POWER_BUTTON_TEX, 42, 14,
                15, 1,    // idle (off)
                 1, 1,    // hover (off) — active frame
                29, 1,    // pressed
                Component.translatable("button.scev.power"),
                this::onPowerClicked)
                .withToggle(menu::isMachinePowered,
                         1, 1,    // idle (on) — active frame
                         1, 1);   // hover (on)
        powerBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.scev.power")));
        addRenderableWidget(powerBtn);
    }

    private void onPowerClicked(IconButton btn) {
        if (menu.isMachinePowered()) {
            PacketDistributor.sendToServer(new MachineResetPayload(false));
            return;
        }

        McuBoardMenu.ValidationResult r = menu.validateForPower();
        if (r == McuBoardMenu.ValidationResult.OK) {
            PacketDistributor.sendToServer(new MachineResetPayload(false));
            return;
        }

        btn.flashFail(500);
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(
                SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.8F));
        failMessage = Component.translatable(r.langKey()).withStyle(ChatFormatting.RED);
        failMessageEndMs = System.currentTimeMillis() + FAIL_MESSAGE_DURATION_MS;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
        drawConsoleText(g);
        drawGpioLeds(g);
    }

    /**
     * Live machine readout, one line per fact.
     *
     * <p>Drawn at 0.5× font scale — MC's standard 9-px font is too wide
     * for the ~86-px console panel (a 20-char line would overflow). Half
     * scale yields ~40 cols × ~7 rows of legible terminal-feel text,
     * matching the density of real LCD character modules, and leaves
     * room beneath the 3 status lines for future UART output.
     */
    private void drawConsoleText(GuiGraphics g) {
        // At 0.5× scale, 1 world-px = 0.5 screen-px. To position text so it
        // lands at a given screen coord, pass coords scaled by 2× in pose
        // space. pushPose/popPose keeps the scaling local to this draw.
        var pose = g.pose();
        pose.pushPose();
        pose.scale(0.5f, 0.5f, 1.0f);
        int x = (leftPos + CONSOLE_X + CONSOLE_INNER_PAD) * 2;
        int y = (topPos + CONSOLE_Y + CONSOLE_INNER_PAD) * 2;
        final int lineHeight = 10;  // 5 px on screen — tight but readable
        int line = 0;

        boolean on = menu.isMachinePowered();
        g.drawString(font, on ? "running" : "halted",
                x, y + line++ * lineHeight,
                on ? TEXT_COLOR : TEXT_DIM, false);

        ItemStack flash = menu.getMcu().getItem(McuBoardBlockEntity.SLOT_FLASH);
        String fwLabel;
        if (flash.isEmpty() || !(flash.getItem() instanceof FlashItem)) {
            fwLabel = "fw: -";
        } else if (flash.has(ScevDataComponents.FIRMWARE_BYTES.get())) {
            fwLabel = "fw: custom";
        } else if (flash.has(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get())) {
            fwLabel = "fw: modded";
        } else {
            FlashFirmware kind = flash.get(ScevDataComponents.FIRMWARE_KIND.get());
            fwLabel = "fw: " + (kind != null ? kind : FlashFirmware.LINUX).getSerializedName();
        }
        g.drawString(font, fwLabel, x, y + line++ * lineHeight, TEXT_DIM, false);

        ItemStack socStack = menu.getMcu().getItem(McuBoardBlockEntity.SLOT_SOC);
        if (socStack.getItem() instanceof SocItem soc) {
            String spec = soc.getIsa() + " " + soc.getHartCount() + "x " + SocItem.formatRam(soc.getEmbeddedRamKib());
            g.drawString(font, spec, x, y + line++ * lineHeight, TEXT_DIM, false);
        }

        pose.popPose();
    }

    private void drawGpioLeds(GuiGraphics g) {
        int mask = menu.getGpioOutputMask();

        int rowWidth = LED_COUNT * LED_SIZE + (LED_COUNT - 1) * LED_GAP;
        int startX = leftPos + LED_STRIP_CENTER_X - rowWidth / 2;
        int y = topPos + LED_STRIP_CENTER_Y - LED_SIZE / 2;

        for (int i = 0; i < LED_COUNT; i++) {
            int px = startX + i * (LED_SIZE + LED_GAP);
            int on = (mask >> i) & 1;
            int fill = on != 0 ? LED_ON_RGB : LED_OFF_RGB;
            g.fill(px, y, px + LED_SIZE, y + LED_SIZE, fill);
            // 1-px frame so LEDs stay legible against any panel color.
            g.fill(px,                y,                 px + LED_SIZE,     y + 1,              LED_FRAME);
            g.fill(px,                y + LED_SIZE - 1,  px + LED_SIZE,     y + LED_SIZE,       LED_FRAME);
            g.fill(px,                y,                 px + 1,            y + LED_SIZE,       LED_FRAME);
            g.fill(px + LED_SIZE - 1, y,                 px + LED_SIZE,     y + LED_SIZE,       LED_FRAME);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        renderFailMessage(g);
        renderTooltip(g, mouseX, mouseY);
    }

    private void renderFailMessage(GuiGraphics g) {
        if (failMessage == null) return;
        long remaining = failMessageEndMs - System.currentTimeMillis();
        if (remaining <= 0) {
            failMessage = null;
            return;
        }
        float alpha = remaining > 300 ? 1.0F : remaining / 300.0F;
        int alphaByte = (int) (alpha * 0xFF);

        String text = failMessage.getString();
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
        int border = 0xFF3030 | (alphaByte << 24);
        g.fill(bgLeft,      bgTop,        bgRight,     bgTop + 1,  border);
        g.fill(bgLeft,      bgBottom - 1, bgRight,     bgBottom,   border);
        g.fill(bgLeft,      bgTop,        bgLeft + 1,  bgBottom,   border);
        g.fill(bgRight - 1, bgTop,        bgRight,     bgBottom,   border);

        int textColor = 0xFF5050 | (alphaByte << 24);
        g.drawString(font, text, cx - textWidth / 2, bgTop + padY, textColor, true);
    }
}
