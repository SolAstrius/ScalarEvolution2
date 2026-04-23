/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.component

import lekkit.scev.component.ScevPlugin
import lekkit.scev.component.api.Action
import lekkit.scev.component.api.Plugin
import lekkit.scev.component.api.Property
import lekkit.scev.component.api.ScevComponent
import lekkit.scev.component.describe.ReturnShape
import lekkit.scev.component.scanner.ComponentScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the scanner against hand-written components modelled on
 * each peripheral shape we surveyed (power monitor, routing detector,
 * integration peripheral, chat-style). Each test asserts over the
 * emitted [lekkit.scev.component.describe.ComponentDescriptor] —
 * purely data comparisons, no runtime dependency.
 */
class ScannerTest {

    // =================================================================
    // Shape 1: PropertyBag with scalars + rw config (PowerMonitor style)
    // =================================================================

    enum class Mode { OFF, AUTO, MANUAL }

    @ScevComponent(name = "power_monitor", doc = "EnderIO power monitor, annotation form")
    class PowerMonitor {
        // Scalars.
        @Property(unit = "FE")   fun conduitPower() = 123_456L
        @Property(unit = "FE")   fun maxConduitPower() = 500_000L
        @Property(unit = "FE/t") fun averageEnergySent() = 1_000L

        // Paired getter/setter — fuses into one rw file.
        private var start = 0.75f
        @Property(min = 0.0, max = 1.0)
        fun startLevel() = start.toDouble()
        fun setStartLevel(v: Double) { start = v.toFloat() }

        // Boolean getter + setter.
        private var engine = false
        @Property fun isEngineEnabled() = engine
        fun setEngineEnabled(v: Boolean) { engine = v }

        // Enum-valued read-only property.
        @Property fun mode(): Mode = Mode.AUTO
    }

    @Test fun `scans scalars, getter-setter fusion, enum, flags`() {
        val d = ComponentScanner.scan(PowerMonitor())
        assertEquals("power_monitor", d.name)
        assertEquals("EnderIO power monitor, annotation form", d.doc)

        // Property map by derived path.
        val props = d.rootProperties.associateBy { it.path }
        assertEquals(6, props.size, props.keys.toString())

        // Fused rw: startLevel → start_level, writable=true due to setter.
        val start = props.getValue("start_level")
        assertTrue(start.readable)
        assertTrue(start.writable, "getter+setter should fuse into writable file")
        assertEquals(0.0, start.min)
        assertEquals(1.0, start.max)

        // isEngineEnabled → engine_enabled, writable via setter.
        val eng = props.getValue("engine_enabled")
        assertTrue(eng.writable, "is-prefix getter + matching setter must fuse")
        assertEquals("boolean", eng.luaType)

        // Scalars are read-only.
        val cp = props.getValue("conduit_power")
        assertFalse(cp.writable)
        assertEquals("FE", cp.unit)
        assertEquals("number", cp.luaType)

        // Enum property exposes values.
        val mode = props.getValue("mode")
        assertEquals("string", mode.luaType)
        assertEquals(listOf("off", "auto", "manual"), mode.enumValues)
        assertFalse(mode.writable)
    }

    // =================================================================
    // Shape 2: IntegrationPeripheral-style plugin composition
    // =================================================================

    class EnergyPlugin(private val beHasCap: Boolean) : ScevPlugin {
        override fun isSuitable(): Boolean = beHasCap

        @Property(unit = "FE")   fun stored() = 100L
        @Property(unit = "FE")   fun capacity() = 1_000L
        @Property(unit = "FE/t") fun throughputIn() = 5L
    }

    class InventoryPlugin : ScevPlugin {
        @Property fun slotCount() = 27

        @Action(doc = "Move items between slots")
        fun move(from: Int, to: Int, count: Int): Boolean = true
    }

    @ScevComponent(name = "integration_peripheral")
    class IntegrationPeripheral(
        private val hasEnergy: Boolean,
        private val hasItems: Boolean,
    ) {
        @Plugin(value = "energy", capability = "neoforge:energy_storage")
        fun energy() = EnergyPlugin(hasEnergy)

        @Plugin(value = "items", capability = "neoforge:item_handler")
        fun items() = if (hasItems) InventoryPlugin() else null
    }

    @Test fun `plugin gating via ScevPlugin isSuitable`() {
        val d = ComponentScanner.scan(IntegrationPeripheral(hasEnergy = true, hasItems = true))
        val slugs = d.plugins.map { it.slug }.toSet()
        assertEquals(setOf("energy", "items"), slugs)

        val energy = d.plugins.first { it.slug == "energy" }
        assertEquals("neoforge:energy_storage", energy.capability)
        val paths = energy.properties.map { it.path }.toSet()
        assertEquals(setOf("stored", "capacity", "throughput_in"), paths)

        val items = d.plugins.first { it.slug == "items" }
        assertEquals("neoforge:item_handler", items.capability)
        assertEquals(listOf("move"), items.actions.map { it.path })

        // isSuitable=false → plugin absent.
        val noEnergy = ComponentScanner.scan(IntegrationPeripheral(hasEnergy = false, hasItems = true))
        assertEquals(setOf("items"), noEnergy.plugins.map { it.slug }.toSet())

        // Null-returning plugin accessor → plugin absent.
        val nothing = ComponentScanner.scan(IntegrationPeripheral(hasEnergy = true, hasItems = false))
        assertEquals(setOf("energy"), nothing.plugins.map { it.slug }.toSet())
    }

    @Test fun `action method params and return shape are extracted`() {
        val d = ComponentScanner.scan(IntegrationPeripheral(hasEnergy = false, hasItems = true))
        val items = d.plugins.single { it.slug == "items" }
        val move = items.actions.single { it.path == "move" }
        assertEquals(3, move.params.size)
        assertTrue(move.params.all { it.luaType == "number" })
        assertEquals(ReturnShape.ONE, move.returnShape)
        assertTrue(move.onTick, "@Action defaults to on-tick dispatch")
        assertEquals("Move items between slots", move.doc)
        assertEquals("move_result", move.resultPath)
    }

    // =================================================================
    // Shape 3: Multi-name alias / doc validation
    // =================================================================

    @ScevComponent(name = "explicit_paths")
    class ExplicitPaths {
        @Property(value = "custom/path", unit = "FE", doc = "Explicitly named")
        fun someWeirdName(): Long = 42L
    }

    @Test fun `explicit value overrides derivation`() {
        val d = ComponentScanner.scan(ExplicitPaths())
        val p = d.rootProperties.single()
        assertEquals("custom/path", p.path)
        assertEquals("FE", p.unit)
        assertEquals("Explicitly named", p.doc)
    }

    // =================================================================
    // Shape 4: Validation errors
    // =================================================================

    class NoAnnotation {
        @Property fun foo(): Int = 0
    }

    @Test fun `missing ScevComponent is a structural error`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComponentScanner.scan(NoAnnotation())
        }
        assertTrue(ex.message!!.contains("@ScevComponent"))
    }

    @ScevComponent(name = "BAD_NAME")   // uppercase violates grammar
    class BadName

    @Test fun `bad name grammar is a structural error`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComponentScanner.scan(BadName())
        }
        assertTrue(ex.message!!.contains("BAD_NAME"))
    }

    @ScevComponent(name = "duplicate_path")
    class DuplicatePath {
        @Property(value = "same") fun a(): Int = 1
        @Property(value = "same") fun b(): Int = 2
    }

    @Test fun `duplicate property path is a structural error`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComponentScanner.scan(DuplicatePath())
        }
        assertTrue(ex.message!!.contains("Duplicate"), ex.message)
    }

    class BadSuitablePlugin {
        @lekkit.scev.component.api.Suitable fun tellMe(): String = "yes"   // non-boolean return
    }

    @ScevComponent(name = "bad_suitable_host")
    class BadSuitableHost {
        @Plugin("x") fun x() = BadSuitablePlugin()
    }

    @Test fun `Suitable must return boolean`() {
        assertThrows(IllegalArgumentException::class.java) {
            ComponentScanner.scan(BadSuitableHost())
        }
    }

    // =================================================================
    // Shape 5: @Suitable annotation wins over interface
    // =================================================================

    class AnnotationGatedPlugin(val suitableFlag: Boolean) : ScevPlugin {
        override fun isSuitable(): Boolean = false   // interface says no
        @lekkit.scev.component.api.Suitable
        fun actuallySuitable(): Boolean = suitableFlag   // annotation says yes
        @Property fun value() = 1
    }

    @ScevComponent(name = "annotation_wins")
    class AnnotationWins(val flag: Boolean) {
        @Plugin("p") fun p() = AnnotationGatedPlugin(flag)
    }

    @Test fun `Suitable annotation overrides ScevPlugin isSuitable`() {
        val t = ComponentScanner.scan(AnnotationWins(flag = true))
        assertEquals(1, t.plugins.size, "annotation said yes, plugin should appear")

        val f = ComponentScanner.scan(AnnotationWins(flag = false))
        assertEquals(0, f.plugins.size, "annotation said no, plugin should not appear")
    }

    // =================================================================
    // Shape 6: allProperties/allActions flatteners
    // =================================================================

    @Test fun `allProperties flattens with plugin slug prefix`() {
        val d = ComponentScanner.scan(IntegrationPeripheral(hasEnergy = true, hasItems = true))
        val paths = d.allProperties().map { it.first }.toSet()
        assertTrue("energy/stored" in paths)
        assertTrue("energy/capacity" in paths)
        assertTrue("items/slot_count" in paths)

        val actions = d.allActions().map { it.first }.toSet()
        assertTrue("items/move" in actions)
    }
}
