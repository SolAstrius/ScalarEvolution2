/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc

import lekkit.scev.core.rpc.MsgValue

/**
 * Lossless-enough conversion between [MsgValue]s (RPC wire) and plain
 * Java/Kotlin objects CC accepts as event arguments.
 *
 * CC's Lua VM accepts these JVM types as event args: `Boolean`,
 * `String`, `Number` (any JDK numeric), `byte[]`,
 * `Map<String, Object>` (becomes a Lua table), `Object[]` /
 * `Collection` (becomes a numerically-keyed table), `null`. Anything
 * else gets stringified or dropped depending on where it appears.
 *
 * Going RPC → Lua:
 * - NIL → `null`
 * - BOOL → Boolean
 * - INT → Long (Lua numbers are doubles, but CC preserves integer
 *   representation when the value fits).
 * - DOUBLE → Double
 * - STRING → String
 * - BYTES → byte[]
 * - ARRAY → Object[]
 * - MAP → Map with String keys (non-string keys are toString'd — Lua
 *   tables allow non-string keys but CC's queueEvent converter
 *   flattens them anyway)
 *
 * Going Lua → RPC is only exercised by the `@LuaFunction` methods on
 * `ScevCCPeripheral`, so it's intentionally narrower — the method
 * signatures do their own coercion.
 */
internal object LuaValueConverter {

    @JvmStatic
    fun toLua(v: MsgValue): Any? = when (v.kind) {
        MsgValue.Kind.NIL -> null
        MsgValue.Kind.BOOL -> v.asBool()
        MsgValue.Kind.INT -> v.asInt()
        MsgValue.Kind.DOUBLE -> v.raw() as Double
        MsgValue.Kind.STRING -> v.asString()
        MsgValue.Kind.BYTES -> v.asBytes()
        MsgValue.Kind.ARRAY -> {
            val xs = v.asArray()
            Array<Any?>(xs.size) { i -> toLua(xs[i]) }
        }
        MsgValue.Kind.MAP -> {
            val src = v.asMap()
            val out = LinkedHashMap<String, Any?>(src.size * 2)
            for ((k, vv) in src) {
                val key = if (k.isString) k.asString() else k.toString()
                out[key] = toLua(vv)
            }
            out
        }
    }

    /** Batch: convert a list of RPC args to the Object[] CC queueEvent wants. */
    @JvmStatic
    fun toLuaArgs(args: List<MsgValue>): Array<Any?> =
        Array(args.size) { i -> toLua(args[i]) }

    /**
     * Coerce arbitrary JVM values back into [MsgValue]s — used when
     * surfacing the result of a reflective `@LuaFunction` invocation.
     * Unknown types are wrapped as their toString().
     */
    @JvmStatic
    fun toMsg(o: Any?): MsgValue = when (o) {
        null -> MsgValue.NIL
        is Boolean -> MsgValue.of(o)
        is Byte, is Short, is Int, is Long -> MsgValue.of((o as Number).toLong())
        is Number -> MsgValue.of(o.toDouble())
        is String -> MsgValue.of(o)
        is ByteArray -> MsgValue.of(o)
        is IntArray -> MsgValue.ofArray(o.map { MsgValue.of(it.toLong()) })
        is Array<*> -> MsgValue.ofArray(o.map { toMsg(it) })
        is Iterable<*> -> MsgValue.ofArray(o.map { toMsg(it) })
        is Map<*, *> -> {
            val out = LinkedHashMap<MsgValue, MsgValue>()
            for ((k, vv) in o) out[toMsg(k)] = toMsg(vv)
            MsgValue.ofMap(out)
        }
        else -> MsgValue.of(o.toString())
    }
}
