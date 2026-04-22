/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side rendezvous between the network packet handler (which
 * receives client requests for keyframes) and
 * `ComputerCaseBlockEntity.broadcastFramebuffer` (which consumes the
 * flag when it runs its next encode).
 *
 * The alternative would be a direct lookup from UUID → BE plus a
 * method call, but the BE registry doesn't exist today and wiring one
 * is scope we don't need. A bag of UUIDs is the smaller primitive:
 * payload handler does [request], BE does [consume] once per encode.
 *
 * Intentionally empty after server shutdown — the UUIDs are runtime
 * identifiers of live machines and have no persistent meaning.
 */
object VideoKeyframeRequests {
    /**
     * `ConcurrentHashMap.newKeySet()` gives a lock-free set-of-UUIDs
     * suitable for our access pattern: many packet-handler threads
     * writing, one tick thread reading + clearing.
     */
    private val pending: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Flag [machineUuid] as needing an IDR on the next encode. Called
     * from the [lekkit.scev.network.KeyframeRequestPayload] handler
     * on the server thread (via `IPayloadContext` default MAIN
     * dispatch). Idempotent under multiple requests — one IDR per
     * BE-tick regardless of how many clients asked.
     */
    @JvmStatic
    fun request(machineUuid: UUID) {
        pending.add(machineUuid)
    }

    /**
     * Consume the flag: returns `true` and clears the entry if an IDR
     * was requested for [machineUuid], `false` otherwise. The BE
     * calls this inside its broadcast-tick encoder-prep block, and
     * if `true` calls `encoder.forceIdr()` before encoding.
     */
    @JvmStatic
    fun consume(machineUuid: UUID): Boolean = pending.remove(machineUuid)

    /** Visible for tests. */
    @JvmStatic
    fun isPending(machineUuid: UUID): Boolean = pending.contains(machineUuid)

    /** Cleanup on server shutdown so state doesn't leak into the next run. */
    @JvmStatic
    fun clearAll() = pending.clear()
}
