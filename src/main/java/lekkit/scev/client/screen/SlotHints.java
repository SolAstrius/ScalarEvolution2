/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.menu.SlotDef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws greyed-out sprite hints behind empty slots so players can see what
 * component type each slot expects. The hint sprites live under the GUI
 * sprite atlas at {@code textures/gui/sprites/slot_hint/*.png} — anything
 * in that tree is auto-registered by NeoForge's {@code GuiSpriteManager}
 * and blitted via {@link GuiGraphics#blitSprite}, so there is no atlas
 * JSON or manual texture loading involved.
 *
 * <p>Mapping from {@link SlotDef#background()} (a short slot-type name
 * like {@code "slot_cpu"}) to sprite id is table-driven here. Adding a new
 * slot type is a one-line addition plus a PNG drop.
 */
public final class SlotHints {

    /** Alpha for the hint overlay: subtle, visible, not distracting. */
    private static final float HINT_ALPHA = 0.35F;

    /** Slot sprite is 16x16 at draw position (slot.x, slot.y) within the gui. */
    private static final int HINT_SIZE = 16;

    /**
     * {@link SlotDef#background()} value → GUI sprite {@link ResourceLocation}.
     * Null values (no hint for a slot type) are allowed at lookup time;
     * missing keys are treated the same.
     */
    private static final Map<String, ResourceLocation> SPRITES = new HashMap<>();
    static {
        register("slot_motherboard", "motherboard");
        register("slot_cpu",         "cpu");
        register("slot_flash",       "flash");
        register("slot_ram",         "ram");
        register("slot_m2",          "m2");
        register("slot_pci",         "pci");
    }

    private static void register(String slotTypeKey, String spriteName) {
        SPRITES.put(slotTypeKey, ScalarEvolution.rl("slot_hint/" + spriteName));
    }

    private SlotHints() {}

    /**
     * @return the sprite id for a given {@link SlotDef#background()}, or
     *         {@code null} if that slot type has no registered hint.
     */
    public static ResourceLocation spriteFor(String slotTypeKey) {
        return slotTypeKey == null ? null : SPRITES.get(slotTypeKey);
    }

    /**
     * Draw a single hint sprite at screen position {@code (x, y)} with the
     * standard alpha. Caller is responsible for having decided that the
     * target slot is empty + enabled; this method just draws.
     */
    public static void draw(GuiGraphics graphics, ResourceLocation sprite, int x, int y) {
        RenderSystem.enableBlend();
        graphics.setColor(1.0F, 1.0F, 1.0F, HINT_ALPHA);
        graphics.blitSprite(sprite, x, y, HINT_SIZE, HINT_SIZE);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
