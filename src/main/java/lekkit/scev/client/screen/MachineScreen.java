/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import lekkit.rvvm.HIDKeyboard;
import lekkit.scev.client.DisplayManager;
import lekkit.scev.client.DisplayState;
import lekkit.scev.menu.MachineMenu;
import lekkit.scev.network.MachineInputPayload;
import lekkit.scev.network.MachineResetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Screen that displays a running machine's framebuffer. Keyboard / mouse input is
 * converted to {@link MachineInputPayload}s and sent to the server.
 *
 * <p><b>Sizing.</b> The native framebuffer is {@value #SCREEN_W}×{@value #SCREEN_H}.
 * Rendering it 1:1 into Minecraft's GUI space produced a 672×512 window (plus
 * margin) that clipped on most real-world display setups — on a 1920×1080
 * window with GUI scale 2, the GUI space is 960×540 and the window didn't fit
 * vertically; at scale 3 (GUI space 640×360) it didn't fit in either axis.
 *
 * <p>We now compute a scale factor at {@link #init()} to fit the framebuffer
 * inside the current GUI space with a small margin, preserving the 4:3
 * aspect ratio. Mouse coordinates passed to the server are un-scaled so the
 * VM still receives framebuffer-space pixel positions.
 *
 * <p><b>Held-key tracking.</b> We keep the set of HID keys we've pressed
 * (without release). On screen close / focus lost, every tracked key is
 * released — so if the player closes the GUI while holding shift, the VM
 * doesn't see shift as stuck down forever.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    /** Pixel size of the framebuffer — matches Framebuffer(640,480). */
    private static final int SCREEN_W = 640;
    private static final int SCREEN_H = 480;
    /** Space reserved around the framebuffer inside the GUI window. */
    private static final int SCREEN_MARGIN = 16;
    /** Extra vertical space below the framebuffer for the power/reset buttons. */
    private static final int BUTTON_STRIP_H = 16;

    /**
     * Cap on how much clipboard text a single Ctrl+Shift+V will type. Past
     * this, the user is pasting a file — which they should do via a guest-
     * side transfer, not synthetic keystrokes. 4096 chars at
     * {@link ClipboardPaster#DEFAULT_EVENTS_PER_TICK}=4 ev/tick / 20 tps =
     * ~205 s of typing, which is already generous.
     */
    private static final int MAX_PASTE_CHARS = 4096;

    /** Modifier HIDs we suppress before typing pasted text so the guest doesn't see Ctrl+letter etc. */
    private static final byte[] MODIFIER_HIDS = {
            HIDKeyboard.HID_KEY_LEFTCTRL, HIDKeyboard.HID_KEY_LEFTSHIFT,
            HIDKeyboard.HID_KEY_LEFTALT, HIDKeyboard.HID_KEY_LEFTMETA,
            HIDKeyboard.HID_KEY_RIGHTCTRL, HIDKeyboard.HID_KEY_RIGHTSHIFT,
            HIDKeyboard.HID_KEY_RIGHTALT, HIDKeyboard.HID_KEY_RIGHTMETA,
    };

    /** Tracks keys we've emitted KEY_PRESS for but not yet KEY_RELEASE. */
    private final HeldKeyTracker heldKeys = new HeldKeyTracker(
            hid -> PacketDistributor.sendToServer(MachineInputPayload.keyPress(hid)),
            hid -> PacketDistributor.sendToServer(MachineInputPayload.keyRelease(hid)));

    /**
     * Paces clipboard paste as synthetic keystrokes. Same press/release
     * sinks as {@link #heldKeys}, but events here aren't tracked as "held"
     * because they always come in matched press/release pairs inside a
     * single paste operation.
     */
    private final ClipboardPaster paster = new ClipboardPaster(
            hid -> PacketDistributor.sendToServer(MachineInputPayload.keyPress(hid)),
            hid -> PacketDistributor.sendToServer(MachineInputPayload.keyRelease(hid)));

    /** Computed once in {@link #init()}. 1.0 = render at native 640×480 resolution. */
    private float displayScale = 1.0f;
    /** Rendered framebuffer size in GUI pixels ({@code SCREEN_W * displayScale}, rounded). */
    private int displayW = SCREEN_W;
    private int displayH = SCREEN_H;
    /** Top-left corner of the rendered framebuffer in GUI space. */
    private int displayX;
    private int displayY;

    public MachineScreen(MachineMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        // imageWidth/imageHeight are used by AbstractContainerScreen to position
        // leftPos/topPos. We set them provisionally here; init() recomputes the
        // framebuffer size + position based on the actual window, because we
        // don't know `width` / `height` yet in the constructor.
        this.imageWidth = SCREEN_W + 2 * SCREEN_MARGIN;
        this.imageHeight = SCREEN_H + 2 * SCREEN_MARGIN + BUTTON_STRIP_H;
    }

    @Override
    protected void init() {
        // Compute a scale that fits the framebuffer (plus margin + buttons) into
        // the current GUI space. We never upscale — small GUI scales keep the
        // native 640×480 resolution. For larger GUI scales (smaller GUI space)
        // we shrink while preserving the 4:3 aspect.
        int availW = Math.max(SCREEN_W / 4, this.width - 2 * SCREEN_MARGIN);
        int availH = Math.max(SCREEN_H / 4, this.height - 2 * SCREEN_MARGIN - BUTTON_STRIP_H);
        float scaleX = availW / (float) SCREEN_W;
        float scaleY = availH / (float) SCREEN_H;
        this.displayScale = Math.min(1.0f, Math.min(scaleX, scaleY));
        this.displayW = Math.max(1, Math.round(SCREEN_W * displayScale));
        this.displayH = Math.max(1, Math.round(SCREEN_H * displayScale));

        // Size the AbstractContainerScreen "image" box to the actual display
        // area (plus button strip). leftPos / topPos are centred on this.
        this.imageWidth = displayW + 2 * SCREEN_MARGIN;
        this.imageHeight = displayH + 2 * SCREEN_MARGIN + BUTTON_STRIP_H;

        super.init();

        this.displayX = leftPos + SCREEN_MARGIN;
        this.displayY = topPos + SCREEN_MARGIN;

        // Single power button at the top-right of the framebuffer. Off→on
        // reboots from bootrom; separate warm-reset affordance removed — see
        // ComputerCaseScreen javadoc for rationale.
        int btnX = displayX + displayW - 34;
        int btnY = topPos + imageHeight - BUTTON_STRIP_H + 2;
        addRenderableWidget(Button.builder(Component.translatable("button.scev.power"),
                b -> PacketDistributor.sendToServer(new MachineResetPayload(false)))
                .bounds(btnX, btnY, 32, 12).build());

        // Ensure no widget is holding keyboard focus — all key events should
        // reach our keyPressed / keyReleased so they're forwarded to the VM,
        // not swallowed by a button waiting for ENTER/SPACE.
        this.setFocused(null);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        DisplayState display = DisplayManager.get(menu.getMachineUuid());
        if (display == null) {
            // VM not running (never powered on, or powered off while the
            // screen stayed open). Paint a solid black rect so the display
            // area doesn't fall through to the dimmed-world layer behind —
            // players read that as "my screen vanished" rather than "the VM
            // is off."
            g.fill(displayX, displayY, displayX + displayW, displayY + displayH, 0xFF000000);
            return;
        }
        ResourceLocation tex = display.getOrUploadTexture();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        // Scale the native 640×480 texture into the computed display area.
        // blit(tex, x, y, destW, destH, u, v, srcW, srcH, texW, texH).
        g.blit(tex, displayX, displayY, displayW, displayH, 0, 0, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * Suppress the default slot rendering. The menu's player-inventory slots
     * exist only so that shift-click from another container doesn't crash —
     * they don't correspond to any real container on the server side, so we
     * hide them visually rather than drawing them on top of the framebuffer.
     */
    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        // no-op: framebuffer view doesn't have slots
    }

    /** Hide the default "Inventory" / title labels — they clutter the framebuffer view. */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // no-op
    }

    @Override public boolean isPauseScreen() { return false; }

    /**
     * Client tick hook — fires ~20 times/s. We use it to drain a small
     * batch of pending paste events, so a large clipboard doesn't burst
     * past the guest's HID buffer capacity in a single frame.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        paster.tick();
    }

    /* ---------------- Input: keyboard ---------------- */

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Leave ESCAPE alone so the player can close the screen.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(keyCode, scanCode, modifiers);

        // Ctrl+Shift+V (or Cmd+Shift+V on macOS) pastes the host clipboard as
        // synthetic keystrokes. Plain Ctrl+V still flows through to the guest
        // — many guest programs use Ctrl+V themselves, so we only claim the
        // three-modifier combo. The V key itself is consumed (not tracked as
        // held) so the guest doesn't see a stray 'v' before the paste stream.
        if (keyCode == GLFW.GLFW_KEY_V
                && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0
                && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            triggerPaste();
            return true;
        }

        byte hid = GlfwToHid.map(keyCode);
        if (hid != 0) {
            heldKeys.press(hid);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Read the host clipboard and queue it for typing into the guest. We
     * first emit release events for any modifier keys the tracker currently
     * thinks are held (the Ctrl+Shift that triggered us, at minimum) so the
     * guest doesn't interpret the pasted letters as hotkey chords.
     *
     * <p>We don't re-press those modifiers afterwards. If the player is
     * still holding Ctrl+Shift when paste finishes, GLFW will eventually
     * fire a release we'll forward normally — the VM sees a release-of-
     * unheld key, which HID handles as a no-op. The tradeoff is that any
     * keystrokes the player types between paste-end and their physical
     * release won't carry the original modifiers; in practice nobody does
     * that within the short paste window.
     */
    private void triggerPaste() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (text == null || text.isEmpty()) return;
        if (text.length() > MAX_PASTE_CHARS) text = text.substring(0, MAX_PASTE_CHARS);

        for (byte mod : MODIFIER_HIDS) {
            if (heldKeys.isHeld(mod)) {
                // Release on the wire but leave the tracker's entry in place.
                // When the player physically releases the key, tracker.release
                // will forward another release — harmless to the guest.
                paster.queueRelease(mod);
            }
        }
        paster.queueText(text);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // Don't emit a release for ESCAPE — we never emitted a press for it
        // (see keyPressed above), and the escape that closes the screen was
        // pressed *before* the screen opened so the VM didn't see it either.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return super.keyReleased(keyCode, scanCode, modifiers);

        byte hid = GlfwToHid.map(keyCode);
        if (hid != 0) {
            heldKeys.release(hid);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /**
     * Consume {@code charTyped} events for any key we would have handled in
     * keyPressed (letters, numbers, symbols). Otherwise AbstractContainerScreen's
     * default charTyped may leak text into debug tools or kick focus around —
     * the VM has already received the HID scancode via keyPressed, and there's
     * no text field to type into in this screen.
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return true;
    }

    /* ---------------- Input: mouse ---------------- */

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isInScreenRect(mouseX, mouseY)) {
            byte hidBtn = mapMouseButton(button);
            if (hidBtn != 0) {
                PacketDistributor.sendToServer(MachineInputPayload.mousePress(hidBtn));
                emitMousePlace(mouseX, mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        byte hidBtn = mapMouseButton(button);
        if (hidBtn != 0) {
            PacketDistributor.sendToServer(MachineInputPayload.mouseRelease(hidBtn));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (isInScreenRect(mouseX, mouseY)) {
            emitMousePlace(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInScreenRect(mouseX, mouseY) && scrollY != 0) {
            byte delta = (byte) (scrollY > 0 ? 1 : -1);
            PacketDistributor.sendToServer(MachineInputPayload.mouseScroll(delta));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (isInScreenRect(mouseX, mouseY)) {
            emitMousePlace(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    /* ---------------- Lifecycle ---------------- */

    @Override
    public void removed() {
        // Drop any paste events that hadn't been drained yet — they'd arrive
        // at the guest without the original paste context and potentially
        // land in the wrong window / tty. Better to lose the tail than to
        // scatter random characters.
        paster.clear();
        // Release any keys we've left pressed. Otherwise the VM sees them as
        // stuck down (e.g. shift jammed on) forever. Done through the tracker
        // so HeldKeyTrackerTest can verify the contract without a real screen.
        heldKeys.releaseAll();
        super.removed();
    }

    /* ---------------- Helpers ---------------- */

    private boolean isInScreenRect(double mouseX, double mouseY) {
        return mouseX >= displayX && mouseX < displayX + displayW
                && mouseY >= displayY && mouseY < displayY + displayH;
    }

    private void emitMousePlace(double mouseX, double mouseY) {
        // Map GUI-space coords back to framebuffer pixels, accounting for scale.
        float scaleInv = displayScale == 0f ? 1f : 1f / displayScale;
        int x = (int) Math.max(0, Math.min(SCREEN_W - 1, (mouseX - displayX) * scaleInv));
        int y = (int) Math.max(0, Math.min(SCREEN_H - 1, (mouseY - displayY) * scaleInv));
        PacketDistributor.sendToServer(MachineInputPayload.mousePlace((short) x, (short) y));
    }

    /**
     * Map GLFW mouse button to HID mouse button bit. HID buttons: 1=left,
     * 2=right, 4=middle (bitmask). Our payload carries a single byte — we
     * re-use that as "which bit to toggle" so the server handler sets/clears
     * the right bit on the HID mouse state.
     */
    private static byte mapMouseButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 4;
            default -> 0;
        };
    }
}
