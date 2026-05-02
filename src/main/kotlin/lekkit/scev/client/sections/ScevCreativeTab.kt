/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections

import com.mojang.blaze3d.systems.RenderSystem
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.function.Consumer
import kotlin.math.ceil
import lekkit.scev.items.FlashFirmware
import lekkit.scev.items.FlashItem
import lekkit.scev.items.PreloadedNvmeItem
import lekkit.scev.machine.storage.DiskTemplateRegistry
import lekkit.scev.main.ScevDataComponents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.joml.Vector3f

/**
 * Sectioned creative-tab renderer + item layout. Adapted from the Simulated
 * project's `SimulatedCreativeTab` under MPL-2.0 — same algorithm,
 * different plumbing to fit SCEv's [ScevSectionRegistry] model.
 *
 * **Layout algorithm.** Assumes the creative tab displays items in 9-wide
 * rows. We flatten sections into one item stream with [ItemStack.EMPTY]
 * padding so each section occupies whole rows and a single empty row sits
 * between consecutive sections for banner rendering. The banner itself is
 * drawn via a render mixin at a computed Y that tracks the scroll position.
 *
 * ```
 *   row 0         <- banner-1 placeholder row (empty slots)
 *   rows 1..R1    <- section 1 items, padded to a full 9-multiple
 *   rows R1+1     <- banner-2 placeholder row
 *   rows R1+2..R2 <- section 2 items
 *   ...
 * ```
 *
 * **Scroll tracking.** [CURRENT_ROW] holds the row index currently at the
 * top of the visible area — updated by the `ItemPickerMenu#scrollTo` mixin.
 * The renderer converts each section's stored Y into a screen-space row
 * and skips banners outside the visible 5-row window.
 */
object ScevCreativeTab {
    /** Banner sprite width matches the tab's inner item-grid width (9 × 18). */
    const val BANNER_WIDTH: Int = 162

    /** Banner sprite height = one row. Keeps layout math trivial. */
    const val BANNER_HEIGHT: Int = 18

    /** Updated by the scroll mixin; read by [renderBanners]. */
    @Volatile @JvmField var CURRENT_ROW: Int = 0

    /** Per-section absolute Y in row units, rebuilt each [processItems]. */
    @JvmField val SECTION_Y_VALUES: Object2IntOpenHashMap<ResourceLocation> = Object2IntOpenHashMap()

    /**
     * The SCEv main creative tab instance. Set after construction by
     * `ScevRegistry` so the tab + screen mixins can identify "our" tab
     * among all tabs without a hard dep on registry classes. Nullable
     * during very early mod init; compare via reference equality.
     */
    @Volatile @JvmField var MAIN_TAB_INSTANCE: CreativeModeTab? = null

    /** True iff [tab] is SCEv's main tab (and its instance is registered). */
    @JvmStatic fun isScevMainTab(tab: CreativeModeTab?): Boolean =
        tab != null && tab === MAIN_TAB_INSTANCE

    /* ----- Item-list build ------------------------------------------------ */

    /**
     * Iterate all registered items, group by [ScevSectionRegistry]
     * assignment, emit in section priority order with padding for banner
     * rows.
     *
     * @param displayItems Accepts both items and [ItemStack.EMPTY] padding.
     * @param searchItems  Accepts items only; padding skipped.
     */
    @JvmStatic fun processItems(displayItems: Consumer<ItemStack>, searchItems: Consumer<ItemStack>) {
        // Group registered items by their assigned section. Items with no
        // assignment are skipped silently — the mod owner decides via
        // ScevSectionRegistry.assign whether an item belongs in our tab.
        val bySection = HashMap<ResourceLocation, MutableList<ItemStack>>()
        for (item in BuiltInRegistries.ITEM) {
            val sectionId = ScevSectionRegistry.sectionOf(item) ?: continue
            val bucket = bySection.getOrPut(sectionId) { ArrayList() }
            when {
                item is FlashItem -> {
                    // One stack per firmware kind (BLANK / LINUX / OPENSBI / OPEN_FW /
                    // BLINKY) so every built-in firmware is discoverable in creative.
                    // No recipe exists for non-BLINKY stamps today — this is the
                    // only survival-adjacent way to obtain them.
                    for (kind in FlashFirmware.values()) {
                        val stack = ItemStack(item)
                        stack.set(ScevDataComponents.FIRMWARE_KIND.get(), kind)
                        bucket += stack
                    }
                }
                item is PreloadedNvmeItem -> {
                    // One stack per registered disk template (BUILDROOT, ALPINE, …)
                    // so every preloaded distro surfaces without needing a separate
                    // item registration per template.
                    for (templateId in DiskTemplateRegistry.ids()) {
                        val stack = ItemStack(item)
                        stack.set(ScevDataComponents.DISK_TEMPLATE.get(), templateId)
                        bucket += stack
                    }
                }
                else -> bucket += item.defaultInstance
            }
        }

        // Build (sectionId, section, items) triples sorted by priority.
        // Unknown section ids (assigned but not loaded as JSON) are skipped —
        // their items vanish from the tab; correct JSON would restore them.
        val mgr = ScevSectionManager.instance()
        val groups = ArrayList<SectionGroup>()
        bySection.forEach { (id, items) ->
            val section = mgr.get(id) ?: return@forEach
            groups += SectionGroup(id, section, items)
        }
        groups.sortBy { it.section }

        SECTION_Y_VALUES.clear()

        // Leading banner row (9 empty slots) — sits above the first section.
        repeat(9) { displayItems.accept(ItemStack.EMPTY) }

        var y = 0
        for ((gi, g) in groups.withIndex()) {
            for (stack in g.items) {
                displayItems.accept(stack)
                searchItems.accept(stack)
            }
            SECTION_Y_VALUES.put(g.id, y)

            val rowCount = ceil(g.items.size / 9.0).toInt()
            // +1 reserves the banner row for the *next* section; the last
            // section doesn't need a trailing banner row so we skip the
            // padding block on the final iteration below.
            y += rowCount + 1

            if (gi == groups.size - 1) break

            // Pad to end-of-row plus one additional empty row for the next
            // section's banner. Always emit at least one full empty row —
            // simpler than conditional logic and matches Simulated's
            // original behavior.
            val rem = g.items.size % 9
            val padding = if (rem == 0) 9 else (9 - rem) + 9
            repeat(padding) { displayItems.accept(ItemStack.EMPTY) }
        }
    }

    /* ----- Banner render -------------------------------------------------- */

    /** Draw a banner for every currently-visible section. */
    @JvmStatic fun renderBanners(screen: CreativeModeInventoryScreen, graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Layout geometry matches vanilla's tab: item grid starts at
        // (leftPos + 8, topPos + 17). Banners align to grid width.
        val acc = screen as CreativeModeInventoryAccess
        val left = acc.`scev$getLeftPos`() + 8
        val top = acc.`scev$getTopPos`() + 17

        val ps = graphics.pose()
        ps.pushPose()
        RenderSystem.enableDepthTest()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        ps.translate(left.toFloat(), top.toFloat(), 0f)

        val mgr = ScevSectionManager.instance()
        for (section in mgr.sorted()) {
            val id = mgr.idOf(section) ?: continue
            val yRows = SECTION_Y_VALUES.getOrDefault(id, -1)
            if (yRows < 0) continue                            // no items → no banner

            val sectionRow = yRows - CURRENT_ROW
            if (sectionRow < 0 || sectionRow > 4) continue     // outside visible window

            val x = 0
            val y = sectionRow * BANNER_HEIGHT

            graphics.blitSprite(section.sprite, x, y, BANNER_WIDTH, BANNER_HEIGHT)

            val title = section.title
            val font = Minecraft.getInstance().font
            val textWidth = font.width(title.text)

            // Title background fill, sized to text.
            graphics.fill(x + 2, y + 2, x + textWidth + 8, y + BANNER_HEIGHT - 2, title.background)

            drawAuraText(graphics, title.text, title.secondaryOrDerived(), title.color, x + 5, y + 5)
        }

        ps.popPose()
        RenderSystem.disableDepthTest()
    }

    /**
     * Two-pass text drawing: outer glow (darker color, shadowed) behind a
     * brighter inner text scissored to a tight bounding box. Yields a
     * subtle halo without requiring a shader.
     */
    @JvmStatic fun drawAuraText(graphics: GuiGraphics, text: Component, outerArgb: Int, innerArgb: Int, x: Int, y: Int) {
        val font = Minecraft.getInstance().font
        val window = Minecraft.getInstance().window
        val scale = window.guiScale.toFloat()

        graphics.drawString(font, text, x, y, outerArgb, true)

        val ps = graphics.pose()
        ps.pushPose()
        ps.translate(0f, 0f, 1f)
        val pose = ps.last().pose()
        val position = pose.transformPosition(Vector3f(x.toFloat(), y.toFloat(), 0f))
        val corner = pose.transformPosition(Vector3f(
            (x + font.width(text)).toFloat(),
            (y + font.lineHeight / 1.8f),
            0f,
        ))

        position.mul(scale)
        corner.mul(scale)
        val height = (corner.y - position.y).toInt()
        val width = (corner.x - position.x).toInt()
        RenderSystem.enableScissor(
            position.x.toInt(),
            window.height - position.y.toInt() - height,
            width,
            height,
        )

        graphics.drawString(font, text, x, y, innerArgb, false)

        RenderSystem.disableScissor()
        ps.popPose()
    }

    private data class SectionGroup(
        val id: ResourceLocation,
        val section: ScevSection,
        val items: List<ItemStack>,
    )
}
