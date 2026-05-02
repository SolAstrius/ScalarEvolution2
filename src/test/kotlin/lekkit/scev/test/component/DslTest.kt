/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.component

import lekkit.scev.component.api.InvalidArgumentException
import lekkit.scev.component.api.OutOfRangeException
import lekkit.scev.component.describe.ParamSpec
import lekkit.scev.component.describe.ReturnShape
import lekkit.scev.component.dsl.scevComponent
import lekkit.scev.component.prebuilt.SlotAccess
import lekkit.scev.component.prebuilt.SlotSnapshot
import lekkit.scev.component.prebuilt.TankAccess
import lekkit.scev.component.prebuilt.TankSnapshot
import lekkit.scev.component.prebuilt.TerminalAccess
import lekkit.scev.component.prebuilt.documentWithTitle
import lekkit.scev.component.prebuilt.fromEnergyStorage
import lekkit.scev.component.prebuilt.fromFluidTanks
import lekkit.scev.component.prebuilt.fromItemSlots
import lekkit.scev.component.prebuilt.fromRedstoneSide
import lekkit.scev.component.prebuilt.fromTerminal
import lekkit.scev.component.prebuilt.sensorWithThresholds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the Kotlin DSL against the same peripheral shapes as the
 * scanner test, plus each prebuilt adapter. Covers wiring from the
 * handler maps to the underlying lambdas.
 */
class DslTest {

    // =================================================================
    // Shape 1: plugin with readOnly / readWrite / action
    // =================================================================

    @Test fun `DSL builds a plugin with all three member kinds`() {
        var stored = 100L
        val built = scevComponent("reactor") {
            plugin("energy", capability = "neoforge:energy_storage") {
                readOnly("stored", unit = "FE") { stored }
                readOnly("capacity", unit = "FE") { 1000L }
                readWrite(
                    "limit", min = 0.0, max = 1.0,
                    getter = { 0.75 },
                    setter = { v -> stored = (v as Number).toLong() },
                )
                action(
                    "pulse",
                    params = listOf(ParamSpec("number")),
                    returnShape = ReturnShape.ONE,
                ) { args ->
                    val n = (args[0] as Number).toInt()
                    stored += n
                    stored
                }
            }
        }

        val desc = built.descriptor
        assertEquals("reactor", desc.name)
        val energy = desc.plugins.single()
        assertEquals("energy", energy.slug)
        assertEquals("neoforge:energy_storage", energy.capability)

        val props = energy.properties.associateBy { it.path }
        assertEquals(3, props.size)
        assertFalse(props.getValue("stored").writable)
        assertTrue(props.getValue("limit").writable)
        assertEquals(0.0, props.getValue("limit").min)
        assertEquals(1.0, props.getValue("limit").max)

        // Actions.
        val pulse = energy.actions.single()
        assertEquals("pulse", pulse.path)
        assertEquals("pulse_result", pulse.resultPath)

        // Handlers are accessible and call the real lambdas.
        val readStored = built.handlers.readers.getValue("energy/stored")
        assertEquals(100L, readStored())

        val write = built.handlers.writers.getValue("energy/limit")
        write(42)
        assertEquals(42L, stored)

        val pulseHandler = built.handlers.actions.getValue("energy/pulse")
        val result = pulseHandler(arrayOf<Any?>(8))
        assertEquals(50L, result, "pulse(8): 42 + 8 = 50")
    }

    // =================================================================
    // Shape 2: plugin gating via suitable lambda
    // =================================================================

    @Test fun `plugin suitable lambda records a gate`() {
        var present = true
        val built = scevComponent("tank") {
            plugin("fluid", suitable = { present }) {
                readOnly("level") { 500 }
            }
        }
        val gate = built.handlers.pluginGates.getValue("fluid")
        assertTrue(gate())
        present = false
        assertFalse(gate())
    }

    // =================================================================
    // Shape 3: prebuilt — fromEnergyStorage / fromItemSlots / fromFluidTanks
    // =================================================================

    @Test fun `fromEnergyStorage produces canonical energy subtree`() {
        val built = scevComponent("generator") {
            fromEnergyStorage(
                stored = { 250L },
                capacityValue = { 1000L },
                canReceive = { true },
                canExtract = { false },
            )
        }
        val energy = built.descriptor.plugins.single { it.slug == "energy" }
        val paths = energy.properties.map { it.path }.toSet()
        assertEquals(setOf("stored", "capacity", "can_receive", "can_extract"), paths)
        assertEquals("neoforge:energy_storage", energy.capability)

        assertEquals(250L, built.handlers.readers.getValue("energy/stored")())
        assertEquals(true, built.handlers.readers.getValue("energy/can_receive")())
        assertEquals(false, built.handlers.readers.getValue("energy/can_extract")())
    }

    private class FakeSlots(private val items: List<SlotSnapshot>) : SlotAccess {
        override val size = items.size
        override fun snapshot(index: Int) = items[index]
    }

    @Test fun `fromItemSlots expands per-slot files`() {
        val slots = FakeSlots(listOf(
            SlotSnapshot("minecraft:coal", 5, componentsJson = ""),
            SlotSnapshot("minecraft:iron_ingot", 64, componentsJson = "{}"),
            SlotSnapshot("", 0, componentsJson = ""),
        ))
        val built = scevComponent("chest") {
            fromItemSlots(access = { slots })
        }
        val items = built.descriptor.plugins.single { it.slug == "items" }
        val paths = items.properties.map { it.path }.toSet()
        assertTrue(paths.contains("slots/count"))
        for (i in 0..2) {
            assertTrue(paths.contains("slots/$i/item"), "missing slots/$i/item")
            assertTrue(paths.contains("slots/$i/count"))
            assertTrue(paths.contains("slots/$i/components"))
        }
        assertEquals("minecraft:coal", built.handlers.readers.getValue("items/slots/0/item")())
        assertEquals(64, built.handlers.readers.getValue("items/slots/1/count")())
    }

    private class FakeTanks(private val tanks: List<TankSnapshot>) : TankAccess {
        override val size = tanks.size
        override fun snapshot(index: Int) = tanks[index]
    }

    @Test fun `fromFluidTanks expands per-tank files with mB units`() {
        val tanks = FakeTanks(listOf(
            TankSnapshot("minecraft:water", 500, 1000),
            TankSnapshot("", 0, 500),
        ))
        val built = scevComponent("mixer") {
            fromFluidTanks(access = { tanks })
        }
        val fluid = built.descriptor.plugins.single()
        val amountUnit = fluid.properties.single { it.path == "tanks/0/amount" }.unit
        assertEquals("mB", amountUnit)
        assertEquals("minecraft:water", built.handlers.readers.getValue("fluid/tanks/0/fluid")())
        assertEquals(500, built.handlers.readers.getValue("fluid/tanks/0/amount")())
    }

    // =================================================================
    // Shape 4: prebuilt — fromTerminal
    // =================================================================

    private class FakeTerm : TerminalAccess {
        override val width = 25
        override val height = 4
        override val cursorX = 1
        override val cursorY = 2
        override fun line(row: Int) = "row $row"
    }

    @Test fun `fromTerminal lays out char grid with per-row files`() {
        val built = scevComponent("monitor") {
            fromTerminal(access = { FakeTerm() })
        }
        val term = built.descriptor.plugins.single()
        assertEquals("vt100", term.slug)
        val paths = term.properties.map { it.path }.toSet()
        assertTrue("width" in paths)
        assertTrue("height" in paths)
        assertTrue("cursor_x" in paths)
        for (row in 0..3) assertTrue("lines/$row" in paths)
        assertEquals("row 2", built.handlers.readers.getValue("vt100/lines/2")())
    }

    // =================================================================
    // Shape 5: fromRedstoneSide with bounds-check errors
    // =================================================================

    @Test fun `redstone output bounds-check throws typed errors`() {
        var output = 0
        val built = scevComponent("rs_side") {
            fromRedstoneSide(
                readInput = { 7 },
                readOutput = { output },
                writeOutput = { output = it },
            )
        }
        val write = built.handlers.writers.getValue("redstone/output")

        write(12)
        assertEquals(12, output)

        val ex1 = assertThrows(OutOfRangeException::class.java) { write(20) }
        assertTrue(ex1.message!!.contains("0..15"), ex1.message)

        val ex2 = assertThrows(InvalidArgumentException::class.java) { write("not a number") }
        assertTrue(ex2.message!!.contains("must be 0..15"))
    }

    // =================================================================
    // Shape 6: documentWithTitle — Routing Detector shape
    // =================================================================

    @Test fun `documentWithTitle matches routing-detector surface`() {
        var text = "rule-a\nrule-b\n\nrule-c\n"
        var title = "my yard"
        val built = scevComponent("routing_detector") {
            documentWithTitle(
                slug = "table",
                getText = { text },
                setText = { text = it },
                getTitle = { title },
                setTitle = { title = it },
                isPresent = { true },
                isLocked = { false },
            )
        }

        // Four root properties.
        val paths = built.descriptor.rootProperties.map { it.path }.toSet()
        assertEquals(setOf("table", "table_title", "table_present", "table_locked"), paths)

        val tablePath = built.descriptor.rootProperties.single { it.path == "table" }
        assertTrue(tablePath.readable)
        assertTrue(tablePath.writable)
        val locked = built.descriptor.rootProperties.single { it.path == "table_locked" }
        assertFalse(locked.writable)

        // Write semantics.
        val tableWrite = built.handlers.writers.getValue("table")
        tableWrite("new rules")
        assertEquals("new rules", text)
        assertEquals("new rules", built.handlers.readers.getValue("table")())

        // Type errors surface.
        assertThrows(InvalidArgumentException::class.java) { tableWrite(42) }
    }

    // =================================================================
    // Shape 7: sensorWithThresholds — Energy/Player Detector shape
    // =================================================================

    @Test fun `sensorWithThresholds exposes value plus two bounds`() {
        var low = 100.0
        var high = 900.0
        val built = scevComponent("energy_detector") {
            sensorWithThresholds(
                slug = "power",
                unit = "FE",
                capability = "scev:energy_threshold",
                read = { 500L },
                readLow = { low },
                writeLow = { low = it },
                readHigh = { high },
                writeHigh = { high = it },
            )
        }
        val plugin = built.descriptor.plugins.single()
        val paths = plugin.properties.map { it.path }.toSet()
        assertEquals(setOf("value", "threshold_low", "threshold_high"), paths)

        val writeLow = built.handlers.writers.getValue("power/threshold_low")
        writeLow(42.5)
        assertEquals(42.5, low)

        // Accepts numeric strings.
        writeLow("3.14")
        assertEquals(3.14, low)

        assertThrows(InvalidArgumentException::class.java) { writeLow("garbage") }
    }

    // =================================================================
    // Shape 8: events
    // =================================================================

    @Test fun `event declarations land in rootEvents or plugin events`() {
        val built = scevComponent("chat_box") {
            event("chat", paramShape = listOf(ParamSpec("string"), ParamSpec("string")))
            plugin("filter", suitable = { true }) {
                event("blocked")
            }
        }
        assertEquals(listOf("chat"), built.descriptor.rootEvents.map { it.name })
        val filter = built.descriptor.plugins.single()
        assertEquals(listOf("blocked"), filter.events.map { it.name })

        val chatShape = built.descriptor.rootEvents.single().paramShape
        assertEquals(2, chatShape.size)
        assertTrue(chatShape.all { it.luaType == "string" })
    }
}
