/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory map from registered item ids to the id of their creative-tab
 * section. Populated at mod init time — after all items are registered but
 * before the first tab build — by calls from {@link lekkit.scev.main.ScevRegistry}.
 *
 * <p>Two levels of indirection on purpose:
 * <ul>
 *   <li>The registry lives in core (always loaded on client + server) so
 *       tests and server-only code can query the mapping without touching
 *       the client-only {@link ScevSectionManager} or any GUI classes.</li>
 *   <li>The section id is a {@link ResourceLocation}, not a {@link ScevSection}
 *       reference — the section itself is a datapack resource and may not
 *       exist yet (or at all) when {@link #assign} is called. The creative
 *       tab resolves lazily at render time.</li>
 * </ul>
 *
 * <p>Thread-safety: writes happen during mod init on the main thread;
 * reads happen from both main and network threads after init. A simple
 * {@link LinkedHashMap} is fine as long as no mod still holds a write path
 * open when the client tab first renders — NeoForge's init order enforces
 * this.
 */
public final class ScevSectionRegistry {
    private static final Map<ResourceLocation, ResourceLocation> ITEM_TO_SECTION = new LinkedHashMap<>();

    private ScevSectionRegistry() {}

    /** Register that {@code item} belongs to the section with id {@code sectionId}. */
    public static void assign(ResourceLocation item, ResourceLocation sectionId) {
        ITEM_TO_SECTION.put(item, sectionId);
    }

    /** Overload for {@link DeferredHolder}-style item holders from NeoForge's {@code DeferredRegister}. */
    public static void assign(DeferredHolder<Item, ? extends Item> holder, ResourceLocation sectionId) {
        assign(holder.getId(), sectionId);
    }

    /**
     * @return section id for {@code item}, or {@code null} if the item is
     *         not registered or hasn't been assigned. Callers should treat
     *         {@code null} as "don't put this in the SCEv tab."
     */
    public static @Nullable ResourceLocation sectionOf(Item item) {
        return ITEM_TO_SECTION.get(BuiltInRegistries.ITEM.getKey(item));
    }

    /** @return section id for the given item id, or {@code null}. */
    public static @Nullable ResourceLocation sectionOf(ResourceLocation itemId) {
        return ITEM_TO_SECTION.get(itemId);
    }

    /**
     * @return an unmodifiable view of all current assignments, in insertion
     *         order. Intended for tests / debugging; the creative tab builder
     *         iterates {@link BuiltInRegistries#ITEM} directly so it doesn't
     *         lose items registered outside this map.
     */
    public static Map<ResourceLocation, ResourceLocation> snapshot() {
        return Collections.unmodifiableMap(ITEM_TO_SECTION);
    }
}
