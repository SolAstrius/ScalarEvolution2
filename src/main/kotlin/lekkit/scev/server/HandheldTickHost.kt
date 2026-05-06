/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.util.HashMap
import java.util.HashSet
import java.util.UUID
import lekkit.scev.items.IHandheldComputer
import lekkit.scev.main.ScevDataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * Server-tick driver for handheld computer items (phones, tablets,
 * Game-Boy-style consoles). Plays the role
 * [lekkit.scev.blockentity.ComputerCaseBlockEntity.serverTick] plays
 * for placed computers — except the host is "the inventory of every
 * online player" instead of a chunk-loaded BE.
 *
 * **Each tick:**
 *  1. Walk every online player's inventory + carried stack.
 *  2. For each [IHandheldComputer] stack found, allocate a
 *     `MACHINE_UUID` if missing, get-or-build the [MachineState]
 *     via [MachineManager], unpause, set its location to the holder's
 *     pos, and run a frame through the per-UUID
 *     [MachineDisplayStreamer].
 *  3. Diff against the previous tick's "live UUIDs" set:
 *     - UUIDs no longer held → pause.
 *     - UUIDs unheld for [GRACE_TICKS] consecutive ticks → destroy
 *       (frees encoder + native VM; disk image stays — GC tracks it
 *       through the `STORAGE_UUID` on the dropped item entity).
 *
 * **Dedup.** A duplicated stack (creative pick-block, `/give`) yields
 * two stacks with the same MACHINE_UUID. The first stack found in the
 * scan order wins; the second is silently skipped this tick. The VM
 * still runs, just from one of the stacks.
 *
 * **Threading.** Server tick thread only. The streamer map and the
 * grace-counter map are plain HashMaps protected by tick-thread
 * confinement; do not touch from elsewhere.
 */
object HandheldTickHost {
    private val LOG = LogUtils.getLogger()

    /** Per-UUID encoder + broadcast pipeline. Mirrors the per-BE field on a desktop case. */
    private val streamers = HashMap<UUID, MachineDisplayStreamer>()

    /** Ticks each VM has been unheld this run. Reset to 0 each tick the VM is held. */
    private val unheldTicks = HashMap<UUID, Int>()

    /**
     * UUIDs the player has explicitly powered off via the in-menu power
     * button. While in this set, the tick host will *not* build / start /
     * tick the VM even if the stack is held — equivalent to a real handheld
     * with the power switch off. Removed by `markPoweredOn`, by grace-destroy,
     * or by post-tick scrub when the stack stops being held.
     *
     * Real-power semantics (RAM lost across cycles) — to add suspend later,
     * keep `MachineState.pause`/`unpause` and a separate user-suspend flag.
     */
    private val userPoweredOff = HashSet<UUID>()

    /** Ticks of grace before an unheld VM is destroyed. 200 = 10 s @ 20 TPS. */
    const val GRACE_TICKS: Int = 200

    /** Backoff between retry attempts after a failed VM build. 600 = 30 s @ 20 TPS. */
    private const val FAILED_INIT_RETRY_TICKS: Int = 600

    /** Server tick (game time) at which the last failed-build attempt was made, per UUID. */
    private val failedInitTick = HashMap<UUID, Long>()

    /* ---- Power-button hooks (called by ItemStackMachineHandle) ----------- */

    /**
     * Mark a handheld as user-powered-off. Subsequent ticks will skip
     * build/tick. Caller is responsible for destroying the running VM
     * (typically `MachineManager.destroyMachineState(uuid)`); this also
     * evicts the per-UUID streamer so its native encoder is released.
     */
    @JvmStatic fun markPoweredOff(uuid: UUID) {
        userPoweredOff.add(uuid)
        streamers.remove(uuid)?.close()
        unheldTicks.remove(uuid)
    }

    /** Mark a handheld as user-powered-on. Tick host will (re)build it next tick. */
    @JvmStatic fun markPoweredOn(uuid: UUID) {
        userPoweredOff.remove(uuid)
    }

    @JvmStatic fun isPoweredOff(uuid: UUID): Boolean = uuid in userPoweredOff

    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val heldThisTick = HashSet<UUID>()

        val now = server.overworld().gameTime
        for (player in server.playerList.players) {
            scanPlayer(player, heldThisTick, now)
        }

        // Drop user-powered-off entries whose stacks are no longer held by
        // anyone — small leak otherwise (entry persists until server stop).
        userPoweredOff.removeIf { it !in heldThisTick }
        failedInitTick.keys.removeIf { it !in heldThisTick }

        // Diff: for every previously-known VM, either it was held this tick
        // (reset grace counter) or it wasn't (advance grace, destroy past
        // threshold). Iterate over the streamers' keyset since that's the
        // canonical "we're tracking this VM" set.
        // Iterate Map.Entry so we can both retrieve the value (to close it)
        // and call iterator.remove() — `streamers.remove(uuid)` from inside
        // a `streamers.keys.iterator()` loop invalidates the iterator and
        // throws ConcurrentModificationException on the next op.
        val it = streamers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val uuid = entry.key
            if (uuid in heldThisTick) {
                unheldTicks.remove(uuid)
                continue
            }
            // Not held this tick. Pause if running.
            MachineManager.getMachineState(uuid)?.pause()
            val ticks = (unheldTicks[uuid] ?: 0) + 1
            if (ticks >= GRACE_TICKS) {
                entry.value.close()
                it.remove()
                unheldTicks.remove(uuid)
                userPoweredOff.remove(uuid)
                MachineManager.removeMachineState(uuid)
            } else {
                unheldTicks[uuid] = ticks
            }
        }
    }

    private fun scanPlayer(player: ServerPlayer, heldThisTick: HashSet<UUID>, now: Long) {
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            tickStackIfHandheld(player, inv.getItem(i), heldThisTick, now)
        }
        // Curios / mod-added player slots.
        player.getCapability(Capabilities.ItemHandler.ENTITY, null)?.let { h ->
            for (i in 0 until h.slots) tickStackIfHandheld(player, h.getStackInSlot(i), heldThisTick, now)
        }
        // Carried stack while a menu is open.
        tickStackIfHandheld(player, player.containerMenu.carried, heldThisTick, now)
    }

    private fun tickStackIfHandheld(
        player: ServerPlayer, stack: ItemStack, heldThisTick: HashSet<UUID>, now: Long
    ) {
        val item = stack.item as? IHandheldComputer ?: return

        // Allocate UUID lazily on first sighting. Set the component back on
        // the stack so it persists across save/load and follows the stack
        // through inventory operations.
        var uuid = stack.get(ScevDataComponents.MACHINE_UUID.get())
        if (uuid == null) {
            uuid = UUID.randomUUID()
            stack.set(ScevDataComponents.MACHINE_UUID.get(), uuid)
        }

        // Per-tick dedup: if a duplicate stack with the same UUID was already
        // ticked this tick, skip — running two ticks against one VM in the
        // same server tick would be a correctness bug (the backend assumes
        // monotonic tick advance).
        if (!heldThisTick.add(uuid)) return

        // User powered the device off via the in-menu power button. Don't
        // build/start; userPoweredOff is in heldThisTick so the post-tick
        // scrub keeps the flag alive while the stack is still in inventory.
        if (uuid in userPoweredOff) return

        // Build VM if missing.
        var state = MachineManager.getMachineState(uuid)
        if (state == null) {
            // Backoff: previous build attempts failed (e.g. RVVM native missing).
            // Without this, every tick spams "failed to create" until something
            // changes externally — which usually means a server restart.
            val lastFail = failedInitTick[uuid]
            if (lastFail != null && now - lastFail < FAILED_INIT_RETRY_TICKS) return

            val spec = item.buildSpec(uuid, stack)
            if (spec == null) {
                // Malformed stack (no motherboard, parser failed). Don't
                // re-scan this tick; UUID stays allocated, retry next tick.
                if (lastFail == null) LOG.warn("Failed to build spec for handheld {}", uuid)
                failedInitTick[uuid] = now
                return
            }
            state = MachineManager.createMachineState(spec)
            if (state == null) {
                if (lastFail == null) LOG.warn("Failed to create handheld VM for {}", uuid)
                failedInitTick[uuid] = now
                return
            }
            failedInitTick.remove(uuid)
            // First-boot mutation persistence: MachineSpecParser may have
            // allocated STORAGE_UUIDs into nested sub-stacks of the
            // MOTHERBOARD_INVENTORY component. Those mutations live on the
            // same ItemStack object the player is holding (the parser walks
            // the data component via a supplier), so the inventory's normal
            // dirty/save round-trip will persist them — no explicit re-set
            // required. (Sanity check on the next tick: stack.get(
            // MOTHERBOARD_INVENTORY) will reflect the new UUIDs.)
            state.start()
        }

        // Unpause if previously paused (re-equip after stow).
        state.unpause()

        // Track the holder's position so DisplayPayload broadcasts reach
        // nearby players (and so the broadcast follows the player as they
        // walk around).
        state.setLocation(player.serverLevel(), player.blockPosition())

        // Allocate the streamer entry up-front (before the framebuffer
        // null-check) so the grace-destroy diff loop can see and clean up
        // VMs that boot without a display device — otherwise such VMs are
        // tracked by MachineManager but invisible to the per-UUID streamer
        // map, and leak until server stop.
        val streamer = streamers.getOrPut(uuid) { MachineDisplayStreamer() }
        val fb = state.display ?: return
        streamer.tick(player.serverLevel(), player.blockPosition(), uuid, fb, state.clock)
    }

    /**
     * Server-stop teardown. `MachineManager.finishAllMachines()` already
     * destroys VMs; we only need to release the per-UUID encoders.
     */
    @JvmStatic
    fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        for (s in streamers.values) s.close()
        streamers.clear()
        unheldTicks.clear()
        userPoweredOff.clear()
        failedInitTick.clear()
    }
}
