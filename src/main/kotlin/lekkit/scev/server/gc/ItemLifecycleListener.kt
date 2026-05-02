/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import com.mojang.logging.LogUtils
import java.util.UUID
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent

/**
 * Event-driven entry point for disk-image GC. Wires two NeoForge events:
 *
 *  - [ItemExpireEvent] — fires just before an [ItemEntity]'s 5-minute
 *    despawn timer removes it. Catches "player dropped it and walked away."
 *  - [EntityLeaveLevelEvent] — fires when any entity leaves the level. We
 *    filter for [ItemEntity] + a destroying removal reason
 *    (`Entity.RemovalReason.shouldDestroy()`): lava, fire, explosion, void,
 *    `/kill`, and pickup-by-player. Pickup looks like destruction at the
 *    event level, but by the time we scan, the stack lives in the player's
 *    inventory and the [lekkit.scev.server.gc.scanners.PlayerInventoryScanner]
 *    includes it in the live set — so pickup is correctly NOT deleted.
 *
 * **Why two events.** `ItemExpireEvent` fires before the entity transitions
 * to a removed state; `EntityLeaveLevelEvent` fires after. Despawn triggers
 * both, but the second firing sees an empty live set for the entity (it's
 * already removed) and still arrives at the right decision via the candidate
 * set. Running the GC twice is idempotent — the image is gone after the
 * first call. Catching both is belt-and-suspenders for mods that remove
 * entities without going through despawn (direct `discard()`).
 *
 * **Removal-reason filter.** `shouldDestroy()` returns true for `KILLED` and
 * `DISCARDED` only. `UNLOADED_TO_CHUNK`, `UNLOADED_WITH_PLAYER`, and
 * `CHANGED_DIMENSION` are transient transitions, not deletions; the item
 * reappears on chunk reload or login.
 *
 * **Fast path.** Every ItemEntity removal fires the handler, but 99.9% carry
 * non-storage stacks. [StackInspector.inspect] on plain dirt is two data-
 * component lookups returning null — essentially free.
 */
object ItemLifecycleListener {
    private val log = LogUtils.getLogger()

    @JvmStatic @SubscribeEvent
    fun onItemExpire(event: ItemExpireEvent) { handleItemDeath(event.entity) }

    @JvmStatic @SubscribeEvent
    fun onEntityLeaveLevel(event: EntityLeaveLevelEvent) {
        val ie = event.entity as? ItemEntity ?: return
        // Only act on destruction. UNLOADED_* and CHANGED_DIMENSION are transient.
        val reason = ie.removalReason ?: return
        if (!reason.shouldDestroy()) return
        handleItemDeath(ie)
    }

    /** Harvest UUIDs from the stack; if non-empty, run scanners (excluding the triggering entity) and hand to GC. */
    private fun handleItemDeath(ie: ItemEntity) {
        val gc = ScevGc.active() ?: return  // server not started or already stopping

        val stack = ie.item
        if (stack.isEmpty) return

        // Fast path: harvest first, short-circuit on empty.
        val candidates = HashSet<UUID>()
        StackInspector.inspect(stack, candidates::add)
        if (candidates.isEmpty()) return

        val server = (ie.level() as? ServerLevel)?.server
        val r = GcRunner.event(gc, server, candidates, ie.uuid)
        if (r.affected() > 0) {
            log.info(
                "[scev-gc] event-driven deleted {} image(s), freed {} bytes (trigger: {})",
                r.affected(), r.bytesFreed, ie.uuid
            )
        }
    }
}
