/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.rvvm

import com.mojang.logging.LogUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import lekkit.rvvm.RTL8169

/**
 * Per-machine host-port allocator for forwarding into the guest network.
 *
 * One forwarder per machine UUID, hashed into the [PORT_BASE] /
 * [PORT_RANGE] window so the same machine ends up at the same host port
 * across server restarts (handy for setting up a persistent ssh-config
 * entry once and forgetting about it). On collision — another machine
 * already holds the hash slot, or the host port is bound by something
 * outside our control — we linear-probe forward until the underlying
 * `tap_portfwd` accepts.
 *
 * Forwards are bound to the loopback interface only. SSH access into a
 * guest is a developer affordance, not a public service: opening it on
 * 0.0.0.0 by default would expose every player's machine to the LAN at
 * minimum. If you need remote access, tunnel via the host's own SSH.
 *
 * Lifetime: a forward is owned by the [RTL8169] it was bound on, and
 * the underlying tap is freed by the NIC when the machine teardown
 * runs (`rtl8169.c` cleanup → `tap_close` → host port unbind). We just
 * track the assignments so [release] can free the slot for re-use by
 * the next machine that happens to hash there.
 */
internal object GuestSshPorts {
    private val LOG = LogUtils.getLogger()

    /**
     * Lowest host port we'll bind. 22000–22999 is firmly in the
     * IANA "user" range and well above anything Minecraft itself
     * reaches for; leaves room for ~1000 simultaneous machines on
     * one server before wraparound failure.
     */
    private const val PORT_BASE = 22000
    private const val PORT_RANGE = 1000
    private const val GUEST_SSH_PORT = 22

    private val assignments = ConcurrentHashMap<UUID, Int>()
    private val taken = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Try to bind a host port that forwards to the guest's TCP/22.
     * Returns the host port on success, or `null` if every slot in
     * the window was rejected by the OS (extremely unlikely on a
     * fresh server; typically means the port range collides with
     * another tenant of the host).
     */
    @JvmStatic
    fun assign(uuid: UUID, nic: RTL8169): Int? {
        if (nic.tapHandle == 0L) return null
        assignments[uuid]?.let { return it }

        val start = (uuid.leastSignificantBits.toInt() and 0x7fffffff) % PORT_RANGE
        for (offset in 0 until PORT_RANGE) {
            val port = PORT_BASE + ((start + offset) % PORT_RANGE)
            if (!taken.add(port)) continue   // already held by another scev machine
            // `tcp/127.0.0.1:<port>=22` — the inline host-address form
            // that tap_user.c parses (see tap_api.h:42). Keeps us
            // loopback-only without a separate ifaddr call (which is
            // declared in tap_api.h but never actually implemented).
            val ok = nic.portfwd("tcp/127.0.0.1:$port=$GUEST_SSH_PORT")
            if (ok) {
                assignments[uuid] = port
                LOG.info("[scev-ssh] machine {} → ssh root@127.0.0.1 -p {}", uuid, port)
                return port
            }
            // tap_portfwd refused (host port is bound by something
            // outside our process). Mark it permanently taken for
            // this server lifetime so the linear probe doesn't keep
            // tripping over the same dead slot.
            taken.add(port)
        }
        LOG.warn("[scev-ssh] no free host port in {}-{} for machine {}",
            PORT_BASE, PORT_BASE + PORT_RANGE - 1, uuid)
        return null
    }

    /**
     * Release the slot for reuse. Called from [RvvmMachineBackend.close];
     * the actual host-port unbind happens in `tap_close` inside RVVM
     * when the NIC tears down — this just clears our bookkeeping.
     */
    @JvmStatic
    fun release(uuid: UUID) {
        val port = assignments.remove(uuid) ?: return
        taken.remove(port)
    }

    /** Inspection hook for `/scev` debug commands and tests. */
    @JvmStatic
    fun lookup(uuid: UUID): Int? = assignments[uuid]
}
