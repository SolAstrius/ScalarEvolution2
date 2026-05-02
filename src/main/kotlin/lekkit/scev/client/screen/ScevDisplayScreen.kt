/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import io.wispforest.owo.ui.base.BaseOwoHandledScreen
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import lekkit.scev.client.screen.owo.ScevSurfaces
import lekkit.scev.client.screen.owo.fixed
import lekkit.scev.client.screen.owo.verticalFlow
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot

/**
 * Common chrome for screens that paint a "display" rectangle (the VM
 * framebuffer, the VT100 terminal, future sixel viewer, etc.):
 *
 *  ┌── ScevSurfaces.PANEL ─────────────┐
 *  │  ┌── ScevSurfaces.INSET ────────┐ │
 *  │  │                              │ │
 *  │  │       displaySurface         │ │
 *  │  │                              │ │
 *  │  └──────────────────────────────┘ │
 *  │  (optional widgets via            │
 *  │   buildBelowDisplay)              │
 *  └───────────────────────────────────┘
 *
 * Subclasses provide:
 *  - [displaySurface]      — what to paint into the display cell
 *  - [computeDisplaySize]  — pixel dimensions for that cell
 *  - [buildBelowDisplay]   — optional widgets under the display
 *
 * This base owns the lifecycle bits both screens repeated verbatim:
 * focus suppression, slot disable, the renderBackground = transparent
 * dim, the renderBg/renderSlot/renderLabels no-ops, isPauseScreen=false.
 *
 * Input handling is intentionally NOT extracted — the per-key transport
 * (HID press/release vs UTF-8 byte stream) and held-key tracking differ
 * enough that hiding them behind a strategy interface would obscure
 * more than it shares. Subclasses override the keyPressed/charTyped
 * family directly.
 */
abstract class ScevDisplayScreen<M : AbstractContainerMenu>(
    menu: M, inv: Inventory, title: Component,
) : BaseOwoHandledScreen<FlowLayout, M>(menu, inv, title) {

    /** Pixel dimensions of the display cell. Populated each [build]
     *  pass from [computeDisplaySize]; subclasses may read these in
     *  their input handlers (e.g. to hit-test mouse coords). */
    protected var displayW: Int = 0
        private set
    protected var displayH: Int = 0
        private set

    /** Surface that paints the display content. Subclass owns the
     *  underlying texture/buffer and decides how to blit; we just
     *  install the Surface on a fixed-size cell so it lands inside
     *  the INSET well in owo's natural draw order. */
    protected abstract val displaySurface: Surface

    /** Compute the display rect in pixels for this layout pass.
     *  Called once per [build]. Most subclasses return a constant;
     *  MachineScreen does an auto-scale based on the window size. */
    protected abstract fun computeDisplaySize(): Pair<Int, Int>

    /** Optional row of widgets directly under the display (the
     *  Power/Paste pair on MachineScreen, nothing on TerminalScreen).
     *  Default no-op. */
    protected open fun buildBelowDisplay(panel: FlowLayout) {}

    final override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { _, _ ->
            verticalFlow(Sizing.fill(100), Sizing.fill(100))
        }

    final override fun build(rootComponent: FlowLayout) {
        val (w, h) = computeDisplaySize()
        displayW = w
        displayH = h

        rootComponent.surface(Surface.BLANK)
        rootComponent.horizontalAlignment(HorizontalAlignment.CENTER)
        rootComponent.verticalAlignment(VerticalAlignment.CENTER)
        rootComponent.child(buildPanel())
    }

    private fun buildPanel(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            surface(ScevSurfaces.PANEL)
            padding(Insets.of(PANEL_PAD))
            gap(BUTTON_STRIP_GAP)
            horizontalAlignment(HorizontalAlignment.CENTER)

            child(Containers.verticalFlow(Sizing.content(), Sizing.content()).apply {
                surface(ScevSurfaces.INSET)
                padding(Insets.of(INSET_PAD))
                child(Containers.verticalFlow(displayW.fixed, displayH.fixed).apply {
                    surface(displaySurface)
                })
            })

            buildBelowDisplay(this)
        }

    override fun init() {
        super.init()
        // No widget should hold focus — every key event must reach
        // keyPressed/keyReleased, not get swallowed by a focused button
        // waiting for ENTER/SPACE.
        focused = null
        // The menu carries the player's inventory + hotbar (36 slots)
        // only so shift-click from another container doesn't crash.
        // disableSlot drops both the render and the hit-test so they
        // don't leak tooltips ("Oak Fence" etc.) over the display.
        for (i in menu.slots.indices) disableSlot(i)
    }

    /* renderBackground in 1.21 dispatches the dim overlay; the display
     * paints through [displaySurface] as part of the owo tree, so just
     * the dim is wanted here. */
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderTransparentBackground(g)
    }

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {}
    override fun renderSlot(g: GuiGraphics, slot: Slot) {}
    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {}
    override fun isPauseScreen(): Boolean = false

    companion object {
        /** Inner padding of the outer ScevSurfaces.PANEL. */
        const val PANEL_PAD: Int = 6
        /** Inner padding of the recessed ScevSurfaces.INSET frame. */
        const val INSET_PAD: Int = 1
        /** Vertical gap between the display and any below-display row. */
        const val BUTTON_STRIP_GAP: Int = 4
    }
}
