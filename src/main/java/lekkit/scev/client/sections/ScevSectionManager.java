/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.sections;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side resource manager that loads {@link ScevSection} definitions
 * from {@code assets/<any_modid>/scev/sections/*.json} on /reload.
 *
 * <p>Multiple mods may drop JSONs under their own namespaces — all are
 * aggregated into one global list. Assignments via
 * {@link ScevSectionRegistry} are independent; a section is only visible
 * in the tab if at least one item is assigned to it. Conversely, an item
 * assigned to an unknown section id is skipped silently at render time
 * (not fatal — the mod owning that section may not be installed).
 *
 * <p>Registered on {@code RegisterClientReloadListenersEvent} from the mod's
 * client-side bus wiring.
 */
public final class ScevSectionManager extends SimpleJsonResourceReloadListener {
    private static final Logger LOG = LoggerFactory.getLogger(ScevSectionManager.class);
    private static final String DIRECTORY = "scev/sections";

    /** Single mutable instance; clients should access via {@link #instance()}. */
    private static final ScevSectionManager INSTANCE = new ScevSectionManager();

    /** id -> section, mutated on reload, read from the GUI thread. */
    private Map<ResourceLocation, ScevSection> sections = Collections.emptyMap();

    /** Cached sorted list, invalidated on reload; read from the GUI thread. */
    private List<ScevSection> sortedCache = Collections.emptyList();

    private ScevSectionManager() {
        super(new com.google.gson.GsonBuilder().create(), DIRECTORY);
    }

    public static ScevSectionManager instance() {
        return INSTANCE;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> raw, ResourceManager mgr, ProfilerFiller profiler) {
        Map<ResourceLocation, ScevSection> parsed = new HashMap<>();
        raw.forEach((id, json) -> {
            ScevSection.CODEC.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(err -> LOG.error("Failed to parse scev section {}: {}", id, err))
                    .ifPresent(section -> parsed.put(id, section));
        });
        this.sections = Map.copyOf(parsed);

        List<ScevSection> sorted = new ArrayList<>(parsed.values());
        Collections.sort(sorted);
        this.sortedCache = List.copyOf(sorted);

        LOG.info("Loaded {} scev creative-tab sections", parsed.size());
    }

    /** @return section for {@code id}, or {@code null} if not loaded / unknown. */
    public @Nullable ScevSection get(ResourceLocation id) {
        return sections.get(id);
    }

    /**
     * @return the id-keyed map of loaded sections. Not guaranteed to be
     *         sorted; use {@link #sorted()} when you need iteration order.
     */
    public Map<ResourceLocation, ScevSection> all() {
        return sections;
    }

    /**
     * @return all loaded sections sorted by {@link ScevSection#priority()}
     *         ascending. Ties resolve in undefined order — don't rely on
     *         stability across reloads.
     */
    public List<ScevSection> sorted() {
        return sortedCache;
    }

    /** @return the section id for a given section instance, or {@code null}. */
    public @Nullable ResourceLocation idOf(ScevSection section) {
        for (Map.Entry<ResourceLocation, ScevSection> e : sections.entrySet()) {
            if (e.getValue().equals(section)) return e.getKey();
        }
        return null;
    }
}
