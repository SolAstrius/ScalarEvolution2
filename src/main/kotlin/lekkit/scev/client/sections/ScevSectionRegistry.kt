/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.neoforged.neoforge.registries.DeferredHolder

/**
 * In-memory map from registered item ids to the id of their creative-tab
 * section. Populated at mod init time — after all items are registered
 * but before the first tab build — by calls from `ScevRegistry`.
 *
 * Two levels of indirection on purpose:
 *   - The registry lives in core (always loaded on client + server) so
 *     tests and server-only code can query the mapping without touching
 *     the client-only [ScevSectionManager] or any GUI classes.
 *   - The section id is a [ResourceLocation], not a [ScevSection]
 *     reference — the section itself is a datapack resource and may not
 *     exist yet (or at all) when [assign] is called. The creative tab
 *     resolves lazily at render time.
 *
 * Thread-safety: writes happen during mod init on the main thread; reads
 * happen from both main and network threads after init. A simple
 * [LinkedHashMap] is fine as long as no mod still holds a write path open
 * when the client tab first renders — NeoForge's init order enforces this.
 */
object ScevSectionRegistry {
    private val itemToSection: MutableMap<ResourceLocation, ResourceLocation> = LinkedHashMap()

    /** Register that [item] belongs to the section with id [sectionId]. */
    @JvmStatic fun assign(item: ResourceLocation, sectionId: ResourceLocation) {
        itemToSection[item] = sectionId
    }

    /** Overload for [DeferredHolder]-style item holders from NeoForge's `DeferredRegister`. */
    @JvmStatic fun assign(holder: DeferredHolder<Item, out Item>, sectionId: ResourceLocation) {
        assign(holder.id, sectionId)
    }

    /**
     * Section id for [item], or null if the item isn't registered or hasn't
     * been assigned. Callers should treat null as "don't put this in the
     * SCEv tab."
     */
    @JvmStatic fun sectionOf(item: Item): ResourceLocation? =
        itemToSection[BuiltInRegistries.ITEM.getKey(item)]

    /** Section id for the given item id, or null. */
    @JvmStatic fun sectionOf(itemId: ResourceLocation): ResourceLocation? =
        itemToSection[itemId]

    /**
     * Unmodifiable view of all current assignments, in insertion order.
     * Tests / debugging — the creative tab builder iterates
     * [BuiltInRegistries.ITEM] directly so it doesn't lose items
     * registered outside this map.
     */
    @JvmStatic fun snapshot(): Map<ResourceLocation, ResourceLocation> =
        java.util.Collections.unmodifiableMap(itemToSection)
}
