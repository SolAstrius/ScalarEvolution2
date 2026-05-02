/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer

/**
 * Server-side subscription registry for VT100 kernel-console fan-out.
 *
 * Two parallel maps:
 *  - [boundPositions] — every VT100 [lekkit.scev.blockentity.TerminalBlockEntity]
 *    currently registered as bound to a machine UUID. BE registers
 *    itself in its server-tick rebind path; deregisters in setRemoved.
 *    Used to authorize [addAmbient] against — a client can only
 *    ambient-subscribe to a machine that actually has a live VT100 in
 *    the world.
 *  - [ambientSubscribers] — players whose client-side
 *    [lekkit.scev.client.terminal.TerminalActiveHost] is keeping a
 *    backend alive for that UUID. Server fan-out sends kernel TX to
 *    these players in addition to the menu-open viewers.
 *
 * **Why ambient subs need server-side state.** With just the
 * containerMenu-based fan-out, the in-world block face freezes on
 * the last frame the moment the GUI closes (server stops sending
 * bytes). The ambient-subscribe protocol lets the client opt into
 * "send me bytes for this UUID even though I don't have a menu open"
 * so the in-world render stays live.
 */
object TerminalSubscriberRegistry {

    private val LOG = LogUtils.getLogger()

    /** Every block position in the world with a VT100 BE bound to
     *  this UUID. Populated by [TerminalBlockEntity.serverTick] when
     *  the bind takes effect; depopulated in setRemoved. Used to
     *  authorize [addAmbient]. */
    private val boundPositions: MutableMap<UUID, MutableSet<BlockPos>> = ConcurrentHashMap()

    /** Players whose client wants kernel TX for this UUID even
     *  without a menu open. Iterated each tick by the BE's sink. */
    private val ambientSubscribers: MutableMap<UUID, MutableSet<ServerPlayer>> = ConcurrentHashMap()

    /* ---------------- bound-BE registry ---------------- */

    @JvmStatic
    fun addBoundBE(uuid: UUID, pos: BlockPos) {
        boundPositions.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }.add(pos)
    }

    @JvmStatic
    fun removeBoundBE(uuid: UUID, pos: BlockPos) {
        val set = boundPositions[uuid] ?: return
        set.remove(pos)
        if (set.isEmpty()) boundPositions.remove(uuid)
    }

    /** True if any VT100 BE is currently registered as bound to
     *  this UUID. */
    @JvmStatic
    fun hasBoundBE(uuid: UUID): Boolean =
        boundPositions[uuid]?.isNotEmpty() == true

    /* ---------------- ambient subscribers ---------------- */

    /**
     * Register [player] as an ambient subscriber to [uuid]. Refuses
     * (returns false) if no VT100 BE is currently bound to this
     * UUID — that's the auth gate, identical in spirit to the
     * containerMenu-based check the input path uses. Without it, a
     * malicious client could scrape any machine's UART output by
     * guessing UUIDs.
     */
    @JvmStatic
    fun addAmbient(uuid: UUID, player: ServerPlayer): Boolean {
        if (!hasBoundBE(uuid)) {
            LOG.debug("terminal ambient: refusing {} for {} (no bound BE)",
                player.gameProfile.name, uuid)
            return false
        }
        ambientSubscribers.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }.add(player)
        return true
    }

    @JvmStatic
    fun removeAmbient(uuid: UUID, player: ServerPlayer) {
        val set = ambientSubscribers[uuid] ?: return
        set.remove(player)
        if (set.isEmpty()) ambientSubscribers.remove(uuid)
    }

    /** Snapshot copy — fan-out iterates this. Empty set if no
     *  ambient subscribers. */
    @JvmStatic
    fun ambientSubscribers(uuid: UUID): Collection<ServerPlayer> =
        ambientSubscribers[uuid] ?: emptySet()

    /** Drop all ambient subscriptions for a player, e.g. on
     *  disconnect / dimension change. */
    @JvmStatic
    fun clearPlayer(player: ServerPlayer) {
        for ((uuid, set) in ambientSubscribers) {
            if (set.remove(player) && set.isEmpty()) {
                ambientSubscribers.remove(uuid)
            }
        }
    }
}
