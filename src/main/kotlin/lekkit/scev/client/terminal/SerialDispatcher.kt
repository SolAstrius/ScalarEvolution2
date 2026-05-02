/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side router for [lekkit.scev.network.SerialOutPayload]
 * packets. TerminalScreen instances register a callback keyed on the
 * UUID of the machine they're bound to; when a server packet
 * arrives [acceptRemote] dispatches the bytes to the matching
 * subscriber (if any). Packets for unbound UUIDs are dropped on
 * the floor — that's the cheap fan-out path: server broadcasts to
 * everyone, clients filter.
 *
 * Single subscriber per UUID is enough — only one VT100 screen
 * can be open at a time per JVM (the worker-thread + embed-buffer
 * model is one-at-a-time per process).
 */
object SerialDispatcher {

    fun interface Receiver {
        fun onBytes(bytes: ByteArray)
    }

    private val receivers: MutableMap<UUID, Receiver> = ConcurrentHashMap()

    fun subscribe(machineUuid: UUID, receiver: Receiver) {
        receivers[machineUuid] = receiver
    }

    fun unsubscribe(machineUuid: UUID) {
        receivers.remove(machineUuid)
    }

    /** Invoked from the network handler. */
    @JvmStatic
    fun acceptRemote(machineUuid: UUID, bytes: ByteArray) {
        receivers[machineUuid]?.onBytes(bytes)
    }
}
