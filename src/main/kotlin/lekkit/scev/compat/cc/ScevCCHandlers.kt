/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc

import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.api.lua.ObjectArguments
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IPeripheral
import kotlinx.coroutines.sync.withLock
import lekkit.scev.rpc.MsgValue
import lekkit.scev.rpc.RpcDispatcher
import lekkit.scev.rpc.RpcHandler
import lekkit.scev.rpc.RpcProtocol
import java.lang.reflect.Field

/**
 * RPC handlers backed by a [ScevCCComputer].
 *
 * The guest speaks our RPC protocol (MessagePack-over-COBS on ttyS1);
 * these handlers translate between that and CC's peripheral/computer
 * model. Each machine's dispatcher gets its own copy installed — they
 * close over the machine-specific [ScevCCComputer].
 *
 * Methods handled:
 *
 *  - [RpcProtocol.METHOD_LIST] — enumerate every adjacent peripheral
 *    this computer can see. Returns an array of
 *    `{peer, types[], class?}` entries. `class` is the implementing
 *    Java class's fully-qualified name, useful as a breadcrumb when
 *    dealing with third-party peripherals.
 *  - [RpcProtocol.METHOD_METHODS] — flat list of method names on a
 *    specific peripheral. Kept for back-compat; richer information is
 *    available via [RpcProtocol.METHOD_DESCRIBE].
 *  - [RpcProtocol.METHOD_CALL] — invoke a peripheral method. v1
 *    synchronous dispatch via [ScevPeripheralMethods] — yielding
 *    [MethodResult]s are on the follow-up task list.
 *  - [RpcProtocol.METHOD_QUEUE_EVENT] — forward a guest-originated
 *    event to the computer's own event bus.
 *  - [RpcProtocol.METHOD_DESCRIBE] — reflection-derived signatures
 *    for every `@LuaFunction` method on a peripheral, grouped by
 *    declaring class. Optional second argument narrows to a single
 *    method.
 *  - [RpcProtocol.METHOD_SCHEMA] — observed event-argument shapes.
 *    Optional arg filters to a single event name; `"clear"` resets the
 *    learner.
 *  - [RpcProtocol.METHOD_TYPE] — peripheral type(s) + implementing
 *    class name.
 *  - [RpcProtocol.METHOD_TRACE] — toggle / dump / clear the dispatch
 *    trace log. Subcommand in args[0]: `"on"`, `"off"`, `"dump"`,
 *    `"clear"`, `"status"`.
 */
internal object ScevCCHandlers {
    fun install(d: RpcDispatcher, computer: ScevCCComputer) {
        d.register(RpcProtocol.METHOD_LIST, RpcHandler { _ -> list(computer) })
        d.register(RpcProtocol.METHOD_METHODS, RpcHandler { args -> methods(computer, args) })
        d.register(RpcProtocol.METHOD_CALL, RpcHandler { args -> call(computer, args) })
        d.register(RpcProtocol.METHOD_QUEUE_EVENT, RpcHandler { args -> queueEvent(computer, args) })
        d.register(RpcProtocol.METHOD_DESCRIBE, RpcHandler { args -> describe(computer, args) })
        d.register(RpcProtocol.METHOD_SCHEMA, RpcHandler { args -> schema(computer, args) })
        d.register(RpcProtocol.METHOD_TYPE, RpcHandler { args -> type(computer, args) })
        d.register(RpcProtocol.METHOD_TRACE, RpcHandler { args -> trace(computer, args) })
        // Subscribe/unsubscribe are no-ops today: events always flow to
        // the guest via ScevRpcManager.sendEvent, and the guest filters
        // client-side. Register real (no-op) handlers so the "CC not
        // installed" stubs from ScevRpcHandlers don't respond with an
        // error when CC actually IS installed.
        d.register(RpcProtocol.METHOD_SUBSCRIBE, RpcHandler { _ -> MsgValue.NIL })
        d.register(RpcProtocol.METHOD_UNSUBSCRIBE, RpcHandler { _ -> MsgValue.NIL })
    }

    /* ---------------- list ---------------- */

    private suspend fun list(computer: ScevCCComputer): MsgValue {
        val entries = mutableListOf<MsgValue>()
        // Direct side neighbours — type+additionalTypes are free to read.
        for ((side, p) in computer.availablePeripherals) {
            val ref = computer.resolvePeripheral(side)
            when (ref) {
                is ScevCCComputer.PeripheralRef.Direct ->
                    entries.add(peripheralEntry(side, p.type, p.additionalTypes, p::class.java.name))
                is ScevCCComputer.PeripheralRef.Remote ->
                    entries.add(remotePeripheralEntry(computer, ref))
                null -> {}
            }
        }
        return MsgValue.ofArray(entries)
    }

    private fun peripheralEntry(name: String, type: String, extras: Set<String>, className: String?): MsgValue {
        val entry = linkedMapOf<MsgValue, MsgValue>()
        entry[MsgValue.of("peer")] = MsgValue.of(name)
        val types = mutableListOf(MsgValue.of(type))
        for (extra in extras) types.add(MsgValue.of(extra))
        entry[MsgValue.of("types")] = MsgValue.ofArray(types)
        if (className != null) entry[MsgValue.of("class")] = MsgValue.of(className)
        return MsgValue.ofMap(entry)
    }

    /**
     * Remote peripheral entry for the `list` RPC. Types come from the
     * modem's `getTypeRemote(computer, name)` which — in vanilla CC's
     * wired modem impl — returns `Object[]{ type, ...additionalTypes }`.
     * We invoke it through the same dispatcher used by `call`, so any
     * peripheral-side LuaException propagates as a typed RPC error.
     *
     * No `class` field for remotes: we only know the owning modem's
     * class, not the remote peripheral's — that's modem-internal.
     */
    private suspend fun remotePeripheralEntry(
        computer: ScevCCComputer,
        ref: ScevCCComputer.PeripheralRef.Remote,
    ): MsgValue {
        val entry = linkedMapOf<MsgValue, MsgValue>()
        entry[MsgValue.of("peer")] = MsgValue.of(ref.remoteName)

        val typesList = mutableListOf<MsgValue>()
        try {
            val ctx = ScevLuaContext()
            val args = ObjectArguments(ref.remoteName)
            val result = ScevPeripheralMethods.dispatch(
                ref.modem, computer, ctx, "getTypeRemote", args,
            )
            val values = result.result ?: arrayOf()
            for (v in values) if (v is String) typesList.add(MsgValue.of(v))
        } catch (_: LuaException) {
            // getTypeRemote failing on a stale name shouldn't nuke the
            // whole list — just emit an empty types array.
        }
        entry[MsgValue.of("types")] = MsgValue.ofArray(typesList)
        // If we can reach through the modem wrapper, surface the real
        // implementing class — same breadcrumb we emit for direct peers.
        underlyingRemotePeripheral(ref.modem, computer, ref.remoteName)?.let { p ->
            entry[MsgValue.of("class")] = MsgValue.of(p::class.java.name)
        }
        return MsgValue.ofMap(entry)
    }

    /* ---------------- methods ---------------- */

    @Throws(RpcHandler.RpcException::class)
    private suspend fun methods(computer: ScevCCComputer, args: List<MsgValue>): MsgValue {
        val target = requireString(args, 0, "peer")
        val ref = computer.resolvePeripheral(target)
            ?: throw RpcHandler.RpcException("no such peripheral: $target")
        return when (ref) {
            is ScevCCComputer.PeripheralRef.Direct ->
                MsgValue.ofArray(ScevPeripheralMethods.methodNames(ref.peripheral).map { MsgValue.of(it) })
            is ScevCCComputer.PeripheralRef.Remote -> {
                // Route through the owning modem's getMethodsRemote.
                // Returns Object[]{ List<String> methodNames } on success.
                val ctx = ScevLuaContext()
                val arguments = ObjectArguments(ref.remoteName)
                val result = try {
                    ScevPeripheralMethods.dispatch(
                        ref.modem, computer, ctx, "getMethodsRemote", arguments,
                    )
                } catch (e: LuaException) {
                    throw RpcHandler.RpcException(e.message ?: "Lua error")
                }
                // Unwrap: result.result is Object[]; first element is
                // the actual method-name collection.
                val values = result.result ?: return MsgValue.ofArray(emptyList())
                val inner = values.firstOrNull() ?: return MsgValue.ofArray(emptyList())
                @Suppress("UNCHECKED_CAST")
                val names: Collection<String> = when (inner) {
                    is Collection<*> -> inner.filterIsInstance<String>()
                    is Array<*> -> inner.filterIsInstance<String>()
                    else -> emptyList()
                }
                MsgValue.ofArray(names.map { MsgValue.of(it) })
            }
        }
    }

    /* ---------------- call ---------------- */

    @Throws(RpcHandler.RpcException::class)
    private suspend fun call(computer: ScevCCComputer, args: List<MsgValue>): MsgValue {
        val target = requireString(args, 0, "peer")
        val method = requireString(args, 1, "method")
        val callArgs: List<MsgValue> =
            if (args.size > 2) args.subList(2, args.size) else emptyList()

        val ref = computer.resolvePeripheral(target)
            ?: throw RpcHandler.RpcException("no such peripheral: $target")

        val ctx = ScevLuaContext()
        val javaArgs = callArgs.map { LuaValueConverter.toLua(it) }.toTypedArray()

        // Remote (modem-attached) peripherals get routed through the
        // owning modem's `callRemote(remote, method, ...)`. Direct
        // neighbours dispatch against the peripheral itself.
        val (dispatchPeripheral, dispatchMethod, dispatchArgs) = when (ref) {
            is ScevCCComputer.PeripheralRef.Direct ->
                Triple(ref.peripheral, method, ObjectArguments(*javaArgs))

            is ScevCCComputer.PeripheralRef.Remote -> {
                // callRemote(remoteName, method, ...args) — prepend the
                // two positional args the modem expects, then the
                // user's.
                val prefix = arrayOf<Any?>(ref.remoteName, method)
                val merged = Array<Any?>(prefix.size + javaArgs.size) { i ->
                    if (i < prefix.size) prefix[i] else javaArgs[i - prefix.size]
                }
                Triple(ref.modem, "callRemote", ObjectArguments(*merged))
            }
        }

        // Serialise per-computer dispatch so yielding methods don't
        // race each other's event-queue draining. Matches real CC
        // where one Lua coroutine processes one peripheral call at a
        // time; concurrent RPC calls from the guest queue up.
        val startedAt = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        val argsSummary = summarizeArgs(callArgs)
        val result = try {
            val r = computer.dispatchMutex.withLock {
                ScevPeripheralMethods.dispatch(
                    dispatchPeripheral, computer, ctx, dispatchMethod, dispatchArgs,
                )
            }
            val durationUs = (System.nanoTime() - startNanos) / 1000
            val detail = r.result?.let { vs ->
                when (vs.size) {
                    0 -> "nil"
                    1 -> summarizeJava(vs[0])
                    else -> "${vs.size} values"
                }
            }
            computer.recordTrace(
                ScevCCComputer.DispatchTrace(
                    startedAt = startedAt,
                    durationMicros = durationUs,
                    peripheralName = target,
                    method = method,
                    argsSummary = argsSummary,
                    outcome = "ok",
                    detail = detail,
                ),
            )
            r
        } catch (e: LuaException) {
            val durationUs = (System.nanoTime() - startNanos) / 1000
            computer.recordTrace(
                ScevCCComputer.DispatchTrace(
                    startedAt = startedAt,
                    durationMicros = durationUs,
                    peripheralName = target,
                    method = method,
                    argsSummary = argsSummary,
                    outcome = "error",
                    detail = e.message,
                ),
            )
            throw RpcHandler.RpcException(e.message ?: "Lua error")
        } catch (e: RuntimeException) {
            val durationUs = (System.nanoTime() - startNanos) / 1000
            computer.recordTrace(
                ScevCCComputer.DispatchTrace(
                    startedAt = startedAt,
                    durationMicros = durationUs,
                    peripheralName = target,
                    method = method,
                    argsSummary = argsSummary,
                    outcome = "error",
                    detail = e.message,
                ),
            )
            throw RpcHandler.RpcException("$method: ${e.message}")
        }

        val values = result.result ?: return MsgValue.NIL
        return when (values.size) {
            0 -> MsgValue.NIL
            1 -> LuaValueConverter.toMsg(values[0])
            else -> MsgValue.ofArray(values.map { LuaValueConverter.toMsg(it) })
        }
    }

    /* ---------------- queue_event ---------------- */

    @Throws(RpcHandler.RpcException::class)
    private fun queueEvent(computer: ScevCCComputer, args: List<MsgValue>): MsgValue {
        if (args.isEmpty() || !args[0].isString) {
            throw RpcHandler.RpcException("queue_event: first arg must be the event name")
        }
        val name = args[0].asString()
        val rest = if (args.size > 1) args.subList(1, args.size) else emptyList()
        val luaArgs = LuaValueConverter.toLuaArgs(rest)
        computer.queueEvent(name, *luaArgs)
        return MsgValue.NIL
    }

    /* ---------------- describe ---------------- */

    /**
     * Reflection-derived signature descriptions for one peripheral.
     * No arg beyond the peer → all methods, grouped by declaring class.
     * Two args → single method description.
     *
     * Structured response (describing everything):
     * ```
     * {
     *   peer:  <name>,
     *   type:  <first type>,
     *   types: [<type>, ...],
     *   class: <fqn>,
     *   groups: {
     *     <declaringClass>: [{ name, aliases, params: [{luaType, optional, enumValues?}], return, mainThread, unsafe, signature }, ...]
     *   }
     * }
     * ```
     * Single-method form drops `groups` in favour of a flat method map
     * keyed `method`.
     *
     * Remote peripherals (wired-modem) can't introspect — we don't
     * have the far-side class. Return a structured `{dynamic: true}`
     * marker plus the flat name list.
     */
    @Throws(RpcHandler.RpcException::class)
    private suspend fun describe(computer: ScevCCComputer, args: List<MsgValue>): MsgValue {
        val target = requireString(args, 0, "peer")
        val ref = computer.resolvePeripheral(target)
            ?: throw RpcHandler.RpcException("no such peripheral: $target")
        return when (ref) {
            is ScevCCComputer.PeripheralRef.Direct -> describeDirect(target, ref.peripheral, args)
            is ScevCCComputer.PeripheralRef.Remote -> describeRemote(computer, ref, args)
        }
    }

    private fun describeDirect(
        name: String,
        peripheral: IPeripheral,
        args: List<MsgValue>,
    ): MsgValue {
        val sigs = ScevPeripheralMethods.signaturesFor(peripheral)
        val methodFilter = if (args.size > 1 && args[1].isString) args[1].asString() else null

        val base = linkedMapOf<MsgValue, MsgValue>()
        base[MsgValue.of("peer")] = MsgValue.of(name)
        base[MsgValue.of("type")] = MsgValue.of(peripheral.type)
        base[MsgValue.of("types")] = MsgValue.ofArray(
            (listOf(peripheral.type) + peripheral.additionalTypes).map { MsgValue.of(it) },
        )
        base[MsgValue.of("class")] = MsgValue.of(peripheral::class.java.name)

        if (methodFilter != null) {
            val sig = sigs[methodFilter]
                ?: throw RpcHandler.RpcException("no such method on $name: $methodFilter")
            base[MsgValue.of("method")] = signatureMsg(sig)
            return MsgValue.ofMap(base)
        }

        // Group by declaredBy — TreeMap for stable output, inner list sorted by name.
        val groups = java.util.TreeMap<String, MutableList<ScevPeripheralMethods.MethodSignature>>()
        for ((_, sig) in sigs) {
            groups.computeIfAbsent(sig.declaredBy) { mutableListOf() }.add(sig)
        }
        val groupsMsg = linkedMapOf<MsgValue, MsgValue>()
        for ((declaringClass, methods) in groups) {
            methods.sortBy { it.name }
            groupsMsg[MsgValue.of(declaringClass)] = MsgValue.ofArray(methods.map { signatureMsg(it) })
        }
        base[MsgValue.of("groups")] = MsgValue.ofMap(groupsMsg)
        return MsgValue.ofMap(base)
    }

    private suspend fun describeRemote(
        computer: ScevCCComputer,
        ref: ScevCCComputer.PeripheralRef.Remote,
        args: List<MsgValue>,
    ): MsgValue {
        // Wired modems attach each remote peripheral to our IComputerAccess
        // by stashing the actual IPeripheral in a private wrapper map.
        // Reach through that wrapper so we can run the same reflection-
        // based introspection as for direct-side peripherals — the real
        // @LuaFunction methods are right there once we have the instance.
        val underlying = underlyingRemotePeripheral(ref.modem, computer, ref.remoteName)
        if (underlying != null) {
            return describeDirect(ref.remoteName, underlying, args)
        }

        // Wrapper-reflection miss (CC internals changed, or the wrapper
        // hasn't been set up yet). Fall back to the method-name list from
        // getMethodsRemote and mark the response `dynamic` so the guest
        // can use the bad-arg probe trick instead of real signatures.
        val ctx = ScevLuaContext()
        val arguments = ObjectArguments(ref.remoteName)
        val result = try {
            ScevPeripheralMethods.dispatch(
                ref.modem, computer, ctx, "getMethodsRemote", arguments,
            )
        } catch (e: LuaException) {
            throw RpcHandler.RpcException(e.message ?: "Lua error")
        }
        val values = result.result ?: arrayOf()
        val inner = values.firstOrNull()
        @Suppress("UNCHECKED_CAST")
        val names: Collection<String> = when (inner) {
            is Collection<*> -> inner.filterIsInstance<String>()
            is Array<*> -> inner.filterIsInstance<String>()
            else -> emptyList()
        }
        val out = linkedMapOf<MsgValue, MsgValue>()
        out[MsgValue.of("peer")] = MsgValue.of(ref.remoteName)
        out[MsgValue.of("dynamic")] = MsgValue.of(true)
        out[MsgValue.of("methods")] = MsgValue.ofArray(names.map { MsgValue.of(it) })
        return MsgValue.ofMap(out)
    }

    /**
     * Pull the actual `IPeripheral` behind a wired-modem remote name,
     * or `null` if we can't reach it.
     *
     * CC's `WiredModemPeripheral` keeps a private
     * `Map<IComputerAccess, Map<String, RemotePeripheralWrapper>>` —
     * one wrapper per (computer, remote-name) pair, populated during
     * `attach(computer)`. Each wrapper holds a private `peripheral`
     * field pointing at the real peripheral. We reflect in to surface
     * it so introspection (`describe` / `type` / class name) matches
     * what the guest gets for direct-side neighbours.
     *
     * Best-effort: any reflection failure returns null so callers fall
     * back to modem-RPC-only paths. CC internals can rename these
     * fields between versions — the cache invalidates automatically
     * because we walk up the class hierarchy each time we miss.
     */
    internal fun underlyingRemotePeripheral(
        modem: IPeripheral,
        computer: IComputerAccess,
        name: String,
    ): IPeripheral? = try {
        val wrappersField = wrappersFieldFor(modem.javaClass)
        @Suppress("UNCHECKED_CAST")
        val byComputer = wrappersField?.get(modem) as? Map<Any?, Any?>
        val inner = byComputer?.get(computer) as? Map<*, *>
        val wrapper = inner?.get(name)
        if (wrapper == null) null else wrapperPeripheralField(wrapper.javaClass)?.get(wrapper) as? IPeripheral
    } catch (_: Throwable) {
        null
    }

    /**
     * Walk up a modem class's hierarchy looking for the private
     * `peripheralWrappers` field. Cached per concrete modem class
     * (including misses, via an Optional) because reflection lookups
     * on a modded-mod's peripheral chain aren't free to repeat.
     */
    private val wrappersFieldCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.Optional<Field>>()

    private fun wrappersFieldFor(modemCls: Class<*>): Field? =
        wrappersFieldCache.computeIfAbsent(modemCls) { cls ->
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    val f = c.getDeclaredField("peripheralWrappers")
                    f.isAccessible = true
                    return@computeIfAbsent java.util.Optional.of(f)
                } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            java.util.Optional.empty()
        }.orElse(null)

    private val wrapperPeripheralFieldCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.Optional<Field>>()

    private fun wrapperPeripheralField(wrapperCls: Class<*>): Field? =
        wrapperPeripheralFieldCache.computeIfAbsent(wrapperCls) { cls ->
            try {
                val f = cls.getDeclaredField("peripheral")
                f.isAccessible = true
                java.util.Optional.of(f)
            } catch (_: NoSuchFieldException) {
                java.util.Optional.empty()
            }
        }.orElse(null)

    private fun signatureMsg(sig: ScevPeripheralMethods.MethodSignature): MsgValue {
        val m = linkedMapOf<MsgValue, MsgValue>()
        m[MsgValue.of("name")] = MsgValue.of(sig.name)
        m[MsgValue.of("aliases")] = MsgValue.ofArray(sig.aliases.map { MsgValue.of(it) })
        val paramsList = sig.params.map { p ->
            val pm = linkedMapOf<MsgValue, MsgValue>()
            pm[MsgValue.of("luaType")] = MsgValue.of(p.luaType)
            pm[MsgValue.of("optional")] = MsgValue.of(p.optional)
            p.enumValues?.let { vs ->
                pm[MsgValue.of("enumValues")] = MsgValue.ofArray(vs.map { MsgValue.of(it) })
            }
            MsgValue.ofMap(pm)
        }
        m[MsgValue.of("params")] = MsgValue.ofArray(paramsList)
        m[MsgValue.of("return")] = MsgValue.of(sig.returnShape.name.lowercase())
        m[MsgValue.of("mainThread")] = MsgValue.of(sig.mainThread)
        m[MsgValue.of("unsafe")] = MsgValue.of(sig.unsafe)
        m[MsgValue.of("declaredBy")] = MsgValue.of(sig.declaredBy)
        m[MsgValue.of("signature")] = MsgValue.of(sig.signatureString())
        return MsgValue.ofMap(m)
    }

    /* ---------------- schema ---------------- */

    /**
     * Observed event-shape learner.
     *
     *  - no args                 → all observed events, sorted by
     *                               observation count descending.
     *  - args[0] = "clear"       → reset the learner.
     *  - args[0] = <event-name>  → single event's observed shapes.
     */
    private fun schema(computer: ScevCCComputer, args: List<MsgValue>): MsgValue {
        if (args.isNotEmpty() && args[0].isString) {
            val first = args[0].asString()
            if (first == "clear") {
                computer.clearEventSchemas()
                return MsgValue.NIL
            }
            val snap = computer.eventSchema(first)
                ?: return MsgValue.ofMap(linkedMapOf(MsgValue.of("name") to MsgValue.of(first), MsgValue.of("observations") to MsgValue.of(0L)))
            return eventSchemaMsg(snap)
        }
        return MsgValue.ofArray(computer.eventSchemas().map { eventSchemaMsg(it) })
    }

    private fun eventSchemaMsg(snap: ScevCCComputer.EventSchemaSnapshot): MsgValue {
        val out = linkedMapOf<MsgValue, MsgValue>()
        out[MsgValue.of("name")] = MsgValue.of(snap.name)
        out[MsgValue.of("observations")] = MsgValue.of(snap.observations)
        val shapesOut = linkedMapOf<MsgValue, MsgValue>()
        for ((shape, count) in snap.shapes.entries.sortedByDescending { it.value }) {
            // Key shapes as "(type, type, type)" so the MessagePack map
            // is still a flat {string → int}. Reconstructing from tuples
            // is guest-side work.
            val key = "(" + shape.joinToString(", ") + ")"
            shapesOut[MsgValue.of(key)] = MsgValue.of(count)
        }
        out[MsgValue.of("shapes")] = MsgValue.ofMap(shapesOut)
        return MsgValue.ofMap(out)
    }

    /* ---------------- type ---------------- */

    /**
     * Peripheral type + implementing class breadcrumb. Lightweight
     * alternative to `list` when the guest already knows the peer name.
     */
    @Throws(RpcHandler.RpcException::class)
    private suspend fun type(computer: ScevCCComputer, args: List<MsgValue>): MsgValue {
        val target = requireString(args, 0, "peer")
        val ref = computer.resolvePeripheral(target)
            ?: throw RpcHandler.RpcException("no such peripheral: $target")
        val out = linkedMapOf<MsgValue, MsgValue>()
        out[MsgValue.of("peer")] = MsgValue.of(target)
        when (ref) {
            is ScevCCComputer.PeripheralRef.Direct -> {
                val p = ref.peripheral
                out[MsgValue.of("type")] = MsgValue.of(p.type)
                val types = mutableListOf<MsgValue>(MsgValue.of(p.type))
                for (extra in p.additionalTypes) types.add(MsgValue.of(extra))
                out[MsgValue.of("types")] = MsgValue.ofArray(types)
                out[MsgValue.of("class")] = MsgValue.of(p::class.java.name)
            }
            is ScevCCComputer.PeripheralRef.Remote -> {
                val ctx = ScevLuaContext()
                val arguments = ObjectArguments(ref.remoteName)
                val result = try {
                    ScevPeripheralMethods.dispatch(
                        ref.modem, computer, ctx, "getTypeRemote", arguments,
                    )
                } catch (e: LuaException) {
                    throw RpcHandler.RpcException(e.message ?: "Lua error")
                }
                val values = result.result ?: arrayOf()
                val types = values.filterIsInstance<String>()
                out[MsgValue.of("type")] = if (types.isNotEmpty()) MsgValue.of(types[0]) else MsgValue.NIL
                out[MsgValue.of("types")] = MsgValue.ofArray(types.map { MsgValue.of(it) })
                out[MsgValue.of("remote")] = MsgValue.of(true)
                underlyingRemotePeripheral(ref.modem, computer, ref.remoteName)?.let { p ->
                    out[MsgValue.of("class")] = MsgValue.of(p::class.java.name)
                }
            }
        }
        return MsgValue.ofMap(out)
    }

    /* ---------------- trace ---------------- */

    /**
     * Dispatch-trace toggle / dump.
     *
     * Subcommand in args[0]:
     *  - "on"     → enable recording.
     *  - "off"    → disable recording (also clears the buffer).
     *  - "status" → return current enabled flag + buffered entry count.
     *  - "clear"  → empty the buffer without changing the enable state.
     *  - "dump" (default) → snapshot of current buffer, oldest-first.
     */
    @Throws(RpcHandler.RpcException::class)
    private fun trace(computer: ScevCCComputer, args: List<MsgValue>): MsgValue {
        val sub = if (args.isNotEmpty() && args[0].isString) args[0].asString() else "dump"
        return when (sub) {
            "on" -> { computer.setTraceEnabled(true); MsgValue.of(true) }
            "off" -> { computer.setTraceEnabled(false); MsgValue.of(false) }
            "clear" -> { computer.clearTrace(); MsgValue.NIL }
            "status" -> {
                val m = linkedMapOf<MsgValue, MsgValue>()
                m[MsgValue.of("enabled")] = MsgValue.of(computer.isTraceEnabled())
                m[MsgValue.of("buffered")] = MsgValue.of(computer.traceSnapshot().size.toLong())
                m[MsgValue.of("capacity")] = MsgValue.of(ScevCCComputer.MAX_TRACE_ENTRIES.toLong())
                MsgValue.ofMap(m)
            }
            "dump" -> {
                val entries = computer.traceSnapshot().map { t ->
                    val m = linkedMapOf<MsgValue, MsgValue>()
                    m[MsgValue.of("startedAt")] = MsgValue.of(t.startedAt)
                    m[MsgValue.of("durationUs")] = MsgValue.of(t.durationMicros)
                    m[MsgValue.of("peer")] = MsgValue.of(t.peripheralName)
                    m[MsgValue.of("method")] = MsgValue.of(t.method)
                    m[MsgValue.of("args")] = MsgValue.of(t.argsSummary)
                    m[MsgValue.of("outcome")] = MsgValue.of(t.outcome)
                    m[MsgValue.of("detail")] = t.detail?.let { MsgValue.of(it) } ?: MsgValue.NIL
                    MsgValue.ofMap(m)
                }
                MsgValue.ofArray(entries)
            }
            else -> throw RpcHandler.RpcException("trace: unknown subcommand '$sub' (on|off|status|dump|clear)")
        }
    }

    /* ---------------- helpers ---------------- */

    @Throws(RpcHandler.RpcException::class)
    private fun requireString(args: List<MsgValue>, idx: Int, name: String): String {
        if (args.size <= idx || !args[idx].isString) {
            throw RpcHandler.RpcException("expected string argument: $name")
        }
        return args[idx].asString()
    }

    /** Tight one-line summary for the trace log. */
    private fun summarizeArgs(args: List<MsgValue>): String {
        if (args.isEmpty()) return "()"
        val types = args.joinToString(", ") { msgTypeTag(it) }
        return "${args.size} arg(s): $types"
    }

    private fun summarizeJava(v: Any?): String = when (v) {
        null -> "nil"
        is Boolean, is Number, is String -> v.toString()
        is ByteArray -> "bytes[${v.size}]"
        is Collection<*> -> "array[${v.size}]"
        is Array<*> -> "array[${v.size}]"
        is Map<*, *> -> "table[${v.size}]"
        else -> v::class.java.simpleName
    }

    private fun msgTypeTag(v: MsgValue): String = when {
        v.isNil -> "nil"
        v.isBool -> "bool"
        v.isInt -> "int"
        v.isNumber -> "number"
        v.isString -> "string"
        v.isBytes -> "bytes"
        v.isArray -> "array"
        v.isMap -> "table"
        else -> "?"
    }
}
