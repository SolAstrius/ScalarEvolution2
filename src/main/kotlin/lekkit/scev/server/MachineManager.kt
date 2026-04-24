/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import com.mojang.logging.LogUtils
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import lekkit.scev.machine.MachineBackendFactory
import lekkit.scev.machine.MachineSpec
import lekkit.scev.machine.rvvm.RvvmMachineBackend

/**
 * Thread-safe registry of currently-running [MachineState]s keyed by UUID.
 *
 * The backing [lekkit.scev.machine.MachineBackend] is constructed via a
 * pluggable [MachineBackendFactory] — by default the production
 * [RvvmMachineBackend]. Tests replace the factory with a fake so the
 * full create / attach / start pipeline runs without JNI.
 *
 * **Concurrency.** Previously a `synchronized` wrapper over a plain
 * `HashMap`; every read (including the per-machine `getMachineState`
 * call from every tick-thread loop in the RPC and sound managers)
 * contended against every write (machine create / destroy). This
 * variant uses a [ConcurrentHashMap] for lock-free reads, and keeps
 * the "no double-create per UUID" invariant via [putIfAbsent] rather
 * than a coarse class monitor — which also means the slow
 * [MachineBackend.initialize] call no longer runs under a lock held
 * across every other manager method.
 */
object MachineManager {
    private val LOG = LogUtils.getLogger()

    private val machines = ConcurrentHashMap<UUID, MachineState>()

    @Volatile
    private var factory: MachineBackendFactory = MachineBackendFactory { RvvmMachineBackend() }

    /**
     * Override the backend factory. Intended for tests — production
     * code leaves it at the default. The change persists until set
     * again. Passing `null` restores the default [RvvmMachineBackend].
     */
    @JvmStatic
    fun setBackendFactory(f: MachineBackendFactory?) {
        factory = f ?: MachineBackendFactory { RvvmMachineBackend() }
    }

    /**
     * Create and register a new [MachineState] from a [MachineSpec].
     * Returns `null` when the UUID is already present or the backend
     * fails to initialize. Backend initialization happens *without*
     * holding any registry lock.
     */
    @JvmStatic
    fun createMachineState(spec: MachineSpec): MachineState? {
        if (machines.containsKey(spec.uuid())) {
            LOG.warn("Machine {} already exists", spec.uuid())
            return null
        }
        val backend = factory.create()
        if (!backend.initialize(spec)) {
            LOG.warn("Failed to initialize backend for machine {}", spec.uuid())
            backend.close()
            return null
        }
        val state = MachineState(spec, backend)
        val existing = machines.putIfAbsent(spec.uuid(), state)
        if (existing != null) {
            // Lost a race against a concurrent create for the same UUID.
            // Destroy the state we just built and return null — the
            // caller's second-create attempt failed, consistent with
            // the containsKey short-circuit above.
            LOG.warn("Machine {} raced to create; discarding ours", spec.uuid())
            state.destroy()
            return null
        }
        return state
    }

    @JvmStatic
    fun getMachineState(uuid: UUID): MachineState? = machines[uuid]

    @JvmStatic
    fun removeMachineState(uuid: UUID) {
        machines.remove(uuid)?.destroy()
    }

    @JvmStatic
    fun destroyMachineState(uuid: UUID) {
        removeMachineState(uuid)
        // TODO: delete snapshot file
    }

    @JvmStatic
    fun hasMachineStateSnapshot(@Suppress("UNUSED_PARAMETER") uuid: UUID): Boolean {
        // TODO: real snapshot lookup
        return false
    }

    @JvmStatic
    fun finishAllMachines() {
        pauseAllMachines()
        for (state in machines.values) {
            state.saveSnapshot()
            state.destroy()
        }
        machines.clear()
    }

    @JvmStatic
    fun pauseAllMachines() {
        for (state in machines.values) state.pause()
    }

    @JvmStatic
    fun unpauseAllMachines() {
        for (state in machines.values) state.unpause()
    }

    /** Visible for tests / telemetry. */
    @JvmStatic
    fun liveMachineCount(): Int = machines.size

    /**
     * Snapshot of every currently-running machine's UUID. Used by the
     * disk-image GC ([lekkit.scev.server.gc.scanners.RunningMachineScanner])
     * to keep the backing images of live VMs out of the orphan set —
     * deleting a running VM's disk image would cause immediate guest I/O
     * errors and likely crash the machine.
     *
     * <p>Returns a defensive copy. Safe to iterate outside the tick thread.
     */
    @JvmStatic
    fun getActiveUuids(): Set<UUID> = HashSet(machines.keys)
}
