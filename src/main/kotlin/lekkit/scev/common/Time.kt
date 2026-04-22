/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.common

/**
 * Type-safe wrappers around `Long` time values so you can't accidentally
 * add nanoseconds to microseconds, pass wall-clock millis where a PTS
 * in micros is expected, or confuse timestamps with durations.
 *
 * All three are [JvmInline] value classes: at the JVM level each is a
 * bare `long`, no boxing, no wrapper object allocation. The compiler
 * erases them to primitives in any method whose signature uses them by
 * value — equality, comparison, and arithmetic happen on the underlying
 * `Long`. Using them at API boundaries is free at runtime but rejects
 * unit-mixing at compile time.
 *
 * **Scope.** These types cover *any* place the codebase handles a
 * monotonic clock reading, a wall-clock timestamp, a duration in known
 * units, or a wire-format PTS. Adopt on new code; retrofit existing
 * `long`-based times as the surrounding code is touched for other
 * reasons — mechanical rewrites for their own sake produce merge
 * friction without uncovering real bugs.
 *
 * **Why three types, not one.**
 *
 * - [Nanos]: monotonic, anchored to `System.nanoTime()`. Never becomes
 *   a wire field. Used for elapsed measurements and as the underlying
 *   clock source for [MachineClock]-style origins. Converting between
 *   [Nanos] and anything with a fixed clock epoch is a type error
 *   unless you've subtracted an origin first.
 * - [Micros]: the A/V sync PTS currency. Fine-grained enough for
 *   48 kHz sample-exact audio timing (a sample is ~20.8 µs); coarse
 *   enough that a `Long` holds ~292,000 years of range.
 * - [Millis]: wall-clock oriented, matching `System.currentTimeMillis()`.
 *   The common choice for human-facing durations (UI fades, timeouts,
 *   idle windows). Not used for A/V PTS — too coarse to express one
 *   audio frame boundary precisely.
 *
 * **Comparable.** All three implement `Comparable<T>` so they compose
 * cleanly with sorted collections (e.g. `TreeMap<Micros, VideoFrame>`
 * for the client-side video jitter buffer).
 */

/** Nanoseconds, intended for `System.nanoTime()`-derived monotonic values. */
@JvmInline
value class Nanos(val value: Long) : Comparable<Nanos> {
    operator fun plus(other: Nanos) = Nanos(value + other.value)
    operator fun minus(other: Nanos) = Nanos(value - other.value)
    operator fun unaryMinus() = Nanos(-value)
    override operator fun compareTo(other: Nanos): Int = value.compareTo(other.value)

    fun toMicros() = Micros(value / 1_000L)
    fun toMillis() = Millis(value / 1_000_000L)

    companion object {
        val ZERO = Nanos(0L)
        /** Monotonic clock reading — NOT a wall-clock timestamp. */
        @JvmStatic fun now(): Nanos = Nanos(System.nanoTime())
    }
}

/** Microseconds, the A/V sync PTS unit. */
@JvmInline
value class Micros(val value: Long) : Comparable<Micros> {
    operator fun plus(other: Micros) = Micros(value + other.value)
    operator fun minus(other: Micros) = Micros(value - other.value)
    operator fun unaryMinus() = Micros(-value)
    override operator fun compareTo(other: Micros): Int = value.compareTo(other.value)

    fun toNanos() = Nanos(value * 1_000L)
    fun toMillis() = Millis(value / 1_000L)

    companion object {
        val ZERO = Micros(0L)
    }
}

/** Milliseconds, intended for wall-clock and human-facing durations. */
@JvmInline
value class Millis(val value: Long) : Comparable<Millis> {
    operator fun plus(other: Millis) = Millis(value + other.value)
    operator fun minus(other: Millis) = Millis(value - other.value)
    operator fun unaryMinus() = Millis(-value)
    override operator fun compareTo(other: Millis): Int = value.compareTo(other.value)

    fun toMicros() = Micros(value * 1_000L)
    fun toNanos() = Nanos(value * 1_000_000L)

    companion object {
        val ZERO = Millis(0L)
        /** Wall-clock timestamp reading. */
        @JvmStatic fun wall(): Millis = Millis(System.currentTimeMillis())
    }
}

/**
 * Scalar multiplication helpers. Useful for `N * Millis(20)` ergonomics
 * without forcing callers to construct a new Millis from
 * `Millis(n * 20)`.
 */
operator fun Int.times(m: Millis) = Millis(this * m.value)
operator fun Long.times(m: Millis) = Millis(this * m.value)
operator fun Int.times(m: Micros) = Micros(this * m.value)
operator fun Long.times(m: Micros) = Micros(this * m.value)
operator fun Int.times(n: Nanos) = Nanos(this * n.value)
operator fun Long.times(n: Nanos) = Nanos(this * n.value)
