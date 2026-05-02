/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.terminal.setup

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side router for [lekkit.scev.network.SetupSyncPayload]
 * packets. Sister to [lekkit.scev.client.terminal.SerialDispatcher] —
 * same shape (UUID → callback map), different domain (Setup state
 * instead of kernel TX bytes).
 *
 * Subscribers register at TerminalScreen open time and unsubscribe at
 * close time. Single subscriber per UUID is enough — only one Setup
 * screen can be open at a time per JVM (the embed terminal limit).
 *
 * Packets for unbound or unwatched UUIDs hit the void: no-op. Same
 * cheap fan-out path the serial pipe uses.
 */
object SetupSyncDispatcher {

    fun interface Receiver {
        fun onSetupState(state: SetupModel.PersistentState)
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
    fun acceptRemote(machineUuid: UUID, state: SetupModel.PersistentState) {
        receivers[machineUuid]?.onSetupState(state)
    }
}
