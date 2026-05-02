/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import org.slf4j.LoggerFactory

/**
 * Client-side resource manager that loads [ScevSection] definitions from
 * `assets/<any_modid>/scev/sections/<filename>.json` on /reload.
 *
 * Multiple mods may drop JSONs under their own namespaces — all are
 * aggregated into one global list. Assignments via [ScevSectionRegistry]
 * are independent; a section is only visible in the tab if at least one
 * item is assigned to it. An item assigned to an unknown section id is
 * skipped silently at render time (not fatal — the mod owning that
 * section may not be installed).
 *
 * Registered on `RegisterClientReloadListenersEvent` from the mod's
 * client-side bus wiring.
 */
class ScevSectionManager private constructor() :
    SimpleJsonResourceReloadListener(GsonBuilder().create(), DIRECTORY) {

    /** id -> section, mutated on reload, read from the GUI thread. */
    private var sections: Map<ResourceLocation, ScevSection> = emptyMap()

    /** Cached sorted list, invalidated on reload; read from the GUI thread. */
    private var sortedCache: List<ScevSection> = emptyList()

    override fun apply(raw: Map<ResourceLocation, JsonElement>, mgr: ResourceManager, profiler: ProfilerFiller) {
        val parsed = HashMap<ResourceLocation, ScevSection>()
        raw.forEach { (id, json) ->
            ScevSection.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial { err -> LOG.error("Failed to parse scev section {}: {}", id, err) }
                .ifPresent { section -> parsed[id] = section }
        }
        sections = parsed.toMap()
        sortedCache = parsed.values.sorted().toList()
        LOG.info("Loaded {} scev creative-tab sections", parsed.size)
    }

    /** Section for [id], or null if not loaded / unknown. */
    fun get(id: ResourceLocation): ScevSection? = sections[id]

    /**
     * id-keyed map of loaded sections. Not guaranteed to be sorted; use
     * [sorted] when iteration order matters.
     */
    fun all(): Map<ResourceLocation, ScevSection> = sections

    /**
     * All loaded sections sorted by [ScevSection.priority] ascending. Ties
     * resolve in undefined order — don't rely on stability across reloads.
     */
    fun sorted(): List<ScevSection> = sortedCache

    /** Section id for a given section instance, or null. */
    fun idOf(section: ScevSection): ResourceLocation? =
        sections.entries.firstOrNull { it.value == section }?.key

    companion object {
        private val LOG = LoggerFactory.getLogger(ScevSectionManager::class.java)
        private const val DIRECTORY = "scev/sections"

        /** Single mutable instance; clients access via [instance]. */
        private val INSTANCE = ScevSectionManager()

        @JvmStatic fun instance(): ScevSectionManager = INSTANCE
    }
}
