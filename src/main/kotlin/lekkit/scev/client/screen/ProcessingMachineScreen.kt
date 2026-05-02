/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import com.mojang.blaze3d.systems.RenderSystem
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
import lekkit.scev.blockentity.ProcessingMachineBlockEntity
import lekkit.scev.client.screen.owo.ScevSurfaces
import lekkit.scev.client.screen.owo.fixed
import lekkit.scev.client.screen.owo.horizontalFlow
import lekkit.scev.client.screen.owo.translatable
import lekkit.scev.client.screen.owo.verticalFlow
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.ProcessingMachineMenu
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * Common screen base for every processing-machine GUI. Layout:
 *
 * ```
 *           ┌── PANEL (centered) ───────────┐
 *           │  Title                        │      ┌── side ──┐
 *           │                               │      │ [exp 0]  │
 *           │  [in]   →   [arrow]   →  [out]│      │ [exp 1]  │
 *           │                               │      │ ...      │
 *           │  ── Inventory ──              │      └──────────┘
 *           │  [9×3 grid]                   │
 *           │  [9×1 hotbar]                 │
 *           └───────────────────────────────┘
 * ```
 *
 * **Centered main panel** — input · progress arrow · output, only.
 * No expansion column inside the main panel; that lives on the
 * detached side strip to the right (mirrors the power-button strip
 * on [ComputerCaseScreen]).
 *
 * **Side panel** — narrow vertical strip with the expansion-card
 * slots, rendered as a small PANEL surface offset to the right of
 * the main panel.
 *
 * **Slot ghosts** — empty slots show a faded "expected item" preview
 * (~40% alpha) computed automatically from the first recipe of this
 * machine's RecipeType. Input slot shows the ingredient, output slot
 * shows the result, expansion slots show a generic Serial-card icon.
 * Subclasses can override [slotGhost] to provide custom hints.
 */
abstract class ProcessingMachineScreen<M : ProcessingMachineMenu>(
    menu: M, inv: Inventory, title: Component,
) : BaseOwoHandledScreen<FlowLayout, M>(menu, inv, title) {

    /** Auto-detected ghost items for input/output, refreshed lazily
     *  on first lookup. Pulls from the level's recipe manager —
     *  client-side recipes are fully synced from server, so this is
     *  cheap and works in singleplayer + multiplayer identically. */
    private var ghostInputs: List<ItemStack>? = null
    private var ghostOutputCached: ItemStack? = null
    private var ghostsResolved: Boolean = false

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { _, _ ->
            verticalFlow(Sizing.fill(100), Sizing.fill(100))
        }

    override fun build(rootComponent: FlowLayout) {
        rootComponent.surface(Surface.BLANK)
        rootComponent.horizontalAlignment(HorizontalAlignment.CENTER)
        rootComponent.verticalAlignment(VerticalAlignment.CENTER)

        // Outer horizontal flow: [main panel] gap [expansion side strip].
        // Vertical-center alignment so the strip floats next to the main
        // panel's "machine row" line, like the power button does relative
        // to the computer case GUI.
        rootComponent.child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
            verticalAlignment(VerticalAlignment.TOP)
            gap(SIDE_GAP)
            child(buildMainPanel())
            // Only render the side strip if there are expansion slots;
            // a 0-slot machine would otherwise show an empty plate.
            if (menu.be.expansionSlotCount > 0) {
                child(buildSidePanel())
            }
        })
    }

    /** The big PANEL — title, processing row, inventory + hotbar. */
    private fun buildMainPanel(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            surface(ScevSurfaces.PANEL)
            padding(Insets.of(PANEL_PAD))
            gap(SECTION_GAP)
            horizontalAlignment(HorizontalAlignment.CENTER)

            child(Components.label(title)
                .color(Color.ofRgb(TITLE_COLOR))
                .margins(Insets.bottom(2)))

            // Processing row, centered inside its parent. Input slots
            // stack vertically when there are multiple (e.g. InkMixer's
            // pigment + binder), single-input machines render the lone
            // slot inline at the same vertical center as the arrow.
            child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
                verticalAlignment(VerticalAlignment.CENTER)
                horizontalAlignment(HorizontalAlignment.CENTER)
                gap(8)
                if (menu.be.inputSlotCount == 1) {
                    child(slotCell(0, ghost = inputGhost(0)))
                } else {
                    child(verticalFlow(Sizing.content(), Sizing.content()).apply {
                        gap(2)
                        for (i in 0 until menu.be.inputSlotCount) {
                            child(slotCell(i, ghost = inputGhost(i)))
                        }
                    })
                }
                child(progressArrow())
                // Output slot index == inputSlotCount on the BE.
                child(slotCell(menu.be.inputSlotCount, ghost = outputGhost()))
            })

            child(buildInventoryGrid())
            child(buildHotbarRow())
        }

    /** Detached PANEL strip holding just the expansion-card slots. */
    private fun buildSidePanel(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            surface(ScevSurfaces.PANEL)
            padding(Insets.of(SIDE_PANEL_PAD))
            gap(2)
            margins(Insets.top(SIDE_PANEL_TOP))
            // Each card slot — same INSET cell style as the main slots,
            // gets a generic "expansion card" ghost so empty slots read
            // as "drop a card here" without needing per-card sprites.
            for (i in 0 until menu.be.expansionSlotCount) {
                child(slotCell(menu.be.firstExpansionSlotIndex + i,
                    ghost = expansionGhost(),
                    tooltip = Component.translatable("tooltip.scev.expansion_slot")))
            }
        }

    /** 18×18 beveled inset wrapping a 16×16 slot, with optional
     *  ghost-item background visible when the slot is empty. */
    protected fun slotCell(slotIndex: Int, ghost: ItemStack? = null, tooltip: Component? = null) =
        Containers.verticalFlow(SLOT_SIZE.fixed, SLOT_SIZE.fixed).apply {
            surface(Surface { ctx, c ->
                // Bevel first.
                ScevSurfaces.INSET.draw(ctx, c)
                // Ghost item when slot is empty — render at low alpha
                // so it reads as "expected here" without competing
                // visually with a real item drop.
                if (ghost != null && menu.slots[slotIndex].item.isEmpty) {
                    drawGhostItem(ctx, c.x() + 1, c.y() + 1, ghost)
                }
            })
            padding(Insets.of(1))
            child(slotAsComponent(slotIndex).apply {
                if (tooltip != null) tooltip(tooltip)
            })
        }

    /** Custom progress arrow — fixed-width cell, amber fill that
     *  extends 0..ARROW_W based on live DataSlots. */
    private fun progressArrow() =
        Containers.verticalFlow(ARROW_W.fixed, ARROW_H.fixed).apply {
            surface(Surface { ctx, c ->
                val x = c.x()
                val y = c.y()
                val w = c.width()
                val h = c.height()
                ctx.fill(x, y, x + w, y + h, ARROW_BG)
                val pct = menu.progress().toFloat() / menu.progressMax().coerceAtLeast(1)
                val fillW = (pct.coerceIn(0f, 1f) * w).toInt()
                if (fillW > 0) {
                    ctx.fill(x, y + 2, x + fillW, y + h - 2, ARROW_FILL)
                }
                val ax = x + w - 4
                val ay = y + h / 2
                ctx.fill(ax,     ay - 2, ax + 1, ay + 3, ARROW_TIP)
                ctx.fill(ax + 1, ay - 1, ax + 2, ay + 2, ARROW_TIP)
                ctx.fill(ax + 2, ay,     ax + 3, ay + 1, ARROW_TIP)
            })
        }

    private fun buildInventoryGrid(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(4))
            val firstInv = menu.be.ioSlotCount + menu.be.expansionSlotCount
            for (row in 0 until 3) {
                child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
                    gap(0)
                    for (col in 0 until 9) {
                        child(slotCell(firstInv + row * 9 + col))
                    }
                })
            }
        }

    private fun buildHotbarRow(): FlowLayout =
        horizontalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(4))
            val firstHotbar = menu.be.ioSlotCount + menu.be.expansionSlotCount + 27
            for (col in 0 until 9) {
                child(slotCell(firstHotbar + col))
            }
        }

    /* ---------------- Ghost lookup ---------------- */

    /** Override to provide a custom ghost for an arbitrary slot index.
     *  Default: null (no override) — the framework's auto-detected
     *  recipe ghost is used for input/output, generic card for expansion. */
    protected open fun slotGhost(slotIndex: Int): ItemStack? = null

    private fun inputGhost(slotIndex: Int = 0): ItemStack? {
        val custom = slotGhost(slotIndex)
        if (custom != null) return custom
        resolveGhosts()
        // Multi-input: pick the corresponding ingredient from the
        // first matching recipe's ingredients list.
        return ghostInputs?.getOrNull(slotIndex)
    }

    private fun outputGhost(): ItemStack? =
        slotGhost(1) ?: resolveGhosts().let { ghostOutputCached }

    private fun expansionGhost(): ItemStack {
        // Generic card hint — shows in every expansion slot regardless
        // of which specific card a player might want to install.
        // Pulls from registry so adding a new card kind doesn't need
        // touching this method.
        return ItemStack(ScevRegistry.SERIAL_PORT_CARD.get())
    }

    /** Compute input/output ghosts from the first recipe of this
     *  machine's RecipeType. Cached after first call — recipes don't
     *  change at runtime. */
    private fun resolveGhosts() {
        if (ghostsResolved) return
        ghostsResolved = true
        val level = Minecraft.getInstance().level ?: return
        val recipes = level.recipeManager.getAllRecipesFor(menu.be.recipeType)
        val firstRecipe = recipes.firstOrNull()?.value() ?: return
        // Per-slot ghost: ingredient[i] for input slot i.
        ghostInputs = firstRecipe.ingredients.map {
            it.items.firstOrNull()?.copy() ?: ItemStack.EMPTY
        }
        ghostOutputCached = firstRecipe.result.copy()
    }

    /** Render an item at low alpha for the slot-ghost overlay. */
    private fun drawGhostItem(ctx: GuiGraphics, x: Int, y: Int, stack: ItemStack) {
        // setShaderColor before renderItem applies to the item's
        // baked-model render. Reset after so we don't leak alpha to
        // siblings.
        RenderSystem.enableBlend()
        RenderSystem.setShaderColor(1f, 1f, 1f, GHOST_ALPHA)
        ctx.renderItem(stack, x, y)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.disableBlend()
    }

    /* ---------------- Lifecycle ---------------- */

    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderTransparentBackground(g)
    }

    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {}

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        renderTooltip(g, mouseX, mouseY)
    }

    companion object {
        protected const val PANEL_PAD: Int = 8
        protected const val SECTION_GAP: Int = 6
        protected const val SLOT_SIZE: Int = 18
        protected const val TITLE_COLOR: Int = 0xC0C0C0

        /** Gap between the main panel and the side expansion strip. */
        private const val SIDE_GAP: Int = 4
        /** Padding inside the side panel — tighter than the main one. */
        private const val SIDE_PANEL_PAD: Int = 4
        /** Vertical offset of the side panel relative to the main panel
         *  top edge — aligns its top with the main panel's processing
         *  row, not its title bar. */
        private const val SIDE_PANEL_TOP: Int = 18

        // Progress arrow sizing.
        private const val ARROW_W: Int = 24
        private const val ARROW_H: Int = 16
        private const val ARROW_BG: Int = 0xFF202020.toInt()
        private const val ARROW_FILL: Int = 0xFFE0C040.toInt()
        private const val ARROW_TIP: Int = 0xFFFFFFFF.toInt()

        /** Alpha for the slot-ghost overlay. ~40% — clearly readable
         *  as an item silhouette but visually subordinate to the
         *  bevel + any real item that gets dropped in. */
        private const val GHOST_ALPHA: Float = 0.4f
    }
}
