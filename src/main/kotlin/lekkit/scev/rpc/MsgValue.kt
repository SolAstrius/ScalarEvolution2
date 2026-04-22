/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

/**
 * Tagged-union MessagePack value. Only the types the RPC actually uses:
 * nil, boolean, int (long-range), double, string, bytes, array, map.
 *
 * Kotlin consumers pattern-match via `when (v) { is MsgValue.Str -> … }`
 * — the sealed hierarchy gives exhaustiveness at compile time, so a
 * missing branch is caught by the compiler rather than a runtime cast.
 *
 * Java consumers continue to use the `MsgValue.of(...)` / `MsgValue.NIL`
 * factories and the `kind` / `asX()` extractors; the legacy API is
 * preserved unchanged apart from `.kind` → `.getKind()` (Kotlin-generated
 * getter for abstract properties — Java field-access syntax doesn't
 * work on an abstract `val`).
 *
 * Kept deliberately small — a full msgpack-core lib would be overkill
 * for the handful of shapes on the wire.
 */
sealed class MsgValue {

    enum class Kind { NIL, BOOL, INT, DOUBLE, STRING, BYTES, ARRAY, MAP }

    /** The wire category this value belongs to. Drives Java-side switches. */
    abstract val kind: Kind

    /* ---------------- Variants ---------------- */

    /** The single nil value. Compare by identity or via [isNil]. */
    object Nil : MsgValue() {
        override val kind get() = Kind.NIL
        override fun toString() = "nil"
    }

    /** Boxed boolean. Prefer [MsgValue.TRUE] / [MsgValue.FALSE] over `Bool(true)`. */
    data class Bool(val value: Boolean) : MsgValue() {
        override val kind get() = Kind.BOOL
        override fun toString() = value.toString()
    }

    /** 64-bit signed integer (msgpack's `int` family). */
    data class Int(val value: Long) : MsgValue() {
        override val kind get() = Kind.INT
        override fun toString() = value.toString()
    }

    /** IEEE-754 double (msgpack's `float64`). */
    data class Double(val value: kotlin.Double) : MsgValue() {
        override val kind get() = Kind.DOUBLE
        override fun toString() = value.toString()
    }

    /** UTF-8 string. */
    data class Str(val value: String) : MsgValue() {
        override val kind get() = Kind.STRING
        override fun toString() = "\"$value\""
    }

    /**
     * Raw bytes. Equality is reference-based (matches the original Java
     * record semantics — msgpack maps with byte-array keys are the only
     * case that cares, and we don't emit those).
     */
    class Bin(val value: ByteArray) : MsgValue() {
        override val kind get() = Kind.BYTES
        override fun equals(other: Any?) = other is Bin && this.value === other.value
        override fun hashCode() = System.identityHashCode(value)
        override fun toString() = "bytes[${value.size}]"
    }

    /** Heterogeneous array. */
    data class Arr(val value: List<MsgValue>) : MsgValue() {
        override val kind get() = Kind.ARRAY
        override fun toString() = value.toString()
    }

    /** Map. Keys are any [MsgValue]; in practice always [Str]. */
    data class Map(val value: kotlin.collections.Map<MsgValue, MsgValue>) : MsgValue() {
        override val kind get() = Kind.MAP
        override fun toString() = value.toString()
    }

    /* ---------------- Legacy Java-style predicates ---------------- */

    /**
     * `is*` properties are exposed as boolean-named JVM methods (`isNil()`,
     * `isString()`, …) per Kotlin's standard property-naming rules. Java
     * callers keep their existing call sites; Kotlin callers should
     * generally prefer `when (v) { is MsgValue.Str -> … }` for smart-cast.
     */
    val isNil    get() = this is Nil
    val isBool   get() = this is Bool
    val isInt    get() = this is Int
    val isNumber get() = this is Int || this is Double
    val isString get() = this is Str
    val isBytes  get() = this is Bin
    val isArray  get() = this is Arr
    val isMap    get() = this is Map

    /* ---------------- Legacy Java-style extractors ---------------- */

    fun asBool(): Boolean = (this as Bool).value
    fun asInt(): Long = (this as Int).value
    fun asDouble(): kotlin.Double = when (this) {
        is Double -> value
        is Int -> value.toDouble()
        else -> throw IllegalStateException("not a number: $this")
    }
    fun asString(): String = (this as Str).value
    fun asBytes(): ByteArray = (this as Bin).value
    fun asArray(): List<MsgValue> = (this as Arr).value
    fun asMap(): kotlin.collections.Map<MsgValue, MsgValue> = (this as Map).value

    /** Raw underlying object (for debugging / tests). */
    fun raw(): Any? = when (this) {
        is Nil -> null
        is Bool -> value
        is Int -> value
        is Double -> value
        is Str -> value
        is Bin -> value
        is Arr -> value
        is Map -> value
    }

    companion object {
        @JvmField val NIL: MsgValue = Nil
        @JvmField val TRUE: MsgValue = Bool(true)
        @JvmField val FALSE: MsgValue = Bool(false)

        @JvmStatic fun of(b: Boolean): MsgValue = if (b) TRUE else FALSE
        @JvmStatic fun of(n: Long): MsgValue = Int(n)
        @JvmStatic fun of(d: kotlin.Double): MsgValue = Double(d)
        @JvmStatic fun of(s: String?): MsgValue = if (s == null) NIL else Str(s)
        @JvmStatic fun of(b: ByteArray?): MsgValue = if (b == null) NIL else Bin(b)

        @JvmStatic fun ofArray(xs: List<MsgValue>?): MsgValue =
            if (xs == null) NIL else Arr(xs)

        @JvmStatic fun ofMap(m: kotlin.collections.Map<MsgValue, MsgValue>?): MsgValue =
            if (m == null) NIL else Map(m)
    }
}
