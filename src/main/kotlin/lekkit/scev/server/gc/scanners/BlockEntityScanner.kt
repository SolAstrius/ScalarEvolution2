/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc.scanners

import com.mojang.logging.LogUtils
import lekkit.scev.server.gc.DiskImageScanner
import lekkit.scev.server.gc.ScanContext
import lekkit.scev.server.gc.StackInspector
import net.minecraft.core.Direction
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.level.chunk.LevelChunk
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler

/**
 * Walks every block entity in every loaded chunk across every server level
 * and harvests `STORAGE_UUID`s.
 *
 * **Discovery.** For each loaded [LevelChunk]:
 *  1. Try `Capabilities.ItemHandler.BLOCK` on every side (null + 6 faces),
 *     deduped by handler identity. Catches Create belts/depots/funnels,
 *     most Tech-mod machines, vanilla chests/barrels/hoppers/shulkers.
 *  2. Fall back to direct [Container] iteration for BEs that implement
 *     [Container] without registering the capability — rare, harmless
 *     belt-and-suspenders.
 *
 * **Doesn't cover:** unloaded chunks (mitigated by the retention window),
 * AE2/RS/Mekanism virtual storage (need compat scanners), Create
 * contraption-mounted inventories (carried by the contraption entity, not
 * exposed as a capability there either).
 */
class BlockEntityScanner : DiskImageScanner {
    override fun scan(ctx: ScanContext) {
        val server = ctx.server ?: return
        for (level in server.allLevels) scanLevel(level, ctx)
    }

    private fun scanLevel(level: ServerLevel, ctx: ScanContext) {
        // Iterate via ChunkMap.getChunks() (reflective) -> ChunkHolder ->
        // LevelChunk. We only want chunks currently in a visible state;
        // partially-generated chunks can't carry meaningful BEs.
        for (holder in invokeGetChunks(level.chunkSource.chunkMap)) {
            val chunk = holder.chunkToSend ?: continue
            scanChunk(level, chunk, ctx)
        }
    }

    private fun scanChunk(level: ServerLevel, chunk: LevelChunk, ctx: ScanContext) {
        for ((pos, be) in chunk.blockEntities) {
            try {
                // Path 1: ItemHandler.BLOCK capability per-side. Dedupe by handler
                // identity so a 6-sided-identical handler isn't walked 7 times.
                var lastSeen: IItemHandler? = null
                for (side in SIDES_WITH_NULL) {
                    val handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side)
                    if (handler == null || handler === lastSeen) continue
                    lastSeen = handler
                    for (i in 0 until handler.slots) StackInspector.inspect(handler.getStackInSlot(i), ctx::addLive)
                }
                // Path 2: direct Container iteration. Idempotent re-add if
                // path 1 already saw this BE — same UUIDs, no harm.
                if (be is Container) {
                    for (i in 0 until be.containerSize) StackInspector.inspect(be.getItem(i), ctx::addLive)
                }
            } catch (t: Throwable) {
                // One misbehaving mod BE shouldn't break the whole scan.
                LOG.warn("[scev-gc] scan failed for block entity {} at {}: {}",
                    be.javaClass.simpleName, pos, t.message)
            }
        }
    }

    companion object {
        private val LOG = LogUtils.getLogger()

        /** Canonical side order. Null means "side-agnostic handler". */
        private val SIDES_WITH_NULL: Array<Direction?> = arrayOf(
            null,
            Direction.DOWN, Direction.UP,
            Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST,
        )

        /**
         * `ChunkMap.getChunks()` returns every loaded chunk's [ChunkHolder]; the
         * accessor is `protected`. Bind reflectively once at class init —
         * cheaper and safer than per-call reflection, and contained to this
         * file so we don't need an access-transformer.
         *
         * If the bind fails (vanilla version drift), log and fall back to an
         * empty iteration. GC degrades gracefully: this scanner finds nothing,
         * but [PlayerInventoryScanner] still covers items in player inventories.
         */
        private val CHUNK_MAP_GET_CHUNKS: java.lang.reflect.Method? = run {
            try {
                ChunkMap::class.java.getDeclaredMethod("getChunks").apply { isAccessible = true }
            } catch (e: NoSuchMethodException) {
                LOG.warn("[scev-gc] Could not bind ChunkMap.getChunks() reflectively — " +
                    "BlockEntityScanner will return empty. Minecraft version mismatch?", e)
                null
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun invokeGetChunks(chunkMap: ChunkMap): Iterable<ChunkHolder> {
            val m = CHUNK_MAP_GET_CHUNKS ?: return emptyList()
            return try {
                m.invoke(chunkMap) as Iterable<ChunkHolder>
            } catch (e: ReflectiveOperationException) {
                LOG.warn("[scev-gc] ChunkMap.getChunks() invocation failed: {}", e.message)
                emptyList()
            }
        }
    }
}
