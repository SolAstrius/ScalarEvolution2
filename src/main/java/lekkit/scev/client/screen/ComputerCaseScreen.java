/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import lekkit.scev.client.screen.widget.IconButton;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.menu.ComputerCaseMenu;
import lekkit.scev.menu.SlotDef;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Screen for the computer case's component editor (motherboard + 14 components
 * + player inventory). One button on a sidebar to the left:
 *
 * <p><b>Power</b> — toggles the machine on/off. Triggers
 * {@link lekkit.scev.blockentity.ComputerCaseBlockEntity#buildMachine}
 * on first press so the installed components get picked up. A click while
 * powered off runs {@link ComputerCaseMenu#validateForPower preflight
 * validation}; missing-component failures flash the button red, play a
 * short beep, and surface the reason as a floating message. A click while
 * powered on is unconditionally legal (power-off always works).
 *
 * <p>There is no separate reset button. Off→on is functionally equivalent
 * to a warm reset for Minecraft use — the VM thread is torn down and a
 * fresh boot runs from the installed bootrom. {@link MachineResetPayload}
 * still accepts a {@code reset=true} flag in the wire protocol for
 * potential future warm-reset UI; nothing in the current screen emits it.
 *
 * <p>Background swaps by installed motherboard tier — the PCB art on each
 * variant has the slot outlines pre-drawn. Slot hints (grey sprites
 * depicting the expected component type) render on every empty component
 * slot when a motherboard is installed; the motherboard slot itself
 * always shows its own hint when empty.
 */
public class ComputerCaseScreen extends AbstractContainerScreen<ComputerCaseMenu> {

    // -- Main panel backgrounds (1.7.10 layout in top-left 176x222 of 256x256) --
    private static final ResourceLocation BG_EMPTY =
            ScalarEvolution.rl("textures/gui/computer_case_empty.png");
    private static final ResourceLocation BG_MB_T1 =
            ScalarEvolution.rl("textures/gui/computer_case_motherboard1.png");
    private static final ResourceLocation BG_MB_T2 =
            ScalarEvolution.rl("textures/gui/computer_case_motherboard2.png");
    private static final ResourceLocation BG_MB_T3 =
            ScalarEvolution.rl("textures/gui/computer_case_motherboard3.png");

    // -- Sidebar + button sprite sheets --
    /** sidebar_1.png: 19×20 single-button backdrop (4px top edge + 12px btn + 4px bottom edge). */
    private static final ResourceLocation SIDEBAR_TEX =
            ScalarEvolution.rl("textures/gui/widget/sidebar_1.png");
    /** power_button.png: 42×14, three 12×12 frames at (1,1)/(15,1)/(29,1). */
    private static final ResourceLocation POWER_BUTTON_TEX =
            ScalarEvolution.rl("textures/gui/widget/power_button.png");

    private static final int SIDEBAR_WIDTH  = 19;
    private static final int SIDEBAR_HEIGHT = 20;
    /** Sidebar's top edge relative to {@code topPos}. Matches upstream convention. */
    private static final int SIDEBAR_TOP = 8;

    /** How long a preflight failure message stays visible, in ms. */
    private static final long FAIL_MESSAGE_DURATION_MS = 2500L;

    /** Last preflight failure, or {@code null} if none active. */
    private @org.jetbrains.annotations.Nullable Component failMessage;
    private long failMessageEndMs;

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
        // Sidebar sits just to the left of the main panel. The button inset
        // matches the sidebar art: 4px in from the left edge, 4px below the
        // top edge.
        int sideX = leftPos - SIDEBAR_WIDTH;
        int sideY = topPos + SIDEBAR_TOP;
        int btnX  = sideX + 4;
        int btnY  = sideY + 4;

        // Power button: 3-frame sheet (active / base / pressed) + toggle state.
        //
        //   VM OFF — idle  → base frame  (15, 1)  — dim "off" look
        //   VM OFF — hover → active frame (1, 1)  — brighter, inviting
        //   VM ON  — idle  → active frame (1, 1)  — persistently lit
        //   VM ON  — hover → active frame (1, 1)  — same (no separate on-hover)
        //   pressed        → pressed frame (29, 1)  — 200 ms post-click flash
        //
        // The onClick callback runs client-side validation before firing the
        // packet. Missing components trigger a fail-flash + error beep + chat
        // message instead of a silent no-op, so the player learns WHY the
        // power didn't take.
        IconButton powerBtn = new IconButton(
                btnX, btnY, 12, 12,
                POWER_BUTTON_TEX, 42, 14,
                15, 1,    // idle (off)
                 1, 1,    // hover (off) — active frame
                29, 1,    // pressed
                Component.translatable("button.scev.power"),
                this::onPowerClicked)
                .withToggle(menu::isMachinePowered,
                         1, 1,    // idle (on) — active frame
                         1, 1);   // hover (on) — same
        powerBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.scev.power")));
        addRenderableWidget(powerBtn);
    }

    /**
     * Validating power-button click handler. On OK, fires the existing
     * {@link MachineResetPayload}; on any failure, flashes the button red,
     * plays a short error beep, and posts an overlay message explaining
     * which component is missing. Server-side validation still runs
     * independently — this is a UX preflight, not a security check.
     */
    private void onPowerClicked(IconButton btn) {
        // If the machine is currently on, power-off is always legal — skip
        // validation entirely. Validation only gates the off→on transition.
        if (menu.isMachinePowered()) {
            PacketDistributor.sendToServer(new MachineResetPayload(false));
            return;
        }

        ComputerCaseMenu.ValidationResult r = menu.validateForPower();
        if (r == ComputerCaseMenu.ValidationResult.OK) {
            PacketDistributor.sendToServer(new MachineResetPayload(false));
            return;
        }

        btn.flashFail(500);
        Minecraft mc = Minecraft.getInstance();
        // Short "nope" — low bass note, reuses a vanilla sound so we don't
        // ship an audio asset.
        mc.getSoundManager().play(SimpleSoundInstance.forUI(
                SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.8F));

        // Display a red error message on the screen itself rather than via
        // the HUD action bar — the latter renders behind the screen's dark
        // overlay and is invisible while the GUI is open.
        failMessage = Component.translatable(r.langKey()).withStyle(ChatFormatting.RED);
        failMessageEndMs = System.currentTimeMillis() + FAIL_MESSAGE_DURATION_MS;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // Sidebar backdrop — drawn first so the button rendered later sits on top.
        // Full-sheet texture (19×20) so uv math is straightforward.
        g.blit(SIDEBAR_TEX, leftPos - SIDEBAR_WIDTH, topPos + SIDEBAR_TOP,
                0, 0, SIDEBAR_WIDTH, SIDEBAR_HEIGHT, SIDEBAR_WIDTH, SIDEBAR_HEIGHT);

        // Pick the background based on what motherboard (if any) is seated.
        // The case's own slot 0 holds the motherboard stack; absence of a
        // MotherboardItem there means "no motherboard installed" -> empty case.
        ItemStack mbStack = menu.getCaseBE().getItem(0);
        MotherboardItem mbItem = mbStack.getItem() instanceof MotherboardItem mi ? mi : null;
        ResourceLocation bg = switch (mbItem == null ? 0 : mbItem.getLevel()) {
            case 1 -> BG_MB_T1;
            case 2 -> BG_MB_T2;
            case 3 -> BG_MB_T3;
            default -> BG_EMPTY;
        };
        // Textures are 256x256 PNGs with the GUI art in the top-left 176x222
        // region (legacy 1.7.10 layout). Pass 256,256 as the source dimensions
        // so MC samples only that region — otherwise the whole 256x256 gets
        // stretched into the 176x222 render area.
        g.blit(bg, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);

        renderSlotHints(g, mbItem);
    }

    /**
     * Draw a greyed-out hint sprite on every empty slot. The motherboard
     * slot (SlotDef index 0) always shows its hint when empty. Component
     * slots only show hints when a motherboard is seated and their slot
     * index is enabled for that tier — disabled slots have no function so
     * a hint would mislead.
     */
    private void renderSlotHints(GuiGraphics g, MotherboardItem mbItem) {
        List<SlotDef> defs = SlotDef.COMPUTER_CASE;
        for (int i = 0; i < defs.size(); i++) {
            SlotDef def = defs.get(i);
            String hintKey = def.background();
            if (hintKey == null) continue;
            ResourceLocation sprite = SlotHints.spriteFor(hintKey);
            if (sprite == null) continue;

            Slot slot = menu.slots.get(i);
            if (slot.hasItem()) continue;

            if (def.index() != 0) {
                if (mbItem == null) continue;
                if (!mbItem.isSlotEnabled(def.index() - 1)) continue;
            }
            SlotHints.draw(g, sprite, leftPos + slot.x, topPos + slot.y);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        renderFailMessage(g);
        renderTooltip(g, mouseX, mouseY);
    }

    /**
     * Draw the preflight failure message floating above the main panel
     * (centered on the screen width) so it's unambiguously visible against
     * the darkened world behind the GUI rather than competing with the
     * panel's grey interior. Fades out over the last 300 ms of its
     * visibility window. A semi-transparent black pill behind the text
     * keeps it legible on any world background.
     */
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
        int y = topPos - (9 + padY * 2) - 6; // 6 px above the panel's top edge

        int bgLeft   = cx - textWidth / 2 - padX;
        int bgRight  = cx + textWidth / 2 + padX;
        int bgTop    = y;
        int bgBottom = y + 9 + padY * 2;

        // Pill backdrop: semi-transparent black, alpha-matched to the text
        // fade so the whole message disappears together.
        int bgAlpha = Math.min(alphaByte, 0xC0);
        g.fill(bgLeft, bgTop, bgRight, bgBottom, (bgAlpha << 24));
        // 1-px red border for unmistakable error tone.
        int border = 0xFF3030 | (alphaByte << 24);
        g.fill(bgLeft,     bgTop,    bgRight,     bgTop + 1,     border);
        g.fill(bgLeft,     bgBottom - 1, bgRight, bgBottom,      border);
        g.fill(bgLeft,     bgTop,    bgLeft + 1,  bgBottom,      border);
        g.fill(bgRight - 1, bgTop,   bgRight,     bgBottom,      border);

        int textColor = 0xFF5050 | (alphaByte << 24);
        g.drawString(font, text,
                cx - textWidth / 2,
                bgTop + padY,
                textColor, true); // shadowed for contrast on any world bg
    }
}
