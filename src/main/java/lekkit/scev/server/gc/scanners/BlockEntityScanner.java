/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc.scanners;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import lekkit.scev.server.gc.DiskImageScanner;
import lekkit.scev.server.gc.ScanContext;
import lekkit.scev.server.gc.StackInspector;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;

/**
 * Walks every block entity in every loaded chunk across every server level
 * and harvests {@code STORAGE_UUID}s from their inventories.
 *
 * <h2>Discovery strategy</h2>
 *
 * <p>For each loaded {@link LevelChunk}, iterate its
 * {@link LevelChunk#getBlockEntities()} map. For each {@link BlockEntity}:
 *
 * <ol>
 *   <li>Try {@link Capabilities.ItemHandler#BLOCK} on every side (null + 6
 *       faces). Deduplicates by handler identity so a block that exposes the
 *       same handler on every face is only walked once. This path catches
 *       Create's belts/depots/funnels, most Tech-mod machines, vanilla
 *       chests, barrels, dispensers, hoppers, furnaces, shulkers.</li>
 *   <li>Fall back to {@link Container} direct iteration for block entities
 *       that implement {@link Container} but don't register the capability.
 *       Rare in modern modded MC but harmless to include — a safety net.</li>
 * </ol>
 *
 * <h2>What this doesn't cover</h2>
 *
 * <ul>
 *   <li><b>Unloaded chunks.</b> Items in chests players haven't visited in a
 *       while are invisible to this scanner. The GC's retention window
 *       (default 30 days, configurable) is the mitigation: as long as the
 *       player visits at least once per retention period, those items stay
 *       preserved.</li>
 *   <li><b>Virtual storage</b> (AE2 ME networks, Refined Storage disks,
 *       Mekanism QIO). Their block entities DO expose an ItemHandler, but
 *       for the "auto-crafting interface / port" side, not the actual
 *       storage cell contents. A compat scanner registered via
 *       {@link lekkit.scev.server.gc.ScannerRegistry} would close the gap.</li>
 *   <li><b>Create contraption entities.</b> While assembled into a
 *       contraption, a chest's block entity lives on the entity, not in the
 *       world. {@link EntityScanner} picks up the contraption entity but
 *       Create doesn't expose the mounted storage as an entity capability —
 *       a compat scanner is needed for those.</li>
 * </ul>
 */
public final class BlockEntityScanner implements DiskImageScanner {
    private static final Logger LOG = LogUtils.getLogger();

    /** Canonical side order. Null means "side-agnostic handler". */
    private static final Direction[] SIDES_WITH_NULL = new Direction[]{
            null,
            Direction.DOWN, Direction.UP,
            Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST,
    };

    /**
     * {@code ChunkMap.getChunks()} returns an {@code Iterable<ChunkHolder>} of
     * every visible (loaded) chunk, but the accessor is {@code protected}.
     * We bind it reflectively once at class init — cheaper and safer than
     * per-call reflection, and contained to this single file so we don't
     * need an access-transformer file in the build.
     *
     * <p>If the reflective bind fails (shouldn't — the method signature is
     * stable across vanilla's lifetime), we log and fall back to an empty
     * iteration. GC degrades gracefully: sweep finds no block entities, so
     * no unreferenced images come from this scanner; a player walking by
     * with an NVMe in their inventory still gets covered by
     * {@link PlayerInventoryScanner}.
     */
    private static final Method CHUNK_MAP_GET_CHUNKS;
    static {
        Method m;
        try {
            m = ChunkMap.class.getDeclaredMethod("getChunks");
            m.setAccessible(true);
        } catch (NoSuchMethodException e) {
            LOG.warn("[scev-gc] Could not bind ChunkMap.getChunks() reflectively — "
                    + "BlockEntityScanner will return empty. Minecraft version mismatch?", e);
            m = null;
        }
        CHUNK_MAP_GET_CHUNKS = m;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<ChunkHolder> invokeGetChunks(ChunkMap chunkMap) {
        if (CHUNK_MAP_GET_CHUNKS == null) return Collections.emptyList();
        try {
            return (Iterable<ChunkHolder>) CHUNK_MAP_GET_CHUNKS.invoke(chunkMap);
        } catch (ReflectiveOperationException e) {
            LOG.warn("[scev-gc] ChunkMap.getChunks() invocation failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void scan(ScanContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level, ctx);
        }
    }

    private static void scanLevel(ServerLevel level, ScanContext ctx) {
        ServerChunkCache chunks = level.getChunkSource();
        // Iterate via ChunkMap.getChunks() (reflective) → ChunkHolder →
        // LevelChunk. We only want chunks that are currently serving a
        // visible state; partially-generated chunks can't have meaningful
        // block entities. getChunkToSend() is the public way to ask for
        // "current broadcastable chunk, if any."
        for (ChunkHolder holder : invokeGetChunks(chunks.chunkMap)) {
            LevelChunk chunk = holder.getChunkToSend();
            if (chunk == null) continue;
            scanChunk(level, chunk, ctx);
        }
    }

    private static void scanChunk(ServerLevel level, LevelChunk chunk, ScanContext ctx) {
        for (var entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockEntity be = entry.getValue();
            try {
                scanBlockEntity(level, pos, be, ctx);
            } catch (Throwable t) {
                // One misbehaving mod BE shouldn't break the whole scan.
                LOG.warn("[scev-gc] scan failed for block entity {} at {}: {}",
                        be.getClass().getSimpleName(), pos, t.getMessage());
            }
        }
    }

    private static void scanBlockEntity(ServerLevel level, BlockPos pos, BlockEntity be, ScanContext ctx) {
        // Path 1: ItemHandler.BLOCK capability per-side. Dedupe by handler
        // identity so a 6-sided-identical handler isn't walked 7 times.
        IItemHandler lastSeen = null;
        for (Direction side : SIDES_WITH_NULL) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (handler == null || handler == lastSeen) continue;
            lastSeen = handler;
            int slots = handler.getSlots();
            for (int i = 0; i < slots; i++) {
                StackInspector.inspect(handler.getStackInSlot(i), ctx::addLive);
            }
        }

        // Path 2: direct Container iteration. Fallback for BEs that implement
        // Container but don't wire up the capability. If the capability walk
        // already hit this BE, the StackInspector-driven live set will
        // idempotently re-add the same UUIDs — no duplicate harm.
        if (be instanceof Container container) {
            int size = container.getContainerSize();
            for (int i = 0; i < size; i++) {
                StackInspector.inspect(container.getItem(i), ctx::addLive);
            }
        }
    }
}
