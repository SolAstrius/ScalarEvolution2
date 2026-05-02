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
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.capabilities.Capabilities

/**
 * Walks every loaded entity in every server level and harvests `STORAGE_UUID`s.
 *
 * Three classes of entity matter:
 *   1. [ItemEntity] — items on the ground. Lifecycle events fire for these,
 *      but while they're alive they must be counted as live references —
 *      otherwise a player dropping an NVMe and walking back briefly would
 *      find the image gone if a sweep ran in between.
 *   2. **Automation entities** (chest-/hopper-minecart, chest-boat) — expose
 *      `Capabilities.ItemHandler.ENTITY_AUTOMATION`.
 *   3. **Inventory entities** (horses, llamas with chests) — expose
 *      `Capabilities.ItemHandler.ENTITY`.
 *
 * Players are handled by [PlayerInventoryScanner]; we skip them here.
 *
 * **Create contraptions:** mounted storage lives in private fields on the
 * contraption entity, NOT a capability. A Create-specific compat scanner
 * would be needed.
 */
class EntityScanner : DiskImageScanner {
    private val log = LogUtils.getLogger()

    override fun scan(ctx: ScanContext) {
        val server = ctx.server ?: return
        for (level in server.allLevels) scanLevel(level, ctx)
    }

    private fun scanLevel(level: ServerLevel, ctx: ScanContext) {
        for (e in level.entities.all) {
            if (e is Player) continue                            // handled by PlayerInventoryScanner
            if (ctx.isEntityExcluded(e.uuid)) continue           // see ScanContext.excludeEntity
            try { scanEntity(e, ctx) } catch (t: Throwable) {
                // One misbehaving mod entity shouldn't break the whole scan.
                log.warn("[scev-gc] scan failed for entity {} ({}): {}",
                    e.javaClass.simpleName, e.uuid, t.message)
            }
        }
    }

    private fun scanEntity(e: Entity, ctx: ScanContext) {
        // ItemEntity: scan its carried stack directly.
        if (e is ItemEntity) {
            StackInspector.inspect(e.item, ctx::addLive)
            return
        }

        // Try ENTITY_AUTOMATION first (the external automation-facing inventory
        // on chest-/hopper-carts and chest-boats), then ENTITY (horse inventory,
        // etc.). Two separate queries so a single entity registering different
        // inventories on each capability gets both walked. Dedupe by handler
        // identity.
        val auto = e.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, null)
        if (auto != null) {
            for (i in 0 until auto.slots) StackInspector.inspect(auto.getStackInSlot(i), ctx::addLive)
        }
        val own = e.getCapability(Capabilities.ItemHandler.ENTITY, null)
        if (own != null && own !== auto) {
            for (i in 0 until own.slots) StackInspector.inspect(own.getStackInSlot(i), ctx::addLive)
        }
    }
}
