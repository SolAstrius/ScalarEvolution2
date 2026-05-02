/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen.owo

import io.wispforest.owo.ui.core.Surface

/**
 * Custom owo [Surface]s in the scev palette — drawn programmatically from
 * the exact pixel values used by `mcu_board.png` and friends. Built by
 * sampling the source PNG (see comment blocks below the palette) and
 * folding the layered fills into a single `Surface` lambda so we can drop
 * the bundled GUI textures entirely without losing the look.
 *
 * One paint per surface = a handful of [io.wispforest.owo.ui.core.OwoUIDrawContext.fill]
 * calls — orders of magnitude cheaper than nine-patch blits and trivially
 * tweakable through the named color constants.
 *
 * Why these palettes specifically:
 *
 * **PANEL** (raised tile, outside-in):
 *   - 1-px black outline                    `#000000`
 *   - 1-px highlight on top + left           `#808080`
 *   - 1-px shadow on bottom + right          `#2B2B2B`
 *   - fill                                   `#636363`
 *
 * **INSET** (recessed slot well, outside-in):
 *   - 1-px shadow on top + left              `#373737`
 *   - 1-px highlight on bottom + right       `#808080`
 *   - 1-px bright accent at BR corner        `#8B8B8B`
 *   - fill                                   `#464646`
 *
 * The single bright corner pixel at BR is what makes the originals read as
 * a real beveled depression and not a flat dark square — without it, the
 * shadow + highlight just look like two parallel lines.
 */
object ScevSurfaces {
    // Panel palette
    private const val PANEL_OUTLINE   = 0xFF000000.toInt()
    private const val PANEL_HIGHLIGHT = 0xFF808080.toInt()
    private const val PANEL_SHADOW    = 0xFF2B2B2B.toInt()
    private const val PANEL_FILL      = 0xFF636363.toInt()

    // Slot well palette
    private const val INSET_SHADOW    = 0xFF373737.toInt()
    private const val INSET_HIGHLIGHT = 0xFF808080.toInt()
    private const val INSET_ACCENT    = 0xFF8B8B8B.toInt()
    private const val INSET_FILL      = 0xFF464646.toInt()

    /**
     * Outer panel — chamfered-corner black-outlined raised bevel. Direct
     * replica of `mcu_board.png`'s frame: 1-px diagonal cut at each corner
     * (the outer black outline staircases inward by 1 px), 2-px highlight
     * band on top + left, 2-px shadow band on bottom + right.
     *
     * Skipped: the slight bevel-widening near the curved corners (3 px
     * tall instead of 2). Approximating with a clean 2-px band is close
     * enough — the eye reads the chamfer + 2-px bevel as the same "rounded
     * raised plate" as the original 3-step-wide curve.
     *
     * Skipped: the original PNG's single `#C6C6C6` accent pixel at the BL
     * corner where the highlight meets the shadow. It's a hand-pixeled
     * artifact that reads as "stray bright dot" more often than as a
     * smooth corner glow — dropping it keeps the four corners uniform.
     */
    val PANEL: Surface = Surface { ctx, c ->
        val x = c.x()
        val y = c.y()
        val w = c.width()
        val h = c.height()

        // Inner fill (everything except a 2-px frame on each side gets fill).
        ctx.fill(x + 2, y + 2, x + w - 2, y + h - 2, PANEL_FILL)

        // ── Black outline, chamfered at each corner ─────────────────────
        // Straight edges skip the outermost 2 corner pixels at each end.
        ctx.fill(x + 2, y, x + w - 2, y + 1, PANEL_OUTLINE)            // top
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, PANEL_OUTLINE)    // bottom
        ctx.fill(x, y + 2, x + 1, y + h - 2, PANEL_OUTLINE)            // left
        ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, PANEL_OUTLINE)    // right
        // Chamfer pixels (diagonal step at each corner).
        ctx.fill(x + 1, y + 1, x + 2, y + 2, PANEL_OUTLINE)                    // TL
        ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, PANEL_OUTLINE)            // TR
        ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, PANEL_OUTLINE)            // BL
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, PANEL_OUTLINE)    // BR

        // ── Highlight bevel (top + left, 2 px wide post-chamfer) ────────
        ctx.fill(x + 2, y + 1, x + w - 2, y + 2, PANEL_HIGHLIGHT)              // top inner row
        ctx.fill(x + 2, y + 2, x + w - 2, y + 3, PANEL_HIGHLIGHT)              // top second row
        ctx.fill(x + 1, y + 2, x + 2, y + h - 2, PANEL_HIGHLIGHT)              // left inner col
        ctx.fill(x + 2, y + 2, x + 3, y + h - 2, PANEL_HIGHLIGHT)              // left second col

        // ── Shadow bevel (bottom + right, 2 px wide post-chamfer) ───────
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, PANEL_SHADOW)         // bottom inner row
        ctx.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, PANEL_SHADOW)         // bottom second row
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, PANEL_SHADOW)         // right inner col
        ctx.fill(x + w - 3, y + 2, x + w - 2, y + h - 2, PANEL_SHADOW)         // right second col

        // ── Re-paint chamfer pixels black (the highlight/shadow rect fills
        // overlap them; restore the chamfer cut). ──────────────────────────
        ctx.fill(x + 1, y + 1, x + 2, y + 2, PANEL_OUTLINE)                    // TL
        ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, PANEL_OUTLINE)            // TR
        ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, PANEL_OUTLINE)            // BL
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, PANEL_OUTLINE)    // BR
    }

    /**
     * Recessed slot well — top + left shadow, bottom + right highlight,
     * with a 1-px brighter accent at the bottom-right corner. Tiles flush
     * with adjacent INSET cells to form the inventory-grid look from
     * `mcu_board.png` (where each slot is 18 px and the BR highlight of
     * one cell sits next to the TL shadow of the next).
     */
    val INSET: Surface = Surface { ctx, c ->
        val x = c.x()
        val y = c.y()
        val w = c.width()
        val h = c.height()

        // Fill.
        ctx.fill(x, y, x + w, y + h, INSET_FILL)

        // TL shadow (1 px).
        ctx.fill(x, y, x + w, y + 1, INSET_SHADOW)         // top
        ctx.fill(x, y, x + 1, y + h, INSET_SHADOW)         // left

        // BR highlight (1 px).
        ctx.fill(x, y + h - 1, x + w, y + h, INSET_HIGHLIGHT)  // bottom
        ctx.fill(x + w - 1, y, x + w, y + h, INSET_HIGHLIGHT)  // right

        // BR corner accent — single brighter pixel that gives the well its
        // depth read. Without this the bevel looks like flat parallel lines.
        ctx.fill(x + w - 1, y + h - 1, x + w, y + h, INSET_ACCENT)
    }
}
