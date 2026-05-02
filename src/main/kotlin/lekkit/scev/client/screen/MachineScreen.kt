/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import com.mojang.blaze3d.systems.RenderSystem
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import java.util.function.Consumer
import lekkit.rvvm.HIDKeyboard
import lekkit.scev.client.DisplayManager
import lekkit.scev.client.screen.owo.horizontalFlow
import lekkit.scev.client.screen.widget.IconButton
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.menu.MachineMenu
import lekkit.scev.network.MachineInputPayload
import lekkit.scev.network.MachineResetPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW

/**
 * owo-ui port of the framebuffer / VM-input screen.
 *
 * Layout:
 *
 *   ┌── ScevSurfaces.PANEL ─────────────┐
 *   │  ┌── ScevSurfaces.INSET ────────┐ │
 *   │  │                              │ │
 *   │  │      framebuffer (4:3)       │ │
 *   │  │                              │ │
 *   │  └──────────────────────────────┘ │
 *   │  [ Power ]  [ Paste ]             │
 *   └───────────────────────────────────┘
 *
 * The framebuffer texture is painted through a custom owo [Surface]
 * ([framebufferSurface]) attached to a fixed-size cell. Going through
 * the Surface mechanism puts the texture into owo's draw order on top
 * of the parent INSET cell's `#464646` fill — no post-`super.render()`
 * hack needed. The cell's laid-out bounds also drive the input
 * handlers' hit-test via the [displayX]/[displayY]/[displayW]/[displayH]
 * mirror, which the `framebufferSurface` updates each frame.
 *
 * JEI exclusion is handled by `lekkit.scev.compat.jei.ScevJeiPlugin` —
 * the entire screen is marked as off-limits so JEI hides its overlay.
 *
 * **Held-key tracking.** We keep the set of HID keys we've pressed
 * (without release). On screen close / focus lost, every tracked key is
 * released — so if the player closes the GUI while holding shift, the
 * VM doesn't see shift as stuck down forever.
 */
class MachineScreen(menu: MachineMenu, inv: Inventory, title: Component) :
    ScevDisplayScreen<MachineMenu>(menu, inv, title) {

    private val keyPress: Consumer<Byte> = Consumer { hid ->
        PacketDistributor.sendToServer(MachineInputPayload.keyPress(hid))
    }
    private val keyRelease: Consumer<Byte> = Consumer { hid ->
        PacketDistributor.sendToServer(MachineInputPayload.keyRelease(hid))
    }

    private val heldKeys = HeldKeyTracker(keyPress, keyRelease)
    private val paster = ClipboardPaster(keyPress, keyRelease)

    /** 1.0 = render at native 640×480 resolution. Computed in
     *  [computeDisplaySize]. */
    private var displayScale: Float = 1.0f

    /** Top-left of the framebuffer rect in screen-space. Refreshed
     *  each frame from the Surface lambda's laid-out bounds; size
     *  comes from the base class's [displayW]/[displayH]. */
    private var displayX: Int = 0
    private var displayY: Int = 0

    /**
     * Custom owo [Surface] that blits the live framebuffer texture into
     * the component's bounds (or paints solid black if the VM is off).
     * Painting through a Surface puts the framebuffer into owo's normal
     * draw order, on top of the parent INSET cell's #464646 fill — no
     * post-super-render hack needed.
     */
    override val displaySurface: Surface = Surface { ctx, c ->
        val x = c.x()
        val y = c.y()
        val w = c.width()
        val h = c.height()
        // Mirror the laid-out rect for the input handlers + layout test.
        displayX = x; displayY = y

        val display = DisplayManager.get(menu.machineUuid)
        if (display == null) {
            ctx.fill(x, y, x + w, y + h, 0xFF000000.toInt())
            return@Surface
        }
        val tex = display.getOrUploadTexture()
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)
        ctx.blit(tex, x, y, w, h, 0f, 0f, SCREEN_W, SCREEN_H, SCREEN_W, SCREEN_H)
    }

    init {
        // Provisional values — owo's actual layout overrides positioning,
        // but AbstractContainerScreen.init still wants something sane for
        // its leftPos/topPos calculation.
        imageWidth = SCREEN_W + 2 * SCREEN_MARGIN
        imageHeight = SCREEN_H + 2 * SCREEN_MARGIN + BUTTON_STRIP_H
    }

    /**
     * Target half-native (320×240 GUI pixels) by default — Minecraft's
     * own GUI scale setting then determines the actual on-screen pixel
     * size, the way vanilla inventories work. At GUI scale 4× on a 4K
     * monitor, half-native lands at 1280×960 actual px (~a third of the
     * screen) instead of 2560×1920 (most of it) like a 1.0 scale would.
     *
     * Auto-shrink only when the window genuinely can't fit the half-
     * native footprint plus chrome; never upscale past DEFAULT_SCALE.
     */
    override fun computeDisplaySize(): Pair<Int, Int> {
        val chromeW = 2 * (PANEL_BORDER + PANEL_PAD + INSET_PAD)
        val chromeH = chromeW + BUTTON_STRIP_H
        val availW = maxOf(SCREEN_W / 4, width - chromeW)
        val availH = maxOf(SCREEN_H / 4, height - chromeH)
        val scaleX = availW / SCREEN_W.toFloat()
        val scaleY = availH / SCREEN_H.toFloat()
        displayScale = minOf(DEFAULT_SCALE, minOf(scaleX, scaleY))
        return Pair(
            maxOf(1, Math.round(SCREEN_W * displayScale)),
            maxOf(1, Math.round(SCREEN_H * displayScale)),
        )
    }

    /**
     * Button row — Power resets the VM, Paste types the host clipboard.
     * Both are 12×12 sprite IconButtons (matching the existing
     * power_button.png 3-frame format) wrapped via owo's
     * wrapVanillaWidget. Text labels would compete with the framebuffer
     * for visual weight.
     */
    override fun buildBelowDisplay(panel: FlowLayout) {
        panel.child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
            gap(BUTTON_GAP)
            child(Components.wrapVanillaWidget(makeIconButton(
                POWER_BUTTON_TEX, "button.scev.power", "tooltip.scev.power",
            ) { PacketDistributor.sendToServer(MachineResetPayload(false)) }))
            child(Components.wrapVanillaWidget(makeIconButton(
                PASTE_BUTTON_TEX, "button.scev.paste", "tooltip.scev.paste",
            ) { triggerPaste() }))
        })
    }

    /**
     * Build a 12×12 [IconButton] from a 42×14 sprite-sheet matching the
     * (idle, hover, pressed) layout of `power_button.png` (frames at
     * x=1, x=15, x=29, all at y=1). One static helper for all the
     * MachineScreen icon buttons; text labels live in the lang file
     * for narration / accessibility but aren't drawn.
     */
    private fun makeIconButton(
        sheet: ResourceLocation,
        narrationKey: String,
        tooltipKey: String,
        onClick: (IconButton) -> Unit,
    ): IconButton {
        val btn = IconButton(
            0, 0, ICON_BUTTON_SIZE, ICON_BUTTON_SIZE,
            sheet, ICON_SHEET_W, ICON_SHEET_H,
            15, 1,    // idle  (frame 1)
             1, 1,    // hover (frame 2)
            29, 1,    // pressed (frame 3)
            Component.translatable(narrationKey),
            { onClick(it) },
        )
        btn.tooltip = Tooltip.create(Component.translatable(tooltipKey))
        return btn
    }

    override fun containerTick() {
        super.containerTick()
        paster.tick()
    }

    /* ----- Input: keyboard ------------------------------------------------ */

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(keyCode, scanCode, modifiers)

        // Ctrl+Shift+V (Cmd+Shift+V on macOS) pastes; plain Ctrl+V passes
        // through to the guest. The V key itself is consumed (not tracked
        // as held) so the guest doesn't see a stray 'v' before the paste.
        if (keyCode == GLFW.GLFW_KEY_V
            && (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
            && (modifiers and (GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SUPER)) != 0
        ) {
            triggerPaste()
            return true
        }

        val hid = GlfwToHid.map(keyCode)
        if (hid != 0.toByte()) {
            heldKeys.press(hid)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun triggerPaste() {
        var text = Minecraft.getInstance().keyboardHandler.clipboard
        if (text.isNullOrEmpty()) return
        if (text.length > MAX_PASTE_CHARS) text = text.substring(0, MAX_PASTE_CHARS)

        for (mod in MODIFIER_HIDS) {
            if (heldKeys.isHeld(mod)) {
                paster.queueRelease(mod)
            }
        }
        paster.queueText(text)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return super.keyReleased(keyCode, scanCode, modifiers)

        val hid = GlfwToHid.map(keyCode)
        if (hid != 0.toByte()) {
            heldKeys.release(hid)
            return true
        }
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    /** Eat charTyped so typed text doesn't leak into debug overlays. */
    override fun charTyped(codePoint: Char, modifiers: Int): Boolean = true

    /* ----- Input: mouse --------------------------------------------------- */

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (isInScreenRect(mouseX, mouseY)) {
            val hidBtn = mapMouseButton(button)
            if (hidBtn != 0.toByte()) {
                PacketDistributor.sendToServer(MachineInputPayload.mousePress(hidBtn))
                emitMousePlace(mouseX, mouseY)
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val hidBtn = mapMouseButton(button)
        if (hidBtn != 0.toByte()) {
            PacketDistributor.sendToServer(MachineInputPayload.mouseRelease(hidBtn))
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (isInScreenRect(mouseX, mouseY)) {
            emitMousePlace(mouseX, mouseY)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (isInScreenRect(mouseX, mouseY) && scrollY != 0.0) {
            val delta: Byte = if (scrollY > 0) 1 else -1
            PacketDistributor.sendToServer(MachineInputPayload.mouseScroll(delta))
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (isInScreenRect(mouseX, mouseY)) emitMousePlace(mouseX, mouseY)
        super.mouseMoved(mouseX, mouseY)
    }

    /* ----- Lifecycle ------------------------------------------------------ */

    override fun removed() {
        paster.clear()
        heldKeys.releaseAll()
        super.removed()
    }

    /* ----- Helpers -------------------------------------------------------- */

    private fun isInScreenRect(mouseX: Double, mouseY: Double): Boolean =
        mouseX >= displayX && mouseX < displayX + displayW &&
        mouseY >= displayY && mouseY < displayY + displayH

    private fun emitMousePlace(mouseX: Double, mouseY: Double) {
        // Map GUI-space coords back to framebuffer pixels via inverse scale.
        // displayScale is the canonical scale; the actual displayBox dimensions
        // are equal up to rounding, so this is also the inverse of (displayW
        // / SCREEN_W) without the integer-rounding wobble.
        val scaleInv = if (displayScale == 0f) 1f else 1f / displayScale
        val x = maxOf(0, minOf(SCREEN_W - 1, ((mouseX - displayX) * scaleInv).toInt()))
        val y = maxOf(0, minOf(SCREEN_H - 1, ((mouseY - displayY) * scaleInv).toInt()))
        PacketDistributor.sendToServer(MachineInputPayload.mousePlace(x.toShort(), y.toShort()))
    }

    companion object {
        private const val SCREEN_W: Int = 640
        private const val SCREEN_H: Int = 480

        /**
         * Default GUI-space scale for the framebuffer. 0.5 → 320×240 GUI
         * pixels, which feels like a normal inventory window after MC's
         * own GUI-scale setting is applied (matches the look the 1.7.10
         * version had). Auto-shrinks when the window can't fit even this.
         */
        private const val DEFAULT_SCALE: Float = 0.5f

        // Outer scev PANEL has 1-px outline + 1-px bevel = 2 px on each
        // side. PANEL_PAD/INSET_PAD live in the base ScevDisplayScreen.
        private const val PANEL_BORDER: Int = 2

        // Reserved height for the button row in the auto-scale calculation
        // (button + gap + a bit of fudge so the panel's vertical sizing
        // doesn't have to be re-computed in two passes).
        private const val BUTTON_STRIP_H: Int = 24

        private const val BUTTON_GAP: Int = 4

        // Icon button sprite sheets (3 frames at x=1/15/29 of a 42×14 sheet,
        // see widget/power_button.png for the canonical layout — paste_button.png
        // matches the same shape).
        private const val ICON_BUTTON_SIZE: Int = 12
        private const val ICON_SHEET_W: Int = 42
        private const val ICON_SHEET_H: Int = 14
        private val POWER_BUTTON_TEX: ResourceLocation =
            ScalarEvolution.rl("textures/gui/widget/power_button.png")
        private val PASTE_BUTTON_TEX: ResourceLocation =
            ScalarEvolution.rl("textures/gui/widget/paste_button.png")

        private const val SCREEN_MARGIN: Int = 16   // legacy, kept for clarity in init()

        private const val MAX_PASTE_CHARS: Int = 1024

        private val MODIFIER_HIDS: ByteArray = byteArrayOf(
            HIDKeyboard.HID_KEY_LEFTCTRL, HIDKeyboard.HID_KEY_LEFTSHIFT,
            HIDKeyboard.HID_KEY_LEFTALT, HIDKeyboard.HID_KEY_LEFTMETA,
            HIDKeyboard.HID_KEY_RIGHTCTRL, HIDKeyboard.HID_KEY_RIGHTSHIFT,
            HIDKeyboard.HID_KEY_RIGHTALT, HIDKeyboard.HID_KEY_RIGHTMETA,
        )

        private fun mapMouseButton(glfwButton: Int): Byte = when (glfwButton) {
            GLFW.GLFW_MOUSE_BUTTON_LEFT   -> 1
            GLFW.GLFW_MOUSE_BUTTON_RIGHT  -> 2
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 4
            else -> 0
        }
    }
}
