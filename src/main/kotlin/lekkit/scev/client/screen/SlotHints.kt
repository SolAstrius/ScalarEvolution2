/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import com.mojang.blaze3d.systems.RenderSystem
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.menu.SlotDef
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation

/**
 * Draws greyed-out sprite hints behind empty slots so players see what
 * component type each slot expects. The hint sprites live under the GUI
 * sprite atlas at `textures/gui/sprites/slot_hint/<name>.png` — anything in
 * that tree is auto-registered by NeoForge's `GuiSpriteManager` and
 * blitted via [GuiGraphics.blitSprite], so there is no atlas JSON or
 * manual texture loading involved.
 *
 * Mapping from [SlotDef.background] (a short slot-type name like
 * `"slot_cpu"`) to sprite id is table-driven here. Adding a new slot
 * type is a one-line addition plus a PNG drop.
 */
object SlotHints {
    /** Alpha for the hint overlay: subtle, visible, not distracting. */
    private const val HINT_ALPHA: Float = 0.35F

    /** Slot sprite is 16×16 at draw position (slot.x, slot.y) within the gui. */
    private const val HINT_SIZE: Int = 16

    /**
     * [SlotDef.background] value → GUI sprite id. Missing keys / null
     * values mean "no hint for this slot type."
     */
    private val SPRITES: Map<String, ResourceLocation> = listOf(
        "slot_motherboard" to "motherboard",
        "slot_cpu"         to "cpu",
        "slot_flash"       to "flash",
        "slot_ram"         to "ram",
        "slot_m2"          to "m2",
        "slot_pci"         to "pci",
    ).associate { (key, sprite) -> key to ScalarEvolution.rl("slot_hint/$sprite") }

    /** Sprite id for [slotTypeKey], or null if that slot type has no registered hint. */
    @JvmStatic fun spriteFor(slotTypeKey: String?): ResourceLocation? =
        if (slotTypeKey == null) null else SPRITES[slotTypeKey]

    /**
     * Draw a single hint sprite at screen position ([x], [y]) with the
     * standard alpha. Caller is responsible for having decided that the
     * target slot is empty + enabled; this method just draws.
     */
    @JvmStatic fun draw(graphics: GuiGraphics, sprite: ResourceLocation, x: Int, y: Int) {
        RenderSystem.enableBlend()
        graphics.setColor(1.0F, 1.0F, 1.0F, HINT_ALPHA)
        graphics.blitSprite(sprite, x, y, HINT_SIZE, HINT_SIZE)
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F)
        RenderSystem.disableBlend()
    }
}
