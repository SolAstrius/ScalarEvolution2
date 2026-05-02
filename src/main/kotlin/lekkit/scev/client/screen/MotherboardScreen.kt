/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import com.mojang.blaze3d.systems.RenderSystem
import io.wispforest.owo.ui.base.BaseOwoHandledScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Positioning
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.VerticalAlignment
import io.wispforest.owo.ui.core.Surface
import lekkit.scev.client.screen.owo.verticalFlow
import lekkit.scev.items.MotherboardItem
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.menu.MotherboardMenu
import lekkit.scev.menu.SlotDef
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

/**
 * owo-ui port of the motherboard-in-hand editor. The PCB art (`motherboard*.png`)
 * is too distinctive to redraw declaratively — it's a full-panel painted PCB
 * with traces, sockets, RAM banks, M.2 footprint baked into the image, and
 * the slot positions are designed to land on top of the painted sockets.
 * Keeping it as a `renderBg()` PNG preserves that identity, while the owo
 * port still buys us:
 *
 *   - Consistent slot lifecycle / tooltip plumbing with the other ported screens.
 *   - Free slot positioning sync — owo's `SlotComponent.updateX/Y` mixin keeps
 *     `Slot.x`/`y` aligned with the absolute layout positions we declare here.
 *   - The slot-hint overlay drops vanilla `RenderSystem.setColor` plumbing in
 *     favor of owo's draw context (still drawn imperatively in `render()` —
 *     hints are alpha-blended sprites, not real components).
 */
class MotherboardScreen(menu: MotherboardMenu, inv: Inventory, title: Component) :
    BaseOwoHandledScreen<FlowLayout, MotherboardMenu>(menu, inv, title) {

    init {
        imageWidth = SlotDef.FAT_IMAGE_WIDTH
        imageHeight = SlotDef.FAT_IMAGE_HEIGHT
    }

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

    /**
     * owo's `BaseOwoHandledScreen.renderBackground` is a no-op which kills
     * both the dim overlay AND the renderBg → subclass-override dispatch
     * in 1.21 (vanilla calls `renderTransparentBackground` + `renderBg`
     * from inside `renderBackground`). Inline the parent's behavior so
     * the PCB blit still runs.
     */
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderTransparentBackground(g)
        renderBg(g, partialTick, mouseX, mouseY)
    }

    /**
     * Inner panel the size of the PCB BG art. Every menu slot is wrapped in
     * a transparent owo SlotComponent at its `SlotDef` coord — the painted
     * sockets in the BG show through because the wrapper has no surface.
     * Centered via the rootComponent's alignment so leftPos/topPos (set by
     * AbstractContainerScreen.init() based on imageWidth/imageHeight) line
     * up with the panel's top-left, which the renderBg() blit relies on.
     */
    private fun buildPanel(): FlowLayout =
        verticalFlow(SlotDef.FAT_IMAGE_WIDTH.fixed(), SlotDef.FAT_IMAGE_HEIGHT.fixed()).apply {
            surface(Surface.BLANK)

            // Component slots over the PCB.
            for (i in SlotDef.MOTHERBOARD.indices) {
                val def = SlotDef.MOTHERBOARD[i]
                child(slotAsComponent(i)
                    .positioning(Positioning.absolute(def.x, def.y)))
            }
            // Player inventory + hotbar — slot indices follow MotherboardMenu's
            // `addSlot` order: 14 component slots, then 27 inv, then 9 hotbar.
            val firstInv = SlotDef.MOTHERBOARD.size
            for (row in 0 until 3) {
                for (col in 0 until 9) {
                    val px = 8 + col * 18
                    val py = SlotDef.FAT_PLAYER_INV_Y + row * 18
                    child(slotAsComponent(firstInv + row * 9 + col)
                        .positioning(Positioning.absolute(px, py)))
                }
            }
            val firstHotbar = firstInv + 27
            for (col in 0 until 9) {
                val px = 8 + col * 18
                child(slotAsComponent(firstHotbar + col)
                    .positioning(Positioning.absolute(px, SlotDef.FAT_HOTBAR_Y)))
            }
        }

    private fun Int.fixed(): Sizing = Sizing.fixed(this)

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)

        val mbItem = menu.getMotherboardItem()
        val bg: ResourceLocation = when (mbItem?.level ?: 1) {
            2 -> BG_T2
            3 -> BG_T3
            else -> BG_T1
        }
        // 256×256 sheet with the GUI in the top-left 176×222. Pass 256 as the
        // source dims so MC samples that region instead of stretching the full
        // sheet over our 176×222 render area.
        g.blit(bg, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)

        if (mbItem != null) renderSlotHints(g, mbItem)
    }

    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Title at the top of the painted PCB area; suppress the inherited
        // "Inventory" label — the BG art's inventory wells carry it.
        g.drawString(font, title, 8, 6, 0x404040, false)
    }

    /** Greyed slot hints over empty enabled slots. */
    private fun renderSlotHints(g: GuiGraphics, mbItem: MotherboardItem) {
        val defs = SlotDef.MOTHERBOARD
        for (i in defs.indices) {
            val def = defs[i]
            val hintKey = def.background ?: continue
            val sprite = SlotHints.spriteFor(hintKey) ?: continue

            val slot = menu.slots[i]
            if (slot.hasItem()) continue
            if (!mbItem.isSlotEnabled(def.index)) continue

            SlotHints.draw(g, sprite, leftPos + slot.x, topPos + slot.y)
        }
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        renderTooltip(g, mouseX, mouseY)
    }

    companion object {
        private val BG_T1: ResourceLocation = ScalarEvolution.rl("textures/gui/motherboard1.png")
        private val BG_T2: ResourceLocation = ScalarEvolution.rl("textures/gui/motherboard2.png")
        private val BG_T3: ResourceLocation = ScalarEvolution.rl("textures/gui/motherboard3.png")
    }
}
