/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component.scanner

import lekkit.scev.component.ComponentRole
import lekkit.scev.component.ScevPlugin
import lekkit.scev.component.api.Action
import lekkit.scev.component.api.Plugin
import lekkit.scev.component.api.Property
import lekkit.scev.component.api.ScevComponent
import lekkit.scev.component.api.Suitable
import lekkit.scev.component.describe.ActionDescriptor
import lekkit.scev.component.describe.ComponentDescriptor
import lekkit.scev.component.describe.ParamSpec
import lekkit.scev.component.describe.PluginDescriptor
import lekkit.scev.component.describe.PropertyDescriptor
import lekkit.scev.component.describe.ReturnShape
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.nio.ByteBuffer
import java.util.Optional
import java.util.regex.Pattern

/**
 * Walks a component instance, extracts every [@ScevComponent], [@Plugin],
 * [@Property], [@Action] it finds, and produces a pure-data
 * [ComponentDescriptor] ready for the eventual runtime to serve.
 *
 * The scanner is deliberately reflection-only — no code generation,
 * no annotation processor, no classpath scanning. That keeps scev's
 * runtime cost simple and predictable (one walk per component class,
 * amortised over every instance of that class).
 *
 * Coverage matches the annotation set:
 *  - Getters: any `@Property`-annotated no-arg method, or its
 *    corresponding getter on a `@Plugin`-returning method.
 *  - Setters: fused with getters when a `setX` exists with a single
 *    parameter whose type matches the getter's return. Standalone
 *    setters without a matching getter are ignored (this is uncommon
 *    and usually a mistake; the DSL `writeOnly(...)` exposes them
 *    explicitly).
 *  - Actions: multi-arg methods annotated with `@Action`. Return
 *    shape classified per [ReturnShape] rules.
 *  - Plugins: each `@Plugin`-annotated getter or field contributes
 *    a subtree. Gating: `@Suitable` annotation > `ScevPlugin.isSuitable()`
 *    > assumed true.
 *
 * Validation is strict — names are checked against the grammar
 * declared on the annotations, and duplicates within a plugin throw
 * at scan time rather than silently colliding at runtime.
 *
 * Output is deterministic (properties and actions sorted
 * alphabetically by path) so tests and wire-format output compare
 * cleanly.
 */
object ComponentScanner {

    private val NAME_GRAMMAR: Pattern = Pattern.compile("[a-z0-9._-]{1,64}")
    private val SLUG_GRAMMAR: Pattern = Pattern.compile("[a-z0-9._-]{1,32}")

    /**
     * Scan [component] into a descriptor. Throws
     * [IllegalArgumentException] for structural errors (missing
     * `@ScevComponent`, illegal names, duplicate paths, ill-typed
     * parameters).
     */
    @JvmStatic
    fun scan(component: Any): ComponentDescriptor {
        val cls = component::class.java
        val ann = cls.getAnnotation(ScevComponent::class.java)
            ?: throw IllegalArgumentException(
                "${cls.name} is missing @ScevComponent — cannot scan",
            )
        requireName(ann.name, "component.name", NAME_GRAMMAR)
        val role = ComponentRole.fromString(ann.role)

        val rootProps = mutableListOf<PropertyDescriptor>()
        val rootActions = mutableListOf<ActionDescriptor>()
        val pluginDescs = mutableListOf<PluginDescriptor>()

        // Index all getters/setters on the root class for fusion.
        val classMembers = ClassMembers.from(cls)

        // Collect plugin references — each is a method / field that
        // returns a plugin instance.
        val pluginRefs = findPlugins(cls)
        for ((pluginAnn, accessor) in pluginRefs) {
            val pluginInstance = runCatching { accessor.get(component) }
                .getOrElse { ex ->
                    throw IllegalStateException(
                        "Failed to read plugin '${pluginAnn.value}' via $accessor",
                        ex,
                    )
                } ?: continue
            if (!gatingOk(pluginInstance)) continue
            pluginDescs.add(scanPlugin(pluginInstance, pluginAnn))
        }

        // Root-level properties and actions (on the component class itself).
        pluginDescs.toList() // force eval before we start indexing root
        scanPropertiesAndActions(classMembers, rootProps, rootActions, cls.simpleName)

        // Sort everything for determinism.
        rootProps.sortBy { it.path }
        rootActions.sortBy { it.path }
        pluginDescs.sortBy { it.slug }

        return ComponentDescriptor(
            name = ann.name,
            role = role,
            declaringClass = cls.name,
            rootProperties = rootProps,
            rootActions = rootActions,
            plugins = pluginDescs,
            doc = ann.doc.ifEmpty { null },
        )
    }

    // ============================================================
    //  Plugin discovery
    // ============================================================

    /** A [Plugin] annotation paired with a reader that can fetch the instance. */
    private data class PluginRef(val ann: Plugin, val accessor: Accessor)

    private fun findPlugins(cls: Class<*>): List<Pair<Plugin, Accessor>> {
        val out = mutableListOf<Pair<Plugin, Accessor>>()
        for (m in cls.methods) {
            val a = m.getAnnotation(Plugin::class.java) ?: continue
            if (m.parameterCount != 0) {
                throw IllegalArgumentException(
                    "@Plugin on ${cls.name}.${m.name} must be zero-arg",
                )
            }
            requireName(a.value, "plugin.value", SLUG_GRAMMAR)
            m.isAccessible = true
            out.add(a to Accessor.MethodAccessor(m))
        }
        for (f in cls.fields) {
            val a = f.getAnnotation(Plugin::class.java) ?: continue
            requireName(a.value, "plugin.value", SLUG_GRAMMAR)
            f.isAccessible = true
            out.add(a to Accessor.FieldAccessor(f))
        }
        // Guard against the same slug appearing twice.
        val dup = out.groupBy { it.first.value }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) {
            throw IllegalArgumentException(
                "${cls.name} has duplicate @Plugin slugs: $dup",
            )
        }
        return out
    }

    /** Walk one plugin instance's own class. */
    private fun scanPlugin(plugin: Any, ann: Plugin): PluginDescriptor {
        val cls = plugin::class.java
        val members = ClassMembers.from(cls)

        val props = mutableListOf<PropertyDescriptor>()
        val actions = mutableListOf<ActionDescriptor>()
        scanPropertiesAndActions(members, props, actions, cls.simpleName)

        props.sortBy { it.path }
        actions.sortBy { it.path }

        // Collision check within the plugin.
        val paths = (props.map { it.path } + actions.map { it.path })
        val dup = paths.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (dup.isNotEmpty()) {
            throw IllegalArgumentException(
                "Plugin '${ann.value}' (${cls.name}) has duplicate paths: $dup",
            )
        }

        return PluginDescriptor(
            slug = ann.value,
            capability = ann.capability.ifEmpty { null },
            declaredBy = cls.simpleName,
            properties = props,
            actions = actions,
            doc = ann.doc.ifEmpty { null },
        )
    }

    /** `isSuitable` check: `@Suitable` annotation > ScevPlugin interface > true. */
    private fun gatingOk(instance: Any): Boolean {
        val cls = instance::class.java
        val annotated = cls.methods.firstOrNull { it.isAnnotationPresent(Suitable::class.java) }
        if (annotated != null) {
            require(annotated.parameterCount == 0) {
                "@Suitable method ${cls.name}.${annotated.name} must be zero-arg"
            }
            require(annotated.returnType == Boolean::class.javaPrimitiveType ||
                annotated.returnType == java.lang.Boolean::class.java) {
                "@Suitable method ${cls.name}.${annotated.name} must return boolean"
            }
            annotated.isAccessible = true
            return annotated.invoke(instance) as Boolean
        }
        if (instance is ScevPlugin) return instance.isSuitable()
        return true
    }

    // ============================================================
    //  Property + Action extraction
    // ============================================================

    /**
     * Fill [props] and [actions] by walking every [@Property] and
     * [@Action] method on the class surface in [members].
     */
    private fun scanPropertiesAndActions(
        members: ClassMembers,
        props: MutableList<PropertyDescriptor>,
        actions: MutableList<ActionDescriptor>,
        declaredBy: String,
    ) {
        // Properties — fuse with setters in one pass.
        val seenPaths = mutableSetOf<String>()
        for (getter in members.propertyGetters) {
            val ann = getter.getAnnotation(Property::class.java)!!
            val path = ann.value.ifEmpty { derivePropertyPath(getter.name) }
            if (!seenPaths.add(path)) {
                throw IllegalArgumentException(
                    "Duplicate @Property path '$path' in $declaredBy — use explicit value= to disambiguate",
                )
            }
            val setter = members.findMatchingSetter(getter, path)
            val luaType = luaTypeOf(getter.returnType, getter.genericReturnType)
            val enumValues = enumValuesOf(getter.returnType)
            props.add(
                PropertyDescriptor(
                    path = path,
                    luaType = luaType,
                    readable = true,
                    writable = setter != null || ann.writable,
                    unit = ann.unit.ifEmpty { null },
                    doc = ann.doc.ifEmpty { null },
                    min = ann.min.takeUnless(Double::isNaN),
                    max = ann.max.takeUnless(Double::isNaN),
                    enumValues = enumValues,
                    declaredBy = declaredBy,
                ),
            )
        }

        // Actions.
        for (method in members.actionMethods) {
            val ann = method.getAnnotation(Action::class.java)!!
            val path = ann.value.ifEmpty { derivePath(method.name) }
            val resultPath = ann.resultPath.ifEmpty { "${path}_result" }
            val params = describeParams(method)
            val returnShape = classifyReturnShape(method.returnType)
            actions.add(
                ActionDescriptor(
                    path = path,
                    resultPath = resultPath,
                    params = params,
                    returnShape = returnShape,
                    onTick = !ann.offThread,
                    doc = ann.doc.ifEmpty { null },
                    declaredBy = declaredBy,
                ),
            )
        }
    }

    /**
     * Snake-case a method name, stripping the `get`/`is`/`has` prefix
     * when used as a property-getter derivation.
     */
    private fun derivePropertyPath(methodName: String): String {
        val stripped = when {
            methodName.length > 3 && methodName.startsWith("get") && methodName[3].isUpperCase() ->
                methodName.substring(3)
            methodName.length > 2 && methodName.startsWith("is") && methodName[2].isUpperCase() ->
                methodName.substring(2)
            methodName.length > 3 && methodName.startsWith("has") && methodName[3].isUpperCase() ->
                methodName.substring(3)
            else -> methodName
        }
        return snakeCase(stripped)
    }

    /** Snake-case an arbitrary method name (no prefix stripping). */
    private fun derivePath(methodName: String): String = snakeCase(methodName)

    private fun snakeCase(s: String): String = buildString {
        for ((i, ch) in s.withIndex()) {
            if (ch.isUpperCase() && i > 0 && !s[i - 1].isUpperCase()) append('_')
            append(ch.lowercaseChar())
        }
    }

    // ============================================================
    //  Param / return classification
    // ============================================================

    private fun describeParams(method: Method): List<ParamSpec> {
        val paramTypes = method.parameterTypes
        val generics = method.genericParameterTypes
        val out = ArrayList<ParamSpec>(paramTypes.size)
        for (i in paramTypes.indices) {
            out.add(describeOneParam(paramTypes[i], generics[i]))
        }
        return out
    }

    private fun describeOneParam(raw: Class<*>, generic: java.lang.reflect.Type): ParamSpec {
        if (raw == Optional::class.java) {
            val inner = (generic as? ParameterizedType)?.actualTypeArguments?.getOrNull(0) as? Class<*>
            if (inner != null) {
                val innerSpec = describeOneParam(inner, inner)
                return innerSpec.copy(optional = true)
            }
            return ParamSpec("any", optional = true)
        }
        if (raw.isEnum && raw != Enum::class.java) {
            @Suppress("UNCHECKED_CAST")
            val values = (raw as Class<out Enum<*>>).enumConstants.map { it.name.lowercase() }
            return ParamSpec("string", enumValues = values)
        }
        return ParamSpec(luaTypeOf(raw, generic))
    }

    private fun classifyReturnShape(retType: Class<*>): ReturnShape {
        if (retType == Void.TYPE) return ReturnShape.NONE
        if (retType.isArray && !retType.componentType.isPrimitive) return ReturnShape.MANY
        // MethodResult would be recognised here in a CC-integration
        // build; we only depend on the scev component API itself, so
        // DYNAMIC is reserved for a future plugin that wants it.
        return ReturnShape.ONE
    }

    private fun luaTypeOf(raw: Class<*>, @Suppress("UNUSED_PARAMETER") generic: java.lang.reflect.Type): String = when (raw) {
        Int::class.javaPrimitiveType, Integer::class.java,
        Long::class.javaPrimitiveType, java.lang.Long::class.java,
        Short::class.javaPrimitiveType, java.lang.Short::class.java,
        Byte::class.javaPrimitiveType, java.lang.Byte::class.java,
        Double::class.javaPrimitiveType, java.lang.Double::class.java,
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> "number"
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> "boolean"
        String::class.java -> "string"
        ByteBuffer::class.java, ByteArray::class.java -> "string|bytes"
        Map::class.java, java.util.Map::class.java -> "table"
        Void::class.javaPrimitiveType, Void::class.java -> "nil"
        else -> if (raw.isEnum) "string" else raw.simpleName
    }

    private fun enumValuesOf(raw: Class<*>): List<String>? =
        if (raw.isEnum && raw != Enum::class.java) {
            @Suppress("UNCHECKED_CAST")
            (raw as Class<out Enum<*>>).enumConstants.map { it.name.lowercase() }
        } else null

    // ============================================================
    //  Utility
    // ============================================================

    private fun requireName(value: String, label: String, pattern: Pattern) {
        if (!pattern.matcher(value).matches()) {
            throw IllegalArgumentException(
                "$label '$value' does not match grammar ${pattern.pattern()}",
            )
        }
    }

    /**
     * Cache-like pre-indexing of a class's methods — groups getters,
     * setters, and actions in one pass so we don't re-iterate
     * `cls.methods` three times.
     */
    private class ClassMembers(
        val propertyGetters: List<Method>,
        /** Map of setter simple-name (after `set`) → method. */
        val setters: Map<String, Method>,
        val actionMethods: List<Method>,
    ) {
        fun findMatchingSetter(getter: Method, derivedPath: String): Method? {
            // Try both the derived-path-based setter and the
            // get-name-strip-based setter. E.g. `getFoo` pairs with
            // `setFoo`; a property named "foo" derived from a getter
            // named "getFoo" also pairs with `setFoo`.
            val pascalPath = pascalCaseFromSnake(derivedPath)
            val candidateNames = buildList {
                add(pascalPath)
                val n = getter.name
                if (n.startsWith("get") || n.startsWith("has")) add(n.substring(3))
                if (n.startsWith("is")) add(n.substring(2))
            }
            for (candidate in candidateNames) {
                val setter = setters[candidate] ?: continue
                if (setter.parameterCount != 1) continue
                if (setter.parameterTypes[0] != getter.returnType) continue
                return setter
            }
            return null
        }

        companion object {
            fun from(cls: Class<*>): ClassMembers {
                val getters = mutableListOf<Method>()
                val setters = mutableMapOf<String, Method>()
                val actions = mutableListOf<Method>()
                for (m in cls.methods) {
                    // Skip synthetic / bridge
                    if (m.isBridge || m.isSynthetic) continue

                    val propAnn = m.getAnnotation(Property::class.java)
                    val actAnn = m.getAnnotation(Action::class.java)

                    if (propAnn != null) {
                        if (m.parameterCount != 0) {
                            throw IllegalArgumentException(
                                "@Property on ${cls.name}.${m.name} must be zero-arg",
                            )
                        }
                        if (m.returnType == Void.TYPE) {
                            throw IllegalArgumentException(
                                "@Property on ${cls.name}.${m.name} must return a value",
                            )
                        }
                        m.isAccessible = true
                        getters.add(m)
                    } else if (actAnn != null) {
                        m.isAccessible = true
                        actions.add(m)
                    } else if (m.name.startsWith("set") && m.name.length > 3 && m.name[3].isUpperCase() && m.parameterCount == 1) {
                        // Candidate setter: we only take it when the
                        // getter-fusion step matches it. No annotation
                        // required — that's the whole point of getter/
                        // setter fusion.
                        m.isAccessible = true
                        setters[m.name.substring(3)] = m
                    }
                }
                return ClassMembers(getters, setters, actions)
            }
        }
    }

    private fun pascalCaseFromSnake(snake: String): String = buildString {
        var capitalise = true
        for (ch in snake) {
            if (ch == '_' || ch == '-') {
                capitalise = true
            } else {
                append(if (capitalise) ch.uppercaseChar() else ch)
                capitalise = false
            }
        }
    }

    /**
     * Small glue over "how we read the plugin instance off the
     * component": method invocation vs field access. Lets the plugin
     * finder produce a uniform accessor from either annotation site.
     */
    private sealed class Accessor {
        abstract fun get(owner: Any): Any?

        class MethodAccessor(private val m: Method) : Accessor() {
            override fun get(owner: Any): Any? = m.invoke(owner)
            override fun toString(): String = m.declaringClass.simpleName + "." + m.name + "()"
        }

        class FieldAccessor(private val f: java.lang.reflect.Field) : Accessor() {
            override fun get(owner: Any): Any? = f.get(owner)
            override fun toString(): String = f.declaringClass.simpleName + "." + f.name
        }
    }
}
