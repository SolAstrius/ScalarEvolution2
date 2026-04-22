/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc

import dan200.computercraft.api.lua.Coerced
import dan200.computercraft.api.lua.IArguments
import dan200.computercraft.api.lua.ILuaContext
import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.api.lua.LuaFunction
import dan200.computercraft.api.lua.MethodResult
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IDynamicPeripheral
import dan200.computercraft.api.peripheral.IPeripheral
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.nio.ByteBuffer
import java.util.Optional
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Reimplementation of the subset of CC's `PeripheralMethodSupplier` we
 * need for the scev-as-computer direction.
 *
 * CC: Tweaked's own dispatcher (in `dan200.computercraft.core.asm`, not
 * part of the public API) uses bytecode generation for speed, and covers
 * the full menu of `@LuaFunction` parameter/return type quirks. We don't
 * have access to that dispatcher — the `core` package is not in the
 * `core-api` / `forge-api` jars scev compiles against — so we roll our
 * own reflection-based version.
 *
 * Coverage (v1):
 * - `@LuaFunction` annotated methods on [IPeripheral] implementations.
 *   Walks the full class hierarchy via [Class.getMethods], deduplicated
 *   by name. Respects the annotation's `value()` for explicit names.
 * - [IDynamicPeripheral.getMethodNames] / [IDynamicPeripheral.callMethod].
 * - Parameter types: primitives (int/long/double/float/boolean/short),
 *   [String], [ByteBuffer], `byte[]`, [Optional] of the same, [Coerced]
 *   of [String], [Map], [Enum] subclasses, [IArguments] passthrough,
 *   [IComputerAccess] and [ILuaContext] injection.
 * - Return types: void → empty, primitives and objects → single-value
 *   result, `Object[]` → multi-value, [MethodResult] → passthrough.
 * - `@LuaFunction(mainThread = true)` is honoured by dispatching inline
 *   because scev RPC handlers *already* run on the server main thread
 *   (server tick listener in [lekkit.scev.rpc.ScevRpcManager]). If that
 *   assumption ever changes, [dispatch] will need to route main-thread
 *   methods through the server executor.
 *
 * Alongside the invoker we now also capture a [MethodSignature] per
 * `@LuaFunction` method, used by the `describe` RPC to surface
 * everything reachable from reflection: param lua-types, optionality,
 * enum valid values, return shape, mainThread/unsafe flags, aliases,
 * declaring class. CC compiles without `-parameters` so real parameter
 * names are not available at runtime — we fall back to `argN` in the
 * signature string.
 *
 * Pending follow-up (tracked as a separate task):
 * - Yielding [MethodResult]s (pullEvent / yield with a callback). The
 *   v1 dispatcher returns a [MethodResult] the moment the method body
 *   returns, so peripherals that expect to pull events mid-call (modem
 *   transmit → modem_message, rednet) return an empty result without
 *   completing the resume loop. Follow-up wraps [dispatch] in a
 *   coroutine backed by a per-machine event queue, so `callback.resume`
 *   can drive the loop until a non-yielding result lands.
 *
 * Out of scope (throws [LuaException]):
 * - Generic parameter types other than the ones enumerated above (raw
 *   `Collection<T>`, `List<?>`, etc.). CC's first-party peripherals
 *   don't use these; add on demand.
 *
 * `@LuaFunction(unsafe = true)` is treated identically to the default —
 * the flag is a Lua-state-thread-safety hint, and we don't have a Lua
 * state machine to lock. The flag is still surfaced in [MethodSignature]
 * so the guest can see it.
 *
 * Invocation objects are cached per-[Class] because building them is
 * non-trivial (reflection over every method + generic parameter type
 * extraction). Cache is unbounded in principle but bounded in practice
 * by the number of distinct peripheral classes the guest ever sees.
 */
object ScevPeripheralMethods {
    private val tableCache: MutableMap<Class<*>, MethodTable> = ConcurrentHashMap()

    /**
     * Return the method table for the given peripheral's concrete class.
     * Handles [IDynamicPeripheral] by building a fresh table each time
     * (getMethodNames is per-instance by contract, though most impls
     * return a constant).
     */
    @JvmStatic
    fun forPeripheral(peripheral: IPeripheral): MethodTable {
        val staticTable = tableCache.computeIfAbsent(peripheral::class.java) { buildStatic(it) }
        return if (peripheral is IDynamicPeripheral) {
            MethodTable(
                staticTable.staticMethods,
                staticTable.signatures,
                peripheral.methodNames.filterNotNull().toList(),
            )
        } else {
            staticTable
        }
    }

    /** Enumerate method names visible to a caller. Sorted + deduped. */
    @JvmStatic
    fun methodNames(peripheral: IPeripheral): Set<String> {
        val table = forPeripheral(peripheral)
        val names = TreeSet<String>()
        names.addAll(table.staticMethods.keys)
        names.addAll(table.dynamicMethodNames)
        return names
    }

    /**
     * Return reflection-derived signatures for every `@LuaFunction`
     * method on this peripheral. Dynamic-peripheral method names have
     * no signature (we can't introspect `callMethod` dispatch shape)
     * and are absent from the returned map.
     *
     * Stable iteration order — sorted by method name — so guest output
     * is deterministic.
     */
    @JvmStatic
    fun signaturesFor(peripheral: IPeripheral): Map<String, MethodSignature> {
        val table = forPeripheral(peripheral)
        val sorted = TreeMap<String, MethodSignature>()
        sorted.putAll(table.signatures)
        return sorted
    }

    /**
     * Invoke `name` on `peripheral` with the callback loop folded in.
     *
     * The method's first call may return a [MethodResult] whose
     * `callback` is non-null — that's how CC peripherals yield waiting
     * for an event (`pullEvent`) or on `coroutine.yield`. We emulate
     * the Lua runtime's resume loop:
     *  1. If `callback` is null, we're done — return the result.
     *  2. Otherwise, treat the result's first value as a `pullEvent`
     *     filter (null = any) and suspend until a matching event
     *     arrives on the computer's [ScevCCComputer.awaitEvent] bus.
     *  3. Call `callback.resume(eventArgs)` to get the next
     *     MethodResult. Loop.
     *
     * The distinction between `pullEvent(filter, cb)` and plain
     * `yield(args, cb)` isn't part of the MethodResult shape; a plain
     * yield with multi-element args would be misread as "wait for
     * event named first-arg". CC peripherals in practice only yield
     * via pullEvent, so the heuristic is safe for our use case.
     *
     * Throws [LuaException] for: no such method, argument-coercion
     * failure, or whatever the target method throws.
     */
    @JvmStatic
    @Throws(LuaException::class)
    suspend fun dispatch(
        peripheral: IPeripheral,
        computer: IComputerAccess,
        context: ILuaContext,
        name: String,
        args: IArguments,
    ): MethodResult {
        val table = forPeripheral(peripheral)
        // Prefer static @LuaFunction methods over IDynamicPeripheral
        // for names that collide: that matches CC's own ordering
        // (dynamic methods fill in the gaps the annotated methods
        // don't cover).
        var result: MethodResult = when {
            table.staticMethods.containsKey(name) ->
                table.staticMethods[name]!!.invoke(peripheral, computer, context, args)
            peripheral is IDynamicPeripheral -> {
                val dynIdx = table.dynamicMethodNames.indexOf(name)
                if (dynIdx < 0) throw LuaException("No such method: $name")
                peripheral.callMethod(computer, context, dynIdx, args)
            }
            else -> throw LuaException("No such method: $name")
        }

        // Drive the yield/pullEvent resume loop until we get a
        // non-yielding MethodResult. Only ScevCCComputer has a proper
        // event bus wired up — for other IComputerAccess impls we can
        // only handle zero-yield results, so we bail.
        while (result.callback != null) {
            val cb = result.callback!!
            val filter = result.result?.firstOrNull() as? String
            if (computer !is ScevCCComputer) {
                throw LuaException(
                    "peripheral method '$name' yields, but this computer " +
                        "has no event bus (expected ScevCCComputer)",
                )
            }
            val evt = computer.awaitEvent(filter)
            // Lua pullEvent yields resume with [event_name, ...args].
            // Mirror that here. Null args become nulls in the array —
            // CC's callbacks tolerate it.
            val resumeArgs: Array<Any?> = Array(evt.args.size + 1) { i ->
                if (i == 0) evt.name else evt.args[i - 1]
            }
            result = cb.resume(resumeArgs)
        }
        return result
    }

    /** Reset internal caches — tests only. */
    @JvmStatic
    fun clearCacheForTests() {
        tableCache.clear()
    }

    // ---------------- public introspection types ----------------

    /**
     * What shape of return the method produces, as determined by
     * reflection. `None` = declared `void`, `One` = single value,
     * `Many` = declared `Object[]` (arbitrary count, not known
     * statically), `Dynamic` = declared `MethodResult` (pass-through;
     * could be anything including a yield).
     */
    enum class ReturnShape { NONE, ONE, MANY, DYNAMIC }

    /**
     * Reflection-derived description of one parameter.
     *
     * [luaType] is the canonical Lua-side type string (`"number"`,
     * `"string"`, `"string|bytes"`, `"table"`, `"any"`, …) matching
     * CC's own argument-error wording. [optional] is true when the
     * Java parameter is wrapped in [Optional]. [enumValues] is
     * populated for [Enum] parameters — the lowercased constant names,
     * matching CC's `LuaValues.checkEnum` convention.
     */
    data class ParamSpec(
        val luaType: String,
        val optional: Boolean = false,
        val enumValues: List<String>? = null,
    )

    /**
     * Everything `describe` needs to know about one `@LuaFunction`
     * method, captured during invoker construction. Pure data; no
     * runtime dependencies — safe to serialise to MessagePack and ship
     * to the guest.
     */
    data class MethodSignature(
        val name: String,
        val aliases: List<String>,
        val params: List<ParamSpec>,
        val returnShape: ReturnShape,
        val mainThread: Boolean,
        val unsafe: Boolean,
        /** simpleName of the class that physically declared the method. */
        val declaredBy: String,
    ) {
        /**
         * Human-readable one-liner: `name(p0: type, p1: type?) -> shape [main]`.
         *
         * Used by enriched error messages and the `describe` RPC's
         * textual form. Keeps parameter names as `arg0..argN` because
         * CC doesn't compile with `-parameters` — real names aren't
         * available at runtime.
         */
        fun signatureString(): String {
            val ps = params.withIndex().joinToString(", ") { (i, p) ->
                val opt = if (p.optional) "?" else ""
                val enum = p.enumValues?.let { " ∈ {${it.joinToString("|")}}" } ?: ""
                "arg$i: ${p.luaType}$opt$enum"
            }
            val ret = when (returnShape) {
                ReturnShape.NONE -> "nil"
                ReturnShape.ONE -> "value"
                ReturnShape.MANY -> "value, ..."
                ReturnShape.DYNAMIC -> "dynamic"
            }
            val flags = buildList {
                if (mainThread) add("mainThread")
                if (unsafe) add("unsafe")
            }.joinToString(",").let { if (it.isEmpty()) "" else " [$it]" }
            return "$name($ps) -> $ret$flags"
        }
    }

    // ---------------- internals ----------------

    class MethodTable internal constructor(
        internal val staticMethods: Map<String, Invoker>,
        internal val signatures: Map<String, MethodSignature>,
        internal val dynamicMethodNames: List<String>,
    ) {
        fun has(name: String): Boolean =
            staticMethods.containsKey(name) || dynamicMethodNames.contains(name)
    }

    internal fun interface Invoker {
        @Throws(LuaException::class)
        fun invoke(
            target: Any,
            computer: IComputerAccess,
            context: ILuaContext,
            args: IArguments,
        ): MethodResult
    }

    private fun buildStatic(cls: Class<*>): MethodTable {
        val methods = mutableMapOf<String, Invoker>()
        val sigs = mutableMapOf<String, MethodSignature>()
        // Class.getMethods walks the whole hierarchy, which is what we
        // want: MonitorPeripheral extends TermMethods and most of the
        // interesting methods live on the parent.
        for (m in cls.methods) {
            val ann = m.getAnnotation(LuaFunction::class.java) ?: continue
            val built = buildInvoker(m, ann) ?: continue
            val (invoker, sigTemplate) = built
            val aliases = ann.value.filter { it.isNotEmpty() }
            val names = aliases.ifEmpty { listOf(m.name) }
            for (n in names) {
                // First registration wins: handles an override where both
                // the parent and child expose the same name (rare, but
                // getMethods returns both).
                if (methods.putIfAbsent(n, invoker) == null) {
                    // Each alias gets a signature keyed by the alias
                    // name, but the signature carries the full alias
                    // list so `describe` can show them all.
                    sigs[n] = sigTemplate.copy(name = n, aliases = aliases)
                }
            }
        }
        return MethodTable(methods.toMap(), sigs.toMap(), emptyList())
    }

    /**
     * Build an [Invoker] + [MethodSignature] template for a concrete
     * `@LuaFunction` method. The parameter-coercion strategy is
     * determined once here (per [Method] reflection) and captured in
     * the returned lambda; the dispatch path then just loops the
     * captured converters.
     *
     * Mirrors CC's own Generator convention: injected params
     * ([IComputerAccess], [ILuaContext]) and [IArguments] pass-through
     * don't consume a position in the Lua `IArguments` index space.
     * Their index is precomputed here (`luaIdx` per converter) so
     * dispatch stays index-free at invocation time.
     *
     * Returns null if any parameter or return type is unconvertible.
     * The previous codepath built a converter that threw at dispatch
     * time — we keep that behavior for *runtime* unknowns (e.g.
     * `Coerced<T>` for unsupported `T`) and only return null for
     * structurally-bad methods we shouldn't even list.
     */
    private fun buildInvoker(method: Method, ann: LuaFunction): Pair<Invoker, MethodSignature>? {
        method.isAccessible = true
        val paramTypes = method.parameterTypes
        val genericParamTypes = method.genericParameterTypes
        var luaIdx = 0
        val converters = Array(paramTypes.size) { i ->
            val raw = paramTypes[i]
            val isInjected = raw == IComputerAccess::class.java ||
                raw == ILuaContext::class.java ||
                raw == IArguments::class.java
            val idx = if (isInjected) -1 else luaIdx++
            paramConverter(raw, genericParamTypes[i], idx)
        }
        val returnConverter = returnConverter(method.returnType)
        val signatureTemplate = MethodSignature(
            name = method.name,
            aliases = emptyList(), // filled by buildStatic per-name
            params = describeParams(paramTypes, genericParamTypes),
            returnShape = describeReturnShape(method.returnType),
            mainThread = ann.mainThread,
            unsafe = ann.unsafe,
            declaredBy = method.declaringClass.simpleName,
        )
        val invoker = Invoker { target, computer, context, args ->
            // Convert args in a separate try so argument-coercion
            // errors (e.g. IArguments.getInt throwing because the
            // caller passed a string where a number is required) also
            // pick up the enriched-with-signature treatment.
            val callArgs = try {
                Array<Any?>(converters.size) { i ->
                    converters[i].convert(computer, context, args)
                }
            } catch (e: LuaException) {
                throw enrichLuaException(e, signatureTemplate)
            }
            val ret = try {
                method.invoke(target, *callArgs)
            } catch (ite: InvocationTargetException) {
                when (val cause = ite.targetException) {
                    is LuaException -> throw enrichLuaException(cause, signatureTemplate)
                    is RuntimeException -> throw LuaException(
                        "${method.name}: ${cause.message} -- ${signatureTemplate.signatureString()}",
                    )
                    else -> throw LuaException(
                        "${method.name} failed: ${cause?.message ?: cause} -- ${signatureTemplate.signatureString()}",
                    )
                }
            } catch (e: IllegalAccessException) {
                throw LuaException("cannot access ${method.name}: ${e.message}")
            }
            returnConverter.convert(ret)
        }
        return invoker to signatureTemplate
    }

    /**
     * Append the synthesized signature to a LuaException produced by
     * either argument coercion (from [paramConverter]'s typed getters)
     * or the method body itself. The original message is preserved
     * first, the signature is separated by ` -- ` so guest parsers can
     * strip the trailing hint if they want.
     *
     * Skip enrichment if the message already contains the signature —
     * prevents double-tagging when a deeper dispatcher already ran
     * through here (e.g. modem callRemote routing).
     */
    private fun enrichLuaException(e: LuaException, sig: MethodSignature): LuaException {
        val original = e.message ?: return e
        val sigStr = sig.signatureString()
        if (original.contains(sigStr)) return e
        return LuaException("$original -- $sigStr")
    }

    private fun interface ParamConverter {
        @Throws(LuaException::class)
        fun convert(computer: IComputerAccess, context: ILuaContext, args: IArguments): Any?
    }

    private fun interface ReturnConverter {
        fun convert(ret: Any?): MethodResult
    }

    private fun paramConverter(
        raw: Class<*>,
        generic: java.lang.reflect.Type,
        luaIdx: Int,
    ): ParamConverter {
        // Injected CC machinery — never consumes a Lua arg index
        // (luaIdx == -1 in the caller's bookkeeping).
        if (raw == IComputerAccess::class.java) return ParamConverter { c, _, _ -> c }
        if (raw == ILuaContext::class.java) return ParamConverter { _, ctx, _ -> ctx }
        if (raw == IArguments::class.java) return ParamConverter { _, _, a -> a }

        // Primitives. IArguments lazily coerces Lua numbers / strings;
        // we mirror its public accessor choice.
        if (raw == Int::class.javaPrimitiveType || raw == Integer::class.java) {
            return ParamConverter { _, _, a -> a.getInt(luaIdx) }
        }
        if (raw == Long::class.javaPrimitiveType || raw == java.lang.Long::class.java) {
            return ParamConverter { _, _, a -> a.getLong(luaIdx) }
        }
        if (raw == Double::class.javaPrimitiveType || raw == java.lang.Double::class.java) {
            return ParamConverter { _, _, a -> a.getDouble(luaIdx) }
        }
        if (raw == Float::class.javaPrimitiveType || raw == java.lang.Float::class.java) {
            return ParamConverter { _, _, a -> a.getDouble(luaIdx).toFloat() }
        }
        if (raw == Boolean::class.javaPrimitiveType || raw == java.lang.Boolean::class.java) {
            return ParamConverter { _, _, a -> a.getBoolean(luaIdx) }
        }
        if (raw == String::class.java) {
            return ParamConverter { _, _, a -> a.getString(luaIdx) }
        }
        if (raw == ByteBuffer::class.java) {
            return ParamConverter { _, _, a -> a.getBytes(luaIdx) }
        }
        if (raw == ByteArray::class.java) {
            return ParamConverter { _, _, a ->
                val bb = a.getBytes(luaIdx)
                val copy = ByteArray(bb.remaining())
                bb.duplicate().get(copy)
                copy
            }
        }
        if (raw == Map::class.java || raw == java.util.Map::class.java) {
            return ParamConverter { _, _, a -> a.getTable(luaIdx) }
        }

        // Enum parameters. CC's own dispatcher handles this via
        // getEnum/optEnum; matching here lets us also surface the
        // valid values in MethodSignature.
        if (raw.isEnum) {
            @Suppress("UNCHECKED_CAST")
            val enumClass = raw as Class<out Enum<*>>
            return ParamConverter { _, _, a -> a.getEnum(luaIdx, enumClass) }
        }

        // Generic wrappers: Optional<T>, Coerced<String>.
        if (raw == Optional::class.java) {
            val inner = (generic as? ParameterizedType)?.actualTypeArguments?.getOrNull(0) as? Class<*>
                ?: return ParamConverter { _, _, a -> Optional.ofNullable(a.get(luaIdx)) }
            // Enum<T> inside an Optional — CC exposes optEnum for
            // exactly this shape.
            if (inner != Enum::class.java && Enum::class.java.isAssignableFrom(inner)) {
                @Suppress("UNCHECKED_CAST")
                val enumClass = inner as Class<out Enum<*>>
                return ParamConverter { _, _, a -> a.optEnum(luaIdx, enumClass) }
            }
            return when (inner) {
                String::class.java -> ParamConverter { _, _, a -> a.optString(luaIdx) }
                Integer::class.java -> ParamConverter { _, _, a -> a.optInt(luaIdx) }
                java.lang.Long::class.java -> ParamConverter { _, _, a -> a.optLong(luaIdx) }
                java.lang.Double::class.java -> ParamConverter { _, _, a -> a.optDouble(luaIdx) }
                java.lang.Boolean::class.java -> ParamConverter { _, _, a -> a.optBoolean(luaIdx) }
                ByteBuffer::class.java -> ParamConverter { _, _, a -> a.optBytes(luaIdx) }
                else -> ParamConverter { _, _, a -> Optional.ofNullable(a.get(luaIdx)) }
            }
        }
        if (raw == Coerced::class.java) {
            val inner = (generic as? ParameterizedType)?.actualTypeArguments?.getOrNull(0) as? Class<*>
            return when (inner) {
                String::class.java -> ParamConverter { _, _, a -> Coerced(a.getStringCoerced(luaIdx)) }
                // Other Coerced<T> specializations aren't used by any
                // first-party CC peripheral (only Coerced<String> is).
                // Error loudly so the failing method name propagates.
                else -> ParamConverter { _, _, _ ->
                    throw LuaException("Coerced<${inner?.simpleName}> not supported by scev dispatcher")
                }
            }
        }

        // Fallback: raw Object. IArguments.get returns the underlying
        // Lua-mapped Java value (Number, String, Map, Boolean, byte[]).
        if (raw == Any::class.java) return ParamConverter { _, _, a -> a.get(luaIdx) }

        // Anything else is a type scev can't convert. Throw at dispatch
        // time with a descriptive message.
        return ParamConverter { _, _, _ ->
            throw LuaException("scev dispatcher cannot convert parameter of type ${raw.name}")
        }
    }

    private fun returnConverter(retType: Class<*>): ReturnConverter {
        if (retType == Void.TYPE) return ReturnConverter { MethodResult.of() }
        if (retType == MethodResult::class.java) return ReturnConverter { it as MethodResult }
        if (retType == Array<Any>::class.java || retType.isArray && !retType.componentType.isPrimitive) {
            return ReturnConverter { ret ->
                @Suppress("UNCHECKED_CAST")
                val arr = ret as? Array<Any?> ?: return@ReturnConverter MethodResult.of()
                MethodResult.of(*arr)
            }
        }
        // Primitive and Object returns: wrap in a single-value result.
        return ReturnConverter { MethodResult.of(it) }
    }

    /**
     * Stringify each parameter's Lua-side type, lifting out of
     * [Optional] / [Coerced] / [Enum] wrappers so the returned list
     * mirrors what a caller would actually pass in. Injected machinery
     * ([IComputerAccess], [ILuaContext], [IArguments]) is dropped —
     * it's not a caller-visible arg.
     */
    private fun describeParams(
        paramTypes: Array<Class<*>>,
        genericTypes: Array<java.lang.reflect.Type>,
    ): List<ParamSpec> {
        val out = ArrayList<ParamSpec>(paramTypes.size)
        for (i in paramTypes.indices) {
            val raw = paramTypes[i]
            if (raw == IComputerAccess::class.java ||
                raw == ILuaContext::class.java ||
                raw == IArguments::class.java
            ) continue
            out += describeOneParam(raw, genericTypes[i])
        }
        return out
    }

    private fun describeOneParam(raw: Class<*>, generic: java.lang.reflect.Type): ParamSpec {
        if (raw == Optional::class.java) {
            val inner = (generic as? ParameterizedType)?.actualTypeArguments?.getOrNull(0) as? Class<*>
            return if (inner != null) {
                val innerSpec = describeOneParam(inner, inner)
                innerSpec.copy(optional = true)
            } else {
                ParamSpec("any", optional = true)
            }
        }
        if (raw == Coerced::class.java) {
            val inner = (generic as? ParameterizedType)?.actualTypeArguments?.getOrNull(0) as? Class<*>
            return when (inner) {
                // getStringCoerced tolerates numbers/booleans/nil and
                // stringifies them — signal the wider acceptance.
                String::class.java -> ParamSpec("string|number|boolean|nil")
                else -> ParamSpec("any")
            }
        }
        if (raw.isEnum && raw != Enum::class.java) {
            @Suppress("UNCHECKED_CAST")
            val values = (raw as Class<out Enum<*>>).enumConstants.map { it.name.lowercase() }
            return ParamSpec("string", optional = false, enumValues = values)
        }
        return ParamSpec(luaTypeName(raw))
    }

    /** Lua-side type label matching CC's own argument-error wording. */
    private fun luaTypeName(raw: Class<*>): String = when (raw) {
        Int::class.javaPrimitiveType, Integer::class.java,
        Long::class.javaPrimitiveType, java.lang.Long::class.java,
        Double::class.javaPrimitiveType, java.lang.Double::class.java,
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> "number"
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> "boolean"
        String::class.java -> "string"
        ByteBuffer::class.java, ByteArray::class.java -> "string|bytes"
        Map::class.java, java.util.Map::class.java -> "table"
        Any::class.java -> "any"
        else -> raw.simpleName
    }

    private fun describeReturnShape(retType: Class<*>): ReturnShape {
        if (retType == Void.TYPE) return ReturnShape.NONE
        if (retType == MethodResult::class.java) return ReturnShape.DYNAMIC
        if (retType == Array<Any>::class.java || retType.isArray && !retType.componentType.isPrimitive) {
            return ReturnShape.MANY
        }
        return ReturnShape.ONE
    }
}
