/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import io.wispforest.owo.ui.base.BaseOwoHandledScreen
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Component as OwoComponent
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import lekkit.scev.client.screen.owo.ScevSurfaces
import lekkit.scev.client.screen.owo.fixed
import lekkit.scev.client.screen.owo.horizontalFlow
import lekkit.scev.client.screen.owo.literal
import lekkit.scev.client.screen.owo.translatable
import lekkit.scev.client.screen.owo.verticalFlow
import lekkit.scev.menu.FlashProgrammerMenu
import lekkit.scev.network.FlashProgrammerWritePayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor

/**
 * owo-ui port of the flash-programmer screen — fully declarative, no BG
 * asset. Layout flows top-to-bottom inside an owo `Surface.PANEL` frame:
 *
 *   Flash Programmer
 *   [src] → [tgt]    [ Write ]
 *   ─────────
 *   [9×3 inventory]
 *   [9×1 hotbar]
 *
 * Every slot — including the 27 inv + 9 hotbar slots from
 * [FlashProgrammerMenu] — is embedded via [BaseOwoHandledScreen.slotAsComponent].
 * owo updates the underlying [net.minecraft.world.inventory.Slot]'s x/y at
 * layout time, so the menu's hardcoded slot coords become render-irrelevant
 * (vanilla still uses them as initial values; click-routing keeps working
 * because the menu Slot objects are the same identity owo positions).
 *
 * Status banner stays as a custom render pass at the top of the screen.
 */
class FlashProgrammerScreen(menu: FlashProgrammerMenu, inv: Inventory, title: Component) :
    BaseOwoHandledScreen<FlowLayout, FlashProgrammerMenu>(menu, inv, title) {

    private var statusMessage: MutableComponent? = null
    private var statusEndMs: Long = 0L
    private var statusOk: Boolean = true

    /** Last-observed DataSlot value — used to detect new status pushes. */
    private var lastObserved: FlashProgrammerMenu.WriteStatus = FlashProgrammerMenu.WriteStatus.IDLE

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { _, _ ->
            verticalFlow(Sizing.fill(100), Sizing.fill(100))
        }

    override fun build(rootComponent: FlowLayout) {
        rootComponent.surface(Surface.BLANK)
        rootComponent.horizontalAlignment(HorizontalAlignment.CENTER)
        rootComponent.verticalAlignment(VerticalAlignment.CENTER)

        rootComponent.child(buildPanel())
    }

    /** Inner panel — content-sized dark frame around the controls. */
    private fun buildPanel(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            surface(ScevSurfaces.PANEL)
            padding(Insets.of(PANEL_PAD))
            gap(SECTION_GAP)
            horizontalAlignment(HorizontalAlignment.CENTER)

            // Reuse the existing menu title key (matches the block name).
            child(Components.label("container.scev.flash_programmer".translatable)
                .color(Color.ofRgb(TITLE_COLOR))
                .margins(Insets.bottom(2)))

            child(buildActionRow())
            child(buildInventoryGrid())
            child(buildHotbarRow())
        }

    /**
     * Per-slot 18×18 inset well: 1-px bevel padding around a centered 16×16
     * SlotComponent. The padding is critical — owo's SlotComponent is 16×16
     * (its `determineHorizontalContentSize` returns 16), so without padding
     * the slot art lands at the cell's top-left corner, overlapping the
     * top + left bevel and leaving a 2-px gap at bottom + right. With
     * `Insets.of(1)` the bevel forms a clean frame around the slot.
     *
     * SlotComponent.updateX(int) (mixin'd by owo via `SlotAccessor`) keeps
     * the underlying `Slot.x` / `Slot.y` in sync with the layout: it sets
     * `slot.x = component.x - screen.leftPos`, so vanilla `renderSlot` /
     * `renderSlotHighlight` (which draw at `slot.x + leftPos`) line up with
     * the same coordinate owo positioned the cell at.
     */
    private fun slotCell(slotIndex: Int, tooltip: Component? = null): OwoComponent =
        Containers.verticalFlow(SLOT_SIZE.fixed, SLOT_SIZE.fixed).apply {
            surface(ScevSurfaces.INSET)
            padding(Insets.of(1))
            child(slotAsComponent(slotIndex).apply {
                if (tooltip != null) tooltip(tooltip)
            })
        }

    /** Source slot → target slot, with a centered Write button to the right. */
    private fun buildActionRow(): FlowLayout =
        horizontalFlow(Sizing.content(), Sizing.content()).apply {
            verticalAlignment(VerticalAlignment.CENTER)
            gap(8)
            child(slotCell(0, Component.translatable("tooltip.scev.programmer.source")))
            child(Components.label("→".literal).color(Color.ofRgb(ARROW_COLOR)))
            child(slotCell(1, Component.translatable("tooltip.scev.programmer.target")))
            child(Components.button("button.scev.programmer.write".translatable) { onWriteClicked() }
                .horizontalSizing(60.fixed)
                .margins(Insets.left(8))
                .tooltip(Component.translatable("tooltip.scev.programmer.write")))
        }

    /**
     * 9×3 player inventory grid — each cell is its own beveled inset well
     * so the slot frames tile like the originals (mcu_board.png et al)
     * rather than reading as one big dark rectangle.
     */
    private fun buildInventoryGrid(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(4))
            for (row in 0 until INV_ROWS) {
                child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
                    gap(0)
                    for (col in 0 until INV_COLS) {
                        child(slotCell(FIRST_INV_SLOT + row * INV_COLS + col))
                    }
                })
            }
        }

    /** 9×1 hotbar — per-slot bevels, same treatment, lifted 4 px from the inv grid. */
    private fun buildHotbarRow(): FlowLayout =
        horizontalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(4))
            for (col in 0 until INV_COLS) {
                child(slotCell(FIRST_HOTBAR_SLOT + col))
            }
        }

    private fun onWriteClicked() {
        PacketDistributor.sendToServer(FlashProgrammerWritePayload)
        // Status pops in when the DataSlot updates; no optimistic UI here.
    }

    override fun containerTick() {
        super.containerTick()
        // Poll the DataSlot for a new status push. On change, stash a
        // timestamped message to display for a couple seconds.
        val now = menu.lastStatus()
        if (now != lastObserved && now != FlashProgrammerMenu.WriteStatus.IDLE) {
            lastObserved = now
            statusMessage = Component.translatable(now.langKey())
            statusOk = now == FlashProgrammerMenu.WriteStatus.OK
            statusEndMs = System.currentTimeMillis() + STATUS_FADE_MS

            if (!statusOk) {
                Minecraft.getInstance().soundManager.play(
                    SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.8F))
            }
        }
    }

    /**
     * owo's `BaseOwoHandledScreen.renderBackground` is a no-op which kills
     * both `renderTransparentBackground` (the dim) AND the `renderBg` →
     * subclass-override dispatch in 1.21. Inline the parent's behavior.
     * No PNG to blit — owo's PANEL surface paints the frame.
     */
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderTransparentBackground(g)
    }

    /** Skip the inherited container labels — title goes inside the owo panel. */
    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {}

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        renderStatus(g)
        renderTooltip(g, mouseX, mouseY)
    }

    private fun renderStatus(g: GuiGraphics) {
        val msg = statusMessage ?: return
        val remaining = statusEndMs - System.currentTimeMillis()
        if (remaining <= 0) {
            statusMessage = null
            return
        }
        val alpha = if (remaining > 300) 1.0F else remaining / 300.0F
        val alphaByte = (alpha * 0xFF).toInt()

        val text = msg.string
        val textWidth = font.width(text)
        val padX = 6
        val padY = 3
        val cx = width / 2
        // Banner sits 8 px from the top of the screen — owo's panel is
        // content-sized + centered, so there's no fixed topPos to anchor to.
        val y = 8

        val bgLeft = cx - textWidth / 2 - padX
        val bgRight = cx + textWidth / 2 + padX
        val bgTop = y
        val bgBottom = y + 9 + padY * 2

        val bgAlpha = minOf(alphaByte, 0xC0)
        g.fill(bgLeft, bgTop, bgRight, bgBottom, bgAlpha shl 24)
        val borderRgb = if (statusOk) 0x40FF40 else 0xFF3030
        val border = borderRgb or (alphaByte shl 24)
        g.fill(bgLeft,      bgTop,        bgRight,    bgTop + 1, border)
        g.fill(bgLeft,      bgBottom - 1, bgRight,    bgBottom,  border)
        g.fill(bgLeft,      bgTop,        bgLeft + 1, bgBottom,  border)
        g.fill(bgRight - 1, bgTop,        bgRight,    bgBottom,  border)

        val textRgb = if (statusOk) 0x60FF60 else 0xFF5050
        val textColor = textRgb or (alphaByte shl 24)
        g.drawString(font, text, cx - textWidth / 2, bgTop + padY, textColor, true)
    }

    companion object {
        private const val PANEL_PAD: Int = 8
        private const val SECTION_GAP: Int = 6
        // Light gray title — DARK_PANEL is near-black so the original
        // dark-on-light scheme would be unreadable.
        private const val TITLE_COLOR: Int = 0xC0C0C0
        private const val ARROW_COLOR: Int = 0xA0A0A0
        private const val SLOT_SIZE: Int = 18

        private const val INV_ROWS: Int = 3
        private const val INV_COLS: Int = 9
        // Slot indices in FlashProgrammerMenu: 0=source, 1=target, then
        // 27 inv (rows 1..3), then 9 hotbar.
        private const val FIRST_INV_SLOT: Int = 2
        private const val FIRST_HOTBAR_SLOT: Int = FIRST_INV_SLOT + INV_ROWS * INV_COLS

        /** Status line shows the last write outcome until faded. */
        private const val STATUS_FADE_MS: Long = 2500L
    }
}
