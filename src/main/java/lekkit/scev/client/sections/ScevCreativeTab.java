/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import lekkit.scev.items.FlashFirmware;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.PreloadedNvmeItem;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Sectioned creative-tab renderer + item layout. Adapted from the Simulated
 * project's {@code SimulatedCreativeTab} under MPL-2.0 — same algorithm,
 * different plumbing to fit SCEv's {@link ScevSectionRegistry} model.
 *
 * <h2>Layout algorithm</h2>
 *
 * Assumes the creative tab displays items in 9-wide rows (Minecraft's fixed
 * width). We flatten sections into one item stream with {@link ItemStack#EMPTY}
 * padding so each section occupies whole rows and a single empty row sits
 * between consecutive sections for banner rendering. The banner itself is
 * drawn via a render mixin at a computed Y that tracks the scroll position.
 *
 * <pre>
 *   row 0         ← banner-1 placeholder row (empty slots)
 *   rows 1..R1    ← section 1 items, padded to a full 9-multiple
 *   rows R1+1     ← banner-2 placeholder row
 *   rows R1+2..R2 ← section 2 items
 *   ...
 * </pre>
 *
 * <h2>Scroll tracking</h2>
 *
 * {@link #CURRENT_ROW} holds the row index currently at the top of the
 * visible area — updated by the {@code ItemPickerMenu#scrollTo} mixin. The
 * renderer converts each section's stored Y into a screen-space row and
 * skips banners outside the visible 5-row window.
 */
public final class ScevCreativeTab {
    /** Banner sprite width matches the tab's inner item-grid width (9 * 18). */
    public static final int BANNER_WIDTH = 162;
    /** Banner sprite height = one row. Keeps layout math trivial. */
    public static final int BANNER_HEIGHT = 18;

    /** Updated by the scroll mixin; read by {@link #renderBanners}. */
    public static volatile int CURRENT_ROW = 0;

    /** Per-section absolute Y in row units, rebuilt each {@link #processItems}. */
    public static final Object2IntOpenHashMap<ResourceLocation> SECTION_Y_VALUES = new Object2IntOpenHashMap<>();

    /**
     * The SCEv main creative tab instance. Set after construction by
     * {@code ScevRegistry} so the tab + screen mixins can identify "our" tab
     * among all tabs without a hard dep on registry classes. Nullable during
     * very early mod init; compare via reference equality.
     */
    public static volatile net.minecraft.world.item.CreativeModeTab MAIN_TAB_INSTANCE;

    /** @return true iff {@code tab} is SCEv's main tab (and its instance is registered). */
    public static boolean isScevMainTab(net.minecraft.world.item.CreativeModeTab tab) {
        return tab != null && tab == MAIN_TAB_INSTANCE;
    }

    private ScevCreativeTab() {}

    // -----------------------------------------------------------------
    // Item-list build
    // -----------------------------------------------------------------

    /**
     * Iterate all registered items, group by {@link ScevSectionRegistry}
     * assignment, emit in section priority order with padding for banner
     * rows. Called from a {@link net.minecraft.world.item.CreativeModeTab}
     * {@code displayItems} callback (or via the creative-tab mixin).
     *
     * @param displayItems Accepts both items and {@link ItemStack#EMPTY}
     *                     padding placeholders.
     * @param searchItems  Accepts items only; padding skipped.
     */
    public static void processItems(Consumer<ItemStack> displayItems, Consumer<ItemStack> searchItems) {
        // Group registered items by their assigned section. Items with no
        // assignment are skipped silently — the mod owner decides via
        // ScevSectionRegistry.assign whether an item belongs in our tab.
        Map<ResourceLocation, List<ItemStack>> bySection = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation sectionId = ScevSectionRegistry.sectionOf(item);
            if (sectionId == null) continue;
            List<ItemStack> bucket = bySection.computeIfAbsent(sectionId, k -> new ArrayList<>());
            if (item instanceof FlashItem) {
                // One stack per firmware kind (BLANK, LINUX, OPENSBI, OPEN_FW,
                // BLINKY) so every built-in firmware is discoverable in creative.
                // No recipe exists for non-BLINKY stamps today — this is the
                // only survival-adjacent way to obtain them.
                for (FlashFirmware kind : FlashFirmware.values()) {
                    ItemStack stack = new ItemStack(item);
                    stack.set(ScevDataComponents.FIRMWARE_KIND.get(), kind);
                    bucket.add(stack);
                }
            } else if (item instanceof PreloadedNvmeItem) {
                // One stack per registered disk template (BUILDROOT, ALPINE,
                // and whatever else gets added later) so every preloaded
                // distro surfaces in creative without needing a separate
                // item registration per template.
                for (ResourceLocation templateId : DiskTemplateRegistry.ids()) {
                    ItemStack stack = new ItemStack(item);
                    stack.set(ScevDataComponents.DISK_TEMPLATE.get(), templateId);
                    bucket.add(stack);
                }
            } else {
                bucket.add(item.getDefaultInstance());
            }
        }

        // Build an ordered list of (sectionId, section, items) triples
        // sorted by section priority. Unknown section ids (assigned but
        // not loaded as a JSON) are skipped — their items vanish from the
        // tab but a server restart / correct JSON would restore them.
        ScevSectionManager mgr = ScevSectionManager.instance();
        List<SectionGroup> groups = new ArrayList<>();
        bySection.forEach((id, items) -> {
            ScevSection section = mgr.get(id);
            if (section == null) return;
            groups.add(new SectionGroup(id, section, items));
        });
        groups.sort(Comparator.comparing(g -> g.section));

        // Reset Y table for this build.
        SECTION_Y_VALUES.clear();

        // Leading banner row (9 empty slots) — sits above the first section
        // and hosts the first banner.
        for (int i = 0; i < 9; i++) displayItems.accept(ItemStack.EMPTY);

        int y = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            SectionGroup g = groups.get(gi);
            for (ItemStack stack : g.items) {
                displayItems.accept(stack);
                searchItems.accept(stack);
            }
            SECTION_Y_VALUES.put(g.id, y);

            int rowCount = (int) Math.ceil(g.items.size() / 9.0);
            // +1 reserves the banner row for the *next* section; the
            // last section doesn't need a trailing banner row so we skip
            // the padding block on the final iteration below.
            y += rowCount + 1;

            if (gi == groups.size() - 1) break;

            // Pad to end-of-row plus one additional empty row for the
            // next section's banner. Always emit at least one full empty
            // row (9 slots) — simpler than conditional logic and visually
            // matches Simulated's original behavior.
            int rem = g.items.size() % 9;
            int padding = (rem == 0) ? 9 : (9 - rem) + 9;
            for (int i = 0; i < padding; i++) displayItems.accept(ItemStack.EMPTY);
        }
    }

    // -----------------------------------------------------------------
    // Banner render
    // -----------------------------------------------------------------

    /**
     * Draw a banner for every currently-visible section. Called from the
     * {@code CreativeModeInventoryScreen#render} mixin at TAIL.
     */
    public static void renderBanners(CreativeModeInventoryScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        // Layout geometry matches vanilla's tab: the item grid starts at
        // (leftPos + 8, topPos + 17). Banners align to the grid width.
        CreativeModeInventoryAccess acc = (CreativeModeInventoryAccess) screen;
        int left = acc.scev$getLeftPos() + 8;
        int top = acc.scev$getTopPos() + 17;

        PoseStack ps = graphics.pose();
        ps.pushPose();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        ps.translate(left, top, 0);

        ScevSectionManager mgr = ScevSectionManager.instance();
        for (ScevSection section : mgr.sorted()) {
            ResourceLocation id = mgr.idOf(section);
            if (id == null) continue;
            int yRows = SECTION_Y_VALUES.getOrDefault(id, -1);
            if (yRows < 0) continue; // section has no items assigned → no banner

            int sectionRow = yRows - CURRENT_ROW;
            if (sectionRow < 0 || sectionRow > 4) continue; // outside visible window

            int x = 0;
            int y = sectionRow * BANNER_HEIGHT;

            graphics.blitSprite(section.sprite(), x, y, BANNER_WIDTH, BANNER_HEIGHT);

            ScevSection.Title title = section.title();
            Font font = Minecraft.getInstance().font;
            int textWidth = font.width(title.text());

            // Title background fill, sized to text.
            graphics.fill(x + 2, y + 2, x + textWidth + 8, y + BANNER_HEIGHT - 2, title.background());

            drawAuraText(graphics, title.text(), title.secondaryOrDerived(), title.color(), x + 5, y + 5);
        }

        ps.popPose();
        RenderSystem.disableDepthTest();
    }

    /**
     * Two-pass text drawing: an outer glow (darker color, shadowed) behind
     * a brighter inner text scissored to a tight bounding box. The two-pass
     * trick yields a subtle halo without requiring a shader.
     */
    public static void drawAuraText(GuiGraphics graphics, Component text, int outerArgb, int innerArgb, int x, int y) {
        Font font = Minecraft.getInstance().font;
        Window window = Minecraft.getInstance().getWindow();
        float scale = (float) window.getGuiScale();

        graphics.drawString(font, text, x, y, outerArgb, true);

        PoseStack ps = graphics.pose();
        ps.pushPose();
        ps.translate(0, 0, 1);
        Matrix4f pose = ps.last().pose();
        Vector3f position = pose.transformPosition(new Vector3f(x, y, 0));
        Vector3f corner = pose.transformPosition(new Vector3f(x + font.width(text), y + font.lineHeight / 1.8f, 0));

        position.mul(scale);
        corner.mul(scale);
        int height = (int) (corner.y - position.y);
        int width = (int) (corner.x - position.x);
        RenderSystem.enableScissor(
                (int) position.x,
                window.getHeight() - (int) position.y - height,
                width,
                height
        );

        graphics.drawString(font, text, x, y, innerArgb, false);

        RenderSystem.disableScissor();
        ps.popPose();
    }

    private record SectionGroup(ResourceLocation id, ScevSection section, List<ItemStack> items) {}
}
