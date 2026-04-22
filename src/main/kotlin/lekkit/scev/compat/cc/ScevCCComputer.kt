/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc

import com.mojang.logging.LogUtils
import dan200.computercraft.api.filesystem.Mount
import dan200.computercraft.api.filesystem.WritableMount
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IPeripheral
import dan200.computercraft.api.peripheral.PeripheralCapability
import dan200.computercraft.api.peripheral.WorkMonitor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import lekkit.scev.blocks.DirectionalBlock
import lekkit.scev.rpc.MsgValue
import lekkit.scev.rpc.RpcFrame
import lekkit.scev.rpc.ScevRpcManager
import lekkit.scev.server.MachineManager
import net.minecraft.core.Direction
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Impersonates a CC: Tweaked computer for a single scev machine.
 *
 * A real CC [IComputerAccess] represents "this peripheral, as seen by
 * the computer it is attached to". Peripherals call it to queue events,
 * to discover the other peripherals this computer can see, to mount
 * read-only resource packs, etc. We expose the same interface so that
 * CC-written peripheral methods can be invoked against scev with no
 * idea they're not talking to a real Lua-driven computer.
 *
 * Peripheral discovery uses NeoForge's [PeripheralCapability] block
 * capability (CC on NeoForge 1.21 registers
 * `BlockCapability<IPeripheral, Direction>`). On every server tick we
 * query the 6 face-adjacent blocks and cache the result into a side →
 * peripheral map (`"front"`, `"back"`, `"left"`, `"right"`, `"top"`,
 * `"bottom"`).
 *
 * Not yet implemented:
 * - Wired-modem network peripherals. Would need to attach to the scev
 *   block's `WiredElement` capability and walk the network each tick.
 * - Mounts. Scev has no Lua filesystem to mount into; [mount] /
 *   [mountWritable] return null. If we ever expose a Linux-side FUSE
 *   or similar we can wire it through here.
 * - [getMainThreadMonitor]. CC's WorkMonitor limits how much server
 *   time a computer can consume on main-thread tasks; we're already
 *   on the main thread inside the RPC tick and don't need throttling.
 */
class ScevCCComputer(private val machineUuid: UUID) : IComputerAccess {
    private val sideToPeripheral = java.util.concurrent.ConcurrentHashMap<String, IPeripheral>()

    /**
     * Bound on first [tick] when a [ScevRpcManager] is registered for
     * this UUID. We look it up lazily instead of taking it in the
     * constructor because managers are created during machine start,
     * potentially before or after we are; binding in the first tick
     * handles either order cleanly.
     */
    @Volatile private var rpcManager: ScevRpcManager? = null

    /**
     * Serialises peripheral-method dispatches per computer. Real CC
     * runs one Lua coroutine per computer; peripheral calls don't race
     * each other. Matching that simplifies the event queue (single
     * consumer) and avoids arguments-reentry pitfalls in peripherals
     * whose [IDynamicPeripheral.callMethod] isn't reentrant.
     */
    val dispatchMutex = Mutex()

    /**
     * Event bus consumed by [awaitEvent] when a peripheral method
     * yields on `pullEvent` / `yield`. [queueEvent] feeds this channel
     * alongside forwarding to the guest via [ScevRpcManager.sendEvent],
     * so either side can wake a suspended dispatch. UNLIMITED avoids
     * blocking the emitter on a slow consumer; events that nobody is
     * waiting for are still forwarded to the guest and just drained
     * lazily from the channel on the next [awaitEvent].
     */
    private val eventChannel = Channel<Event>(Channel.UNLIMITED)

    /** One event as seen by a suspending [awaitEvent] call. */
    data class Event(val name: String, val args: List<Any?>)

    /**
     * Event-schema learner: running tally of `(event name) →
     * (argument-positional-shape → count)`. Populated from both
     * [queueEvent] (peripheral-originated) and [injectEventFromGuest]
     * (Lua → Java via the `queue_event` RPC), so the observed schema
     * reflects the guest's complete view of event traffic.
     *
     * Intentionally unbounded in principle — but bounded in practice
     * by (1) the finite set of CC event names and (2) the cap on
     * distinct argument shapes per event ([MAX_SHAPES_PER_EVENT]). Once
     * the cap is hit further shape variations are lumped into a
     * sentinel shape rather than growing the map.
     *
     * Access is thread-safe: ConcurrentHashMap + AtomicLong counters.
     * Not consistent across fields — a concurrent reader can see a
     * fresh counter that doesn't yet appear in the shape map — but
     * eventually consistent is fine for a learner.
     */
    private val eventSchemas = java.util.concurrent.ConcurrentHashMap<String, EventSchemaEntry>()

    /** Internal schema entry: mutable, thread-safe counters. */
    private class EventSchemaEntry {
        val total = AtomicLong(0)
        val shapes = java.util.concurrent.ConcurrentHashMap<List<String>, AtomicLong>()
    }

    /** Flattened snapshot entry returned to callers of [eventSchemas]. */
    data class EventSchemaSnapshot(
        val name: String,
        val observations: Long,
        /** Positional Java-type shape → count. Sentinel `"…"` means "too many shapes". */
        val shapes: Map<List<String>, Long>,
    )

    /**
     * Dispatch tracing. Toggleable via the `trace` RPC; off by default
     * (the ring buffer's allocation isn't free even when empty, but
     * the main cost is serialisation and per-call work when recording).
     *
     * [traceLog] is a bounded ring — once full, the oldest entry is
     * dropped to make room for a new one. Synchronised on itself
     * because dispatches can overlap when the dispatcher mutex isn't
     * engaged (e.g. internal enumeration paths like `getTypeRemote`
     * from [ScevCCHandlers.list] don't take [dispatchMutex]).
     */
    @Volatile private var traceEnabled: Boolean = false
    private val traceLog = ArrayDeque<DispatchTrace>()

    /**
     * One row in the dispatch trace. Captures enough to reconstruct
     * "what did the guest ask, what did it get back, how long did it
     * take" without needing the full MsgValue round-trip (which would
     * bloat memory and is already visible on the RPC wire if anyone
     * cares).
     */
    data class DispatchTrace(
        /** Epoch millis at invocation start. */
        val startedAt: Long,
        val durationMicros: Long,
        val peripheralName: String,
        val method: String,
        /** One-line summary of incoming args ("3 args: number, string, …"). */
        val argsSummary: String,
        /** "ok", "error", or "yielded" — the latter currently rare. */
        val outcome: String,
        /** Error message on failure, single-value summary on success, or null. */
        val detail: String?,
    )

    /**
     * Remote-peripheral name (e.g. `monitor_4`) → the wired modem that
     * owns it. Populated during [tick] by calling `getNamesRemote` on
     * each adjacent modem; serves as the routing table for
     * `scev list` / `scev call` / `scev methods` on names that aren't
     * direct side neighbours.
     */
    private val remoteToModem = java.util.concurrent.ConcurrentHashMap<String, IPeripheral>()

    /**
     * Peripherals we've called [IPeripheral.attach] on. A real CC
     * computer attaches each peripheral it can see so the peripheral
     * learns who's looking and starts fanning events at it; wired
     * modems specifically use this moment to populate their per-
     * computer wrapper map that underpins `callRemote` /
     * `getNamesRemote`. We do the same, tracking the set so we can
     * call `detach` when the peripheral goes away (removed block,
     * machine shutdown).
     */
    private val attachedPeripherals = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<IPeripheral, Boolean>()
    )

    /** Scan adjacent blocks for CC peripherals. Called from the RPC tick. */
    fun tick() {
        if (rpcManager == null) {
            rpcManager = ScevRpcManager.get(machineUuid)
        }
        val state = MachineManager.getMachineState(machineUuid) ?: return
        val level = state.level ?: return
        val pos = state.pos ?: return

        // The scev block's horizontal facing controls how relative side
        // names (front/back/left/right) map to absolute directions. We
        // expose both sets so Lua scripts written against CC's "left"
        // and "north" conventions both work — either name resolves to
        // the same peripheral instance in the map.
        val facing: Direction = runCatching {
            val bs = level.getBlockState(pos)
            bs.getValue(DirectionalBlock.FACING)
        }.getOrDefault(Direction.NORTH)

        // Rebuild the map each tick rather than patching it. Blocks
        // can be placed/removed and facings rotated between ticks; a
        // full replace is simpler than tracking deltas and costs six
        // capability lookups on an already-cheap path.
        val next = HashMap<String, IPeripheral>(12)
        val discovered = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<IPeripheral, Boolean>()
        )
        for (dir in Direction.values()) {
            val adjacentPos = pos.relative(dir)
            // Pass dir.opposite so the peripheral sees the side facing
            // back toward us — CC's block-capability contract.
            val peripheral = level.getCapability(
                PeripheralCapability.get(),
                adjacentPos,
                dir.opposite,
            ) ?: continue
            // Absolute side name (north/south/east/west/up/down) —
            // always valid, orientation-independent.
            next[dir.serializedName] = peripheral
            // Relative side name (front/back/left/right/top/bottom) —
            // depends on the scev block's HORIZONTAL_FACING. Same
            // IPeripheral lands under both keys.
            next[relativeSideOf(dir, facing)] = peripheral
            discovered.add(peripheral)
        }

        // Attach/detach to keep modem wrapper maps (and any other
        // attach-sensitive peripheral) in sync with our visibility.
        // IdentityHashMap semantics match IPeripheral's equals(other)
        // contract — two distinct instances pointing at the same BE
        // count as different attachments.
        val toDetach = mutableListOf<IPeripheral>()
        for (p in attachedPeripherals) if (!discovered.contains(p)) toDetach.add(p)
        for (p in toDetach) {
            runCatching { p.detach(this) }
            attachedPeripherals.remove(p)
        }
        for (p in discovered) {
            if (attachedPeripherals.add(p)) runCatching { p.attach(this) }
        }

        sideToPeripheral.clear()
        sideToPeripheral.putAll(next)

        // Discover wired-modem-attached peripheral names. Each adjacent
        // wired modem exposes `getNamesRemote(IComputerAccess)` via
        // @LuaFunction; we invoke it through reflection because we know
        // the exact signature and don't want to route through the
        // dispatcher just to enumerate strings.
        refreshRemotePeripherals(discovered)
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshRemotePeripherals(adjacent: Set<IPeripheral>) {
        val fresh = HashMap<String, IPeripheral>()
        for (p in adjacent) {
            // Modems report themselves with type "modem". The
            // wired-only variants have `isWireless()` returning false;
            // wireless ones don't have getNamesRemote at all and a
            // reflection lookup will miss, which is fine — the map
            // stays empty for them.
            if (p.type != "modem") continue
            val m = try {
                p::class.java.methods.firstOrNull {
                    it.name == "getNamesRemote" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0] == IComputerAccess::class.java
                } ?: continue
            } catch (_: Throwable) { continue }
            val names: Collection<String> = try {
                @Suppress("UNCHECKED_CAST")
                m.invoke(p, this) as? Collection<String> ?: continue
            } catch (_: Throwable) { continue }
            for (name in names) fresh[name] = p
        }
        remoteToModem.clear()
        remoteToModem.putAll(fresh)
    }

    /** Remote-name → owning-modem map snapshot for the RPC handlers. */
    fun remotePeripherals(): Map<String, IPeripheral> = remoteToModem.toMap()

    /**
     * Resolve a user-facing name (side or remote) to its peripheral.
     * Remote names go through the owning modem; callers that need to
     * invoke a method then drive `callRemote` / `getMethodsRemote`.
     */
    fun resolvePeripheral(name: String): PeripheralRef? {
        sideToPeripheral[name]?.let { return PeripheralRef.Direct(it) }
        remoteToModem[name]?.let { return PeripheralRef.Remote(it, name) }
        return null
    }

    fun shutdown() {
        // Detach everything we attached so peripherals tear down their
        // per-computer wrapper tables; guarded with runCatching because
        // a misbehaving peripheral's detach shouldn't take the machine
        // teardown with it.
        for (p in attachedPeripherals) runCatching { p.detach(this) }
        attachedPeripherals.clear()
        sideToPeripheral.clear()
        remoteToModem.clear()
        rpcManager = null
    }

    /**
     * How a resolved name should be invoked. Side-adjacent peripherals
     * go straight through [ScevPeripheralMethods]; remotes go through
     * the owning modem's `callRemote` / `getMethodsRemote`.
     */
    sealed class PeripheralRef {
        data class Direct(val peripheral: IPeripheral) : PeripheralRef()
        data class Remote(val modem: IPeripheral, val remoteName: String) : PeripheralRef()
    }

    /* ---------------- IComputerAccess ---------------- */

    override fun mount(desiredLocation: String, mount: Mount, driveName: String): String? = null

    override fun mountWritable(desiredLocation: String, mount: WritableMount, driveName: String): String? = null

    override fun unmount(location: String?) {}

    /** Stable int id derived from the machine's UUID. */
    override fun getID(): Int = machineUuid.hashCode().ushr(1)  // mask sign bit

    /**
     * Forward peripheral-queued events to:
     *  1. the guest, as scev RPC events (subscribe-style filtering is a
     *     follow-up; today events always go out and the guest filters);
     *  2. the internal [eventChannel], so any currently-suspended
     *     `pullEvent` inside the dispatch loop picks them up.
     *
     * Non-blocking via `trySend` on the UNLIMITED channel — won't block
     * the peripheral thread that called `queueEvent`.
     */
    override fun queueEvent(event: String, vararg arguments: Any?) {
        val mgr = rpcManager ?: ScevRpcManager.get(machineUuid)?.also { rpcManager = it }
        if (mgr != null) {
            val args = buildList<MsgValue>(arguments.size) {
                arguments.forEach { add(javaObjectToMsg(it)) }
            }
            mgr.sendEvent(RpcFrame.event(event, args))
        }
        eventChannel.trySend(Event(event, arguments.toList()))
        observeEvent(event, arguments.toList())
    }

    /**
     * Inject an event from the guest side (via [METHOD_QUEUE_EVENT]).
     * Reaches suspended peripheral dispatches the same way a
     * peripheral-originated event would — matching CC's semantics where
     * `os.queueEvent` from Lua wakes `pullEvent`s on the same computer.
     */
    fun injectEventFromGuest(name: String, args: List<Any?>) {
        eventChannel.trySend(Event(name, args))
        observeEvent(name, args)
    }

    /** Record one event observation into [eventSchemas]. Thread-safe. */
    private fun observeEvent(name: String, args: List<Any?>) {
        val shape = args.map { javaTypeTag(it) }
        val entry = eventSchemas.computeIfAbsent(name) { EventSchemaEntry() }
        entry.total.incrementAndGet()
        // Cap distinct shapes per event to avoid pathological growth
        // (e.g. an event whose payload includes a monotonically-
        // incrementing string). If the cap is hit, collapse further
        // observations into a sentinel shape rather than growing.
        val target = if (entry.shapes.size >= MAX_SHAPES_PER_EVENT && !entry.shapes.containsKey(shape)) {
            OVERFLOW_SHAPE
        } else {
            shape
        }
        entry.shapes.computeIfAbsent(target) { AtomicLong(0) }.incrementAndGet()
    }

    /** Snapshot the learned event schemas. Stable order: by descending observation count. */
    fun eventSchemas(): List<EventSchemaSnapshot> =
        eventSchemas.entries
            .map { (name, entry) ->
                EventSchemaSnapshot(
                    name = name,
                    observations = entry.total.get(),
                    shapes = entry.shapes.entries
                        .associate { (k, v) -> k to v.get() },
                )
            }
            .sortedByDescending { it.observations }

    /** Single-event schema snapshot, or null if nothing observed. */
    fun eventSchema(name: String): EventSchemaSnapshot? = eventSchemas[name]?.let { entry ->
        EventSchemaSnapshot(
            name = name,
            observations = entry.total.get(),
            shapes = entry.shapes.entries.associate { (k, v) -> k to v.get() },
        )
    }

    /** Clear all observed schemas. Intended for tests and the `schema clear` RPC. */
    fun clearEventSchemas() {
        eventSchemas.clear()
    }

    /* ---------------- dispatch tracing ---------------- */

    /** Toggle the per-computer dispatch trace on or off. */
    fun setTraceEnabled(on: Boolean) {
        traceEnabled = on
        if (!on) clearTrace()
    }

    fun isTraceEnabled(): Boolean = traceEnabled

    /** Snapshot the trace buffer. Oldest-first. Safe for concurrent readers. */
    fun traceSnapshot(): List<DispatchTrace> = synchronized(traceLog) { traceLog.toList() }

    /** Drop every recorded trace entry. */
    fun clearTrace() = synchronized(traceLog) { traceLog.clear() }

    /**
     * Record one dispatch. No-op when tracing is off. The bounded ring
     * buffer drops the oldest entry when full.
     */
    fun recordTrace(entry: DispatchTrace) {
        if (!traceEnabled) return
        synchronized(traceLog) {
            while (traceLog.size >= MAX_TRACE_ENTRIES) traceLog.removeFirst()
            traceLog.addLast(entry)
        }
    }

    /**
     * Wait for an event. [filter] null matches any event; otherwise
     * events with a non-matching name are drained and discarded (the
     * guest has already received a copy via the RPC event fan-out, so
     * "discard" only means "this particular dispatch's pullEvent
     * doesn't consume it").
     */
    suspend fun awaitEvent(filter: String?): Event {
        while (true) {
            val evt = eventChannel.receive()
            if (filter == null || evt.name == filter) return evt
        }
    }

    override fun getAttachmentName(): String = "scev_" + machineUuid.toString().take(8)

    /**
     * Returns the full set of peripherals this computer can see — both
     * directly-adjacent (under side names) and reachable through any
     * adjacent wired modem (under CC-assigned names like `monitor_4`).
     * Matches Lua's `peripheral.getNames()` convention.
     *
     * For remote peripherals, the value is the owning MODEM — not the
     * remote peripheral itself. Dispatching against that reference is
     * what `callRemote` expects. scev's `list`/`call`/`methods`
     * handlers use [resolvePeripheral] instead, which returns a
     * [PeripheralRef] distinguishing direct from remote.
     */
    override fun getAvailablePeripherals(): Map<String, IPeripheral> {
        val merged = HashMap<String, IPeripheral>(sideToPeripheral.size + remoteToModem.size)
        merged.putAll(sideToPeripheral)
        merged.putAll(remoteToModem)
        return merged
    }

    override fun getAvailablePeripheral(name: String): IPeripheral? =
        sideToPeripheral[name] ?: remoteToModem[name]

    override fun getMainThreadMonitor(): WorkMonitor? = null

    /* ---------------- helpers ---------------- */

    /** For tests: inject a peripheral on a given side without world lookup. */
    fun setPeripheralForTests(side: String, peripheral: IPeripheral?) {
        if (peripheral == null) sideToPeripheral.remove(side)
        else sideToPeripheral[side] = peripheral
    }

    companion object {
        /** Cap on distinct shapes recorded per event name. See [observeEvent]. */
        const val MAX_SHAPES_PER_EVENT = 16

        /** Sentinel shape inserted when [MAX_SHAPES_PER_EVENT] is exceeded. */
        val OVERFLOW_SHAPE: List<String> = listOf("…")

        /** Cap on retained dispatch-trace entries. See [recordTrace]. */
        const val MAX_TRACE_ENTRIES = 256

        /**
         * One-word Java type tag for the event-schema learner. Matches
         * what CC's Lua runtime would report (`number`, `string`,
         * `boolean`, `nil`, `table`, `bytes`) plus a catch-all
         * `other(ClassName)` that keeps the value but doesn't pretend
         * to be a Lua type.
         */
        internal fun javaTypeTag(v: Any?): String = when (v) {
            null -> "nil"
            is Boolean -> "boolean"
            is Number -> "number"
            is String -> "string"
            is ByteArray -> "bytes"
            is Map<*, *> -> "table"
            is Collection<*>, is Array<*> -> "array"
            else -> "other(${v::class.java.simpleName})"
        }

        /**
         * Relative side name for `dir` given the scev block is looking
         * toward `facing`. Matches CC's convention: `front` is the
         * direction the block faces, `back` the opposite, `left` /
         * `right` the perpendicular horizontals, `top` / `bottom`
         * unaffected by horizontal facing.
         *
         * CC exposes both this relative naming and the absolute one
         * (north/south/east/west/up/down, via
         * [Direction.serializedName]); both are keys in
         * [sideToPeripheral] and resolve to the same [IPeripheral].
         */
        internal fun relativeSideOf(dir: Direction, facing: Direction): String {
            if (dir == Direction.UP) return "top"
            if (dir == Direction.DOWN) return "bottom"
            return when (dir) {
                facing -> "front"
                facing.opposite -> "back"
                facing.counterClockWise -> "left"
                facing.clockWise -> "right"
                else -> dir.serializedName  // defensive; shouldn't be hit
            }
        }

        /**
         * Minimal Java-object → MsgValue coercion for queueEvent args.
         * Keeps responsibilities separate from [LuaValueConverter.toMsg]
         * — this path doesn't reach into generic collections the way
         * peripheral return values do.
         */
        private fun javaObjectToMsg(o: Any?): MsgValue = when (o) {
            null -> MsgValue.NIL
            is Boolean -> MsgValue.of(o)
            is Byte, is Short, is Int, is Long -> MsgValue.of((o as Number).toLong())
            is Float, is Double -> MsgValue.of((o as Number).toDouble())
            is String -> MsgValue.of(o)
            is ByteArray -> MsgValue.of(o)
            is Array<*> -> MsgValue.ofArray(o.map { javaObjectToMsg(it) })
            is Iterable<*> -> MsgValue.ofArray(o.map { javaObjectToMsg(it) })
            is Map<*, *> -> MsgValue.ofMap(
                o.entries.associate { (k, v) ->
                    javaObjectToMsg(k) to javaObjectToMsg(v)
                }
            )
            else -> MsgValue.of(o.toString())
        }
    }
}
