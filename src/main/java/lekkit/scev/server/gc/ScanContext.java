/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * Collector passed to every {@link DiskImageScanner}. Each scanner adds the
 * UUIDs it finds referenced; the orchestrator reads the final set to decide
 * which image files are orphaned.
 *
 * <p>The {@link #server()} handle is {@code null} for unit-test contexts that
 * don't stand up a real server — scanners that need a server should null-check
 * and either short-circuit or extract what they need from another path. In
 * production the orchestrator always provides a real server.
 */
public final class ScanContext {
    private final @Nullable MinecraftServer server;
    private final Set<UUID> liveUuids = new HashSet<>();
    private final Set<UUID> excludedEntityUuids = new HashSet<>();

    public ScanContext(@Nullable MinecraftServer server) {
        this.server = server;
    }

    /**
     * Handle to the running server, or {@code null} in test contexts.
     * Scanners that need to walk world state should obtain their levels /
     * player list / etc. from here.
     */
    public @Nullable MinecraftServer server() {
        return server;
    }

    /**
     * Mark {@code uuid} as referenced — the image file backing this UUID will
     * be preserved by the current sweep/purge. Null-safe: calls with
     * {@code null} are ignored so scanners can pass
     * {@code stack.get(STORAGE_UUID)} directly.
     */
    public void addLive(@Nullable UUID uuid) {
        if (uuid != null) liveUuids.add(uuid);
    }

    /**
     * Read-only view of UUIDs accumulated so far.
     */
    public Set<UUID> liveUuids() {
        return Collections.unmodifiableSet(liveUuids);
    }

    /** Number of UUIDs added — for logging / status readouts. */
    public int size() {
        return liveUuids.size();
    }

    /**
     * Mark an entity (by Minecraft entity UUID — {@link net.minecraft.world.entity.Entity#getUUID()},
     * NOT a {@code STORAGE_UUID}) as excluded from the current scan. Used by
     * the event-driven GC path: when an {@code ItemEntity} is about to
     * despawn / burn / void, the {@link lekkit.scev.server.gc.scanners.EntityScanner}
     * must skip it — otherwise it would "protect" the to-be-destroyed
     * entity's own stack, which would make the event-driven path a no-op for
     * items that aren't also referenced elsewhere.
     *
     * <p>No-op for {@code null}.
     */
    public void excludeEntity(@Nullable UUID entityUuid) {
        if (entityUuid != null) excludedEntityUuids.add(entityUuid);
    }

    /**
     * True iff {@code entityUuid} has been registered via
     * {@link #excludeEntity(UUID)}. Scanners that iterate entities should
     * check this and skip matching entities.
     */
    public boolean isEntityExcluded(@Nullable UUID entityUuid) {
        return entityUuid != null && excludedEntityUuids.contains(entityUuid);
    }
}
