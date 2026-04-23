/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component.api

/**
 * Marks a class as a scev component — a thing that shows up at
 * `/sys/scev/by-label/<name>/` (or equivalent) when it's attached to
 * a scev computer.
 *
 * Apply to a class whose methods / fields carry further annotations
 * ([Plugin], [Property], [Action]). The component's lifecycle and
 * threading model are inherited from the scev runtime: property reads
 * are served off-thread from a tick-refreshed snapshot; action
 * invocations and writes run on the server tick.
 *
 * Compiles to a runtime-retained JVM annotation identical to the Java
 * form, so Java and Scala authors can use `@ScevComponent(name = ...)`
 * on their own classes.
 *
 * This is the dead form for now: scev's runtime does not yet scan for
 * it on capability attach. Mod authors adding it today will have
 * working components when the runtime lands.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ScevComponent(
    /**
     * User-visible name. Shows up as the directory under
     * `/sys/scev/by-label/`. Must match `[a-z0-9._-]{1,64}` — the
     * scanner validates on first use.
     */
    val name: String,

    /**
     * Component role. One of `"property_bag"`, `"rpc"`,
     * `"char_stream"`, `"mount"` (case-insensitive). Default covers
     * the common case.
     */
    val role: String = "property_bag",

    /**
     * Optional human-readable summary. Lands in `.meta/describe`'s
     * top-level `doc` field.
     */
    val doc: String = "",
)

/**
 * Declares a plugin subtree on a [ScevComponent]. Apply to a method
 * (returning a plugin instance) or a field (holding one). The
 * plugin's own [Property] / [Action] members populate a subdirectory
 * under the component root named [value].
 *
 * When the plugin instance implements [lekkit.scev.component.ScevPlugin],
 * its `isSuitable()` gate decides whether the subtree appears at
 * attach time. A `@Suitable`-annotated method on the plugin class
 * works as an alternative to the interface.
 *
 * The [capability] string should be the slug of the block capability
 * this plugin represents (e.g. a NeoForge `BlockCapability`'s
 * `name()`). Scev uses it for the
 * `/sys/scev/by-capability/<cap>/` symlink view. Unknown / custom
 * capability names are fine.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Plugin(
    /**
     * Slug for this plugin's subdirectory. Must match
     * `[a-z0-9._-]{1,32}`.
     */
    val value: String,

    /**
     * Capability identifier, or empty for "no associated capability."
     * The `/sys/scev/by-capability/<cap>/` index is built from this.
     */
    val capability: String = "",

    /**
     * Human-readable summary for `.meta/describe`.
     */
    val doc: String = "",
)

/**
 * Declares a property file. Apply to a method on a [ScevComponent]-
 * or [Plugin]-annotated class; the method is treated as a getter.
 *
 * Pairing with a method named `set<Name>` on the same class that
 * takes one matching-type argument auto-creates an rw file; the
 * scanner walks the class once and fuses pairs. Set [writable] true
 * to force the file writable even without a paired setter (the
 * author wires the actual mutation via the DSL).
 *
 * The scanner derives the file name by stripping `get`/`is`/`has`
 * prefix from the method name and snake-casing the remainder:
 * `powerInConduits` → `power_in_conduits`. Override with [value].
 *
 * Reads are served off the server thread from a tick-refreshed
 * snapshot. Writes are queued to the server tick by default.
 *
 * Units are free-form strings. Documented conventions: `"FE"`,
 * `"FE/t"`, `"mB"`, `"K"`, `"Pa"`, `"fraction"`. Emitted as the
 * `<path>_unit` sibling file when non-empty.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Property(
    /**
     * Explicit path for this property, relative to the component root
     * or plugin subdirectory. Empty = derive from method name.
     */
    val value: String = "",

    /**
     * Free-form unit tag. Emitted as `<path>_unit` when non-empty.
     */
    val unit: String = "",

    /**
     * Human-readable description. Emitted as `<path>_label`.
     */
    val doc: String = "",

    /**
     * Minimum numeric value accepted for writes, if known. `NaN` =
     * unspecified. Ignored for non-numeric types.
     */
    val min: Double = Double.NaN,

    /**
     * Maximum numeric value accepted for writes, if known. `NaN` =
     * unspecified.
     */
    val max: Double = Double.NaN,

    /**
     * Force the property writable even when no paired setter is
     * visible. Rarely needed — prefer pairing with a matching
     * setter method.
     */
    val writable: Boolean = false,
)

/**
 * Declares a multi-argument action.
 *
 * Actions surface as a write-only file at `<path>` that accepts JSON
 * or whitespace-separated arguments, paired with a read-only result
 * file (default `<path>_result`) carrying the most recent return
 * value.
 *
 * Dispatch defaults to on-tick (equivalent to CC's
 * `@LuaFunction(mainThread = true)`). Set [offThread] true only for
 * actions that genuinely don't touch world state — scev's dispatcher
 * enforces this.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Action(
    /** Explicit path. Empty = derived from method name. */
    val value: String = "",

    /** Path for the result file. Empty = `<value>_result`. */
    val resultPath: String = "",

    /** Human-readable description. */
    val doc: String = "",

    /**
     * Allow dispatch off the server tick. Default false (on-tick) to
     * match the CC `mainThread = true` expectation of mod authors.
     */
    val offThread: Boolean = false,
)

/**
 * Marks a zero-argument boolean-returning method as the gating
 * predicate for its enclosing plugin. An alternative to implementing
 * [lekkit.scev.component.ScevPlugin.isSuitable] when you prefer
 * annotations over an interface.
 *
 * Exactly one `@Suitable` method per class. When both are present
 * (annotation and interface method), the annotation wins.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Suitable
