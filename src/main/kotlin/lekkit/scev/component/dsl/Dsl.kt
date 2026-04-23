/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component.dsl

import lekkit.scev.component.ComponentRole
import lekkit.scev.component.describe.ActionDescriptor
import lekkit.scev.component.describe.ComponentDescriptor
import lekkit.scev.component.describe.EventDescriptor
import lekkit.scev.component.describe.ParamSpec
import lekkit.scev.component.describe.PluginDescriptor
import lekkit.scev.component.describe.PropertyDescriptor
import lekkit.scev.component.describe.ReturnShape

/**
 * Kotlin DSL entry for components.
 *
 * A full alternative to the annotation-based surface: produces the
 * same [ComponentDescriptor] tree the scanner does, so the eventual
 * runtime can't tell them apart. Useful for:
 *
 * - Authors who don't want to scatter annotations across a class.
 * - Cases where the plugin structure depends on runtime state (where
 *   annotations would force a class-per-configuration).
 * - Composing components from small building blocks — each
 *   `fromEnergyCapability(...)` / `fromItemHandler(...)` helper is a
 *   one-line contribution to the builder.
 *
 * Typical use:
 * ```
 * val desc = scevComponent("mekanism_reactor") {
 *     plugin("energy", capability = "neoforge:energy_storage") {
 *         readOnly("stored", unit = "FE") { be.energy.stored }
 *         readOnly("capacity", unit = "FE") { be.energy.capacity }
 *         readWrite("limit", min = 0.0, max = 1.0,
 *             getter = { be.energy.limit.toDouble() },
 *             setter = { v -> be.energy.limit = v.toFloat() })
 *     }
 *     plugin("items", capability = "neoforge:item_handler") {
 *         action("move") { from: Int, to: Int, count: Int ->
 *             be.inventory.move(from, to, count)
 *         }
 *     }
 * }
 * ```
 *
 * For "the dead API", the `getter` / `setter` lambdas are held in the
 * builder but the final [ComponentDescriptor] only carries metadata —
 * the actual dispatchers aren't wired yet. When the runtime lands it
 * will pick up the accumulated lambdas from a parallel registration
 * table the builder writes to.
 *
 * Thread-safety: a builder is a one-shot object. Don't share across
 * threads; if you need to build in parallel, build separate instances
 * and combine the results.
 */
@DslMarker
annotation class ScevDsl

/**
 * Build a component descriptor using the DSL.
 *
 * Use at class construction or init time, typically once per
 * peripheral. The returned [BuiltComponent] holds both the
 * [ComponentDescriptor] and the lambda table — the latter is what
 * the runtime will later read from when wiring actual dispatch.
 */
fun scevComponent(
    name: String,
    role: ComponentRole = ComponentRole.PROPERTY_BAG,
    doc: String? = null,
    block: ComponentBuilder.() -> Unit,
): BuiltComponent {
    return ComponentBuilder(name, role, doc).apply(block).build()
}

/**
 * The DSL output. Descriptor is the public, serialisable shape that
 * matches the scanner's output. [handlers] is internal plumbing for
 * the runtime.
 */
data class BuiltComponent(
    val descriptor: ComponentDescriptor,
    val handlers: ComponentHandlers,
)

/**
 * Behaviour table accumulated by the DSL. Parallel-structured to
 * [ComponentDescriptor]: for every path the descriptor declares, this
 * map holds the callable that reads/writes/invokes it.
 *
 * Opaque to mod authors — produced by the builder, consumed by the
 * runtime. For the dead API, nobody consumes it yet; tests can walk
 * it to assert wiring matches the descriptor.
 */
class ComponentHandlers internal constructor(
    /** Full-path (plugin/path or just path) → read handler. */
    val readers: Map<String, () -> Any?>,
    /** Full-path → write handler. Null = not writable. */
    val writers: Map<String, (Any?) -> Unit>,
    /** Full-path → action handler. Arguments passed as `Array<Any?>`. */
    val actions: Map<String, (Array<Any?>) -> Any?>,
    /** Slug → suitability predicate for the plugin's gating. */
    val pluginGates: Map<String, () -> Boolean>,
)

// ============================================================
//  Builders
// ============================================================

@ScevDsl
class ComponentBuilder internal constructor(
    private val name: String,
    private val role: ComponentRole,
    private val doc: String?,
) {
    private val rootProperties = mutableListOf<PropertyDescriptor>()
    private val rootActions = mutableListOf<ActionDescriptor>()
    private val rootEvents = mutableListOf<EventDescriptor>()
    private val plugins = mutableListOf<PluginBuilder>()

    // Handler accumulators — flat maps keyed by full path.
    internal val readers = mutableMapOf<String, () -> Any?>()
    internal val writers = mutableMapOf<String, (Any?) -> Unit>()
    internal val actionHandlers = mutableMapOf<String, (Array<Any?>) -> Any?>()
    internal val pluginGates = mutableMapOf<String, () -> Boolean>()

    private val declaredBy: String = "dsl:$name"

    /**
     * Add a plugin subtree. [slug] names the subdirectory,
     * [capability] optionally identifies the capability for the
     * by-capability/ view, [suitable] gates whether the subtree is
     * visible at attach time.
     */
    fun plugin(
        slug: String,
        capability: String? = null,
        doc: String? = null,
        suitable: () -> Boolean = { true },
        block: PluginBuilder.() -> Unit,
    ): PluginBuilder {
        val pb = PluginBuilder(slug, capability, doc, suitable, declaredBy).apply(block)
        plugins.add(pb)
        pluginGates[slug] = suitable
        return pb
    }

    /**
     * Declare a read-only property at the component root (not in any
     * plugin). Preferred when the component has only one or two
     * loose properties; for anything bigger, group into a plugin.
     */
    fun readOnly(
        path: String,
        unit: String? = null,
        doc: String? = null,
        min: Double? = null,
        max: Double? = null,
        luaType: String = "any",
        getter: () -> Any?,
    ) {
        val desc = PropertyDescriptor(
            path = path,
            luaType = luaType,
            readable = true,
            writable = false,
            unit = unit,
            doc = doc,
            min = min,
            max = max,
            declaredBy = declaredBy,
        )
        rootProperties.add(desc)
        readers[path] = getter
    }

    /**
     * Declare a read/write property at the component root. Both
     * [getter] and [setter] are required. For setters that need
     * type coercion (scev's FS hands you a String), convert inside
     * the lambda.
     */
    fun readWrite(
        path: String,
        unit: String? = null,
        doc: String? = null,
        min: Double? = null,
        max: Double? = null,
        luaType: String = "any",
        getter: () -> Any?,
        setter: (Any?) -> Unit,
    ) {
        val desc = PropertyDescriptor(
            path = path,
            luaType = luaType,
            readable = true,
            writable = true,
            unit = unit,
            doc = doc,
            min = min,
            max = max,
            declaredBy = declaredBy,
        )
        rootProperties.add(desc)
        readers[path] = getter
        writers[path] = setter
    }

    /**
     * Declare an action at the component root. The action handler
     * receives arguments as an untyped `Array<Any?>`; the builder
     * doesn't currently infer param specs from the handler type
     * signature — declare [params] explicitly when you care about the
     * describe output.
     */
    fun action(
        path: String,
        resultPath: String? = null,
        doc: String? = null,
        onTick: Boolean = true,
        params: List<ParamSpec> = emptyList(),
        returnShape: ReturnShape = ReturnShape.ONE,
        handler: (Array<Any?>) -> Any?,
    ) {
        val desc = ActionDescriptor(
            path = path,
            resultPath = resultPath ?: "${path}_result",
            params = params,
            returnShape = returnShape,
            onTick = onTick,
            doc = doc,
            declaredBy = declaredBy,
        )
        rootActions.add(desc)
        actionHandlers[path] = handler
    }

    /** Declare a top-level event stream. */
    fun event(
        name: String,
        doc: String? = null,
        paramShape: List<ParamSpec> = emptyList(),
    ) {
        rootEvents.add(EventDescriptor(name = name, doc = doc, paramShape = paramShape))
    }

    internal fun build(): BuiltComponent {
        // Absorb each plugin's handlers into the top-level maps,
        // prefixing paths with the plugin slug.
        val pluginDescriptors = mutableListOf<PluginDescriptor>()
        for (pb in plugins) {
            val built = pb.buildInto(readers, writers, actionHandlers)
            pluginDescriptors.add(built)
        }
        val desc = ComponentDescriptor(
            name = name,
            role = role,
            declaringClass = "(dsl)",
            rootProperties = rootProperties.sortedBy { it.path },
            rootActions = rootActions.sortedBy { it.path },
            rootEvents = rootEvents.sortedBy { it.name },
            plugins = pluginDescriptors.sortedBy { it.slug },
            doc = doc,
        )
        return BuiltComponent(
            descriptor = desc,
            handlers = ComponentHandlers(
                readers = readers.toMap(),
                writers = writers.toMap(),
                actions = actionHandlers.toMap(),
                pluginGates = pluginGates.toMap(),
            ),
        )
    }
}

@ScevDsl
class PluginBuilder internal constructor(
    private val slug: String,
    private val capability: String?,
    private val doc: String?,
    internal val suitable: () -> Boolean,
    private val ownerDeclaredBy: String,
) {
    private val properties = mutableListOf<PropertyDescriptor>()
    private val actions = mutableListOf<ActionDescriptor>()
    private val events = mutableListOf<EventDescriptor>()
    private val localReaders = mutableMapOf<String, () -> Any?>()
    private val localWriters = mutableMapOf<String, (Any?) -> Unit>()
    private val localActions = mutableMapOf<String, (Array<Any?>) -> Any?>()

    private val declaredBy: String = "dsl:$ownerDeclaredBy/$slug"

    fun readOnly(
        path: String,
        unit: String? = null,
        doc: String? = null,
        min: Double? = null,
        max: Double? = null,
        luaType: String = "any",
        getter: () -> Any?,
    ) {
        properties.add(
            PropertyDescriptor(
                path = path, luaType = luaType, readable = true, writable = false,
                unit = unit, doc = doc, min = min, max = max, declaredBy = declaredBy,
            ),
        )
        localReaders[path] = getter
    }

    fun readWrite(
        path: String,
        unit: String? = null,
        doc: String? = null,
        min: Double? = null,
        max: Double? = null,
        luaType: String = "any",
        getter: () -> Any?,
        setter: (Any?) -> Unit,
    ) {
        properties.add(
            PropertyDescriptor(
                path = path, luaType = luaType, readable = true, writable = true,
                unit = unit, doc = doc, min = min, max = max, declaredBy = declaredBy,
            ),
        )
        localReaders[path] = getter
        localWriters[path] = setter
    }

    fun action(
        path: String,
        resultPath: String? = null,
        doc: String? = null,
        onTick: Boolean = true,
        params: List<ParamSpec> = emptyList(),
        returnShape: ReturnShape = ReturnShape.ONE,
        handler: (Array<Any?>) -> Any?,
    ) {
        actions.add(
            ActionDescriptor(
                path = path,
                resultPath = resultPath ?: "${path}_result",
                params = params, returnShape = returnShape, onTick = onTick,
                doc = doc, declaredBy = declaredBy,
            ),
        )
        localActions[path] = handler
    }

    fun event(
        name: String,
        doc: String? = null,
        paramShape: List<ParamSpec> = emptyList(),
    ) {
        events.add(EventDescriptor(name = name, doc = doc, paramShape = paramShape))
    }

    /**
     * Fold this plugin's handlers into the owner's flat maps. The
     * plugin's paths are prefixed with `<slug>/` when stored in the
     * owner's table so runtime lookup stays a single hash hit.
     */
    internal fun buildInto(
        ownerReaders: MutableMap<String, () -> Any?>,
        ownerWriters: MutableMap<String, (Any?) -> Unit>,
        ownerActions: MutableMap<String, (Array<Any?>) -> Any?>,
    ): PluginDescriptor {
        for ((p, r) in localReaders) ownerReaders["$slug/$p"] = r
        for ((p, w) in localWriters) ownerWriters["$slug/$p"] = w
        for ((p, a) in localActions) ownerActions["$slug/$p"] = a

        val paths = (properties.map { it.path } + actions.map { it.path })
        val dup = paths.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (dup.isNotEmpty()) {
            throw IllegalArgumentException(
                "Plugin '$slug' has duplicate paths: $dup",
            )
        }

        return PluginDescriptor(
            slug = slug,
            capability = capability,
            declaredBy = ownerDeclaredBy.substringAfterLast('/'),
            properties = properties.sortedBy { it.path },
            actions = actions.sortedBy { it.path },
            events = events.sortedBy { it.name },
            doc = doc,
        )
    }
}
