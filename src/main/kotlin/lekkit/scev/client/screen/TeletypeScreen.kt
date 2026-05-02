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
import lekkit.scev.client.screen.owo.translatable
import lekkit.scev.client.screen.owo.verticalFlow
import lekkit.scev.menu.TeletypeMenu
import lekkit.scev.network.TeletypePrintTestPayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Teletype GUI: paper + ribbon slots, "Print Test Page" button, and
 * a paper-strip view of the most recently printed lines.
 *
 * Visual: the strip uses a cream-paper-colored background with a
 * monospace-friendly font; line wrap matches the BE's MAX_LINE_CHARS.
 */
class TeletypeScreen(menu: TeletypeMenu, inv: Inventory, title: Component) :
    BaseOwoHandledScreen<FlowLayout, TeletypeMenu>(menu, inv, title) {

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

    private fun buildPanel(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            surface(ScevSurfaces.PANEL)
            padding(Insets.of(PANEL_PAD))
            gap(SECTION_GAP)
            horizontalAlignment(HorizontalAlignment.CENTER)

            child(Components.label(title)
                .color(Color.ofRgb(TITLE_COLOR))
                .margins(Insets.bottom(2)))

            // Paper-strip viewport — cream background with the recent
            // printed lines as monospace text.
            child(buildPaperStrip())

            // Loading row: paper slot · ribbon slot · print button.
            child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
                verticalAlignment(VerticalAlignment.CENTER)
                gap(8)
                child(slotCell(0))
                child(slotCell(1))
                child(Components.button("button.scev.teletype.test_print".translatable) {
                    PacketDistributor.sendToServer(TeletypePrintTestPayload)
                }.horizontalSizing(80.fixed))
            })

            child(buildInventoryGrid())
            child(buildHotbarRow())
        }

    private fun buildPaperStrip() = Containers.verticalFlow(
        STRIP_W.fixed, STRIP_H.fixed,
    ).apply {
        surface(Surface { ctx, c ->
            // Cream paper with a few horizontal ruler tints to suggest
            // the platen lines.
            ctx.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height(), PAPER_BG)
            // Tear-off perforation hint at the top.
            for (i in 0 until c.width() step 4) {
                ctx.fill(c.x() + i, c.y(), c.x() + i + 2, c.y() + 1, PAPER_PERF)
            }
        })
        padding(Insets.of(4))
        gap(0)
        // Render the recent lines as labels stacked top-down. Use
        // a fixed-width-ish font color (vanilla can't enforce
        // monospace, but the cream + black + monospace-ish look is
        // very teletype-y).
        for (line in menu.be.visibleLines()) {
            child(Components.label(Component.literal(line))
                .color(Color.ofRgb(PAPER_INK))
                .horizontalSizing(STRIP_W.fixed))
        }
    }

    private fun slotCell(slotIndex: Int) =
        Containers.verticalFlow(SLOT_SIZE.fixed, SLOT_SIZE.fixed).apply {
            surface(ScevSurfaces.INSET)
            padding(Insets.of(1))
            child(slotAsComponent(slotIndex))
        }

    private fun buildInventoryGrid(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(4))
            val firstInv = 2  // 0 = paper, 1 = ribbon, then inv
            for (row in 0 until 3) {
                child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
                    gap(0)
                    for (col in 0 until 9) child(slotCell(firstInv + row * 9 + col))
                })
            }
        }

    private fun buildHotbarRow(): FlowLayout =
        horizontalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(4))
            val firstHotbar = 2 + 27
            for (col in 0 until 9) child(slotCell(firstHotbar + col))
        }

    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderTransparentBackground(g)
    }
    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {}
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        renderTooltip(g, mouseX, mouseY)
    }

    companion object {
        private const val PANEL_PAD: Int = 8
        private const val SECTION_GAP: Int = 6
        private const val SLOT_SIZE: Int = 18
        private const val TITLE_COLOR: Int = 0xC0C0C0

        private const val STRIP_W: Int = 162  // 9 × 18 = inv-grid width
        private const val STRIP_H: Int = 100
        private const val PAPER_BG: Int = 0xFFEFE6CB.toInt()  // cream paper
        private const val PAPER_PERF: Int = 0xFFA89A6F.toInt()
        private const val PAPER_INK: Int = 0x202020
    }
}
