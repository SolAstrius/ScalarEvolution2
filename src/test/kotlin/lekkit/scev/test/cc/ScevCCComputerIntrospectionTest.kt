/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.cc

import lekkit.scev.compat.cc.ScevCCComputer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Exercises the event-schema learner and dispatch-trace ring buffer.
 *
 * Uses only the [ScevCCComputer] surface, not the full RPC stack —
 * the RPC translation layer is tested separately in
 * [ScevCCHandlersIntrospectionTest].
 */
class ScevCCComputerIntrospectionTest {

    @Test fun `schema learner records peripheral-originated events`() {
        val c = ScevCCComputer(UUID.randomUUID())
        c.queueEvent("modem_message", "left", 1L, 2L, "payload", 3.0)
        c.queueEvent("modem_message", "right", 5L, 6L, "other", 10.0)
        c.queueEvent("redstone")
        val schemas = c.eventSchemas()
        assertEquals(2, schemas.size) // two distinct names
        val mm = c.eventSchema("modem_message")!!
        assertEquals(2L, mm.observations)
        // Both observations share the same positional shape.
        assertEquals(1, mm.shapes.size)
        val shape = mm.shapes.keys.first()
        assertEquals(listOf("string", "number", "number", "string", "number"), shape)

        val rs = c.eventSchema("redstone")!!
        assertEquals(1L, rs.observations)
        assertEquals(emptyList<String>(), rs.shapes.keys.first())
    }

    @Test fun `schema learner records guest-injected events`() {
        val c = ScevCCComputer(UUID.randomUUID())
        c.injectEventFromGuest("terminate", emptyList())
        c.injectEventFromGuest("key", listOf(42L, false))
        val schemas = c.eventSchemas()
        assertEquals(2, schemas.size)
        val key = c.eventSchema("key")!!
        assertEquals(listOf("number", "boolean"), key.shapes.keys.first())
    }

    @Test fun `distinct shapes under one event are counted separately`() {
        val c = ScevCCComputer(UUID.randomUUID())
        c.queueEvent("varies", "x")
        c.queueEvent("varies", 1L, 2L)
        c.queueEvent("varies", 1L, 2L)
        val snap = c.eventSchema("varies")!!
        assertEquals(3L, snap.observations)
        assertEquals(2, snap.shapes.size)
        assertEquals(2L, snap.shapes[listOf("number", "number")])
        assertEquals(1L, snap.shapes[listOf("string")])
    }

    @Test fun `shape cap collapses overflow into a sentinel`() {
        val c = ScevCCComputer(UUID.randomUUID())
        // Fill past the cap with distinct shapes.
        for (i in 0 until ScevCCComputer.MAX_SHAPES_PER_EVENT + 5) {
            c.queueEvent("noisy", *Array<Any?>(i) { j -> "val$j" })
        }
        val snap = c.eventSchema("noisy")!!
        // We allow up to MAX_SHAPES distinct keys + the sentinel.
        assertTrue(snap.shapes.size <= ScevCCComputer.MAX_SHAPES_PER_EVENT + 1)
        assertTrue(snap.shapes.containsKey(ScevCCComputer.OVERFLOW_SHAPE))
    }

    @Test fun `clearEventSchemas empties the learner`() {
        val c = ScevCCComputer(UUID.randomUUID())
        c.queueEvent("x")
        assertEquals(1, c.eventSchemas().size)
        c.clearEventSchemas()
        assertEquals(0, c.eventSchemas().size)
    }

    @Test fun `trace is a no-op until enabled`() {
        val c = ScevCCComputer(UUID.randomUUID())
        c.recordTrace(
            ScevCCComputer.DispatchTrace(
                startedAt = 0L,
                durationMicros = 1L,
                peripheralName = "p",
                method = "m",
                argsSummary = "()",
                outcome = "ok",
                detail = null,
            ),
        )
        assertTrue(c.traceSnapshot().isEmpty())

        c.setTraceEnabled(true)
        c.recordTrace(
            ScevCCComputer.DispatchTrace(
                startedAt = 10L,
                durationMicros = 2L,
                peripheralName = "p",
                method = "m",
                argsSummary = "(1 arg: string)",
                outcome = "ok",
                detail = "ok",
            ),
        )
        assertEquals(1, c.traceSnapshot().size)
        assertEquals("m", c.traceSnapshot()[0].method)
    }

    @Test fun `trace ring drops oldest once full`() {
        val c = ScevCCComputer(UUID.randomUUID())
        c.setTraceEnabled(true)
        for (i in 0 until ScevCCComputer.MAX_TRACE_ENTRIES + 10) {
            c.recordTrace(
                ScevCCComputer.DispatchTrace(
                    startedAt = i.toLong(),
                    durationMicros = 0L,
                    peripheralName = "p",
                    method = "m$i",
                    argsSummary = "()",
                    outcome = "ok",
                    detail = null,
                ),
            )
        }
        val snap = c.traceSnapshot()
        assertEquals(ScevCCComputer.MAX_TRACE_ENTRIES, snap.size)
        // Oldest dropped → first entry should be i=10 (assuming 10 overflowed).
        assertEquals("m10", snap.first().method)
        assertEquals("m${ScevCCComputer.MAX_TRACE_ENTRIES + 9}", snap.last().method)
    }

    @Test fun `disabling trace clears the buffer`() {
        val c = ScevCCComputer(UUID.randomUUID())
        c.setTraceEnabled(true)
        c.recordTrace(
            ScevCCComputer.DispatchTrace(
                startedAt = 0L, durationMicros = 0L,
                peripheralName = "p", method = "m", argsSummary = "()",
                outcome = "ok", detail = null,
            ),
        )
        assertEquals(1, c.traceSnapshot().size)
        c.setTraceEnabled(false)
        assertTrue(c.traceSnapshot().isEmpty())
    }

    @Test fun `javaTypeTag tags common kinds`() {
        assertEquals("nil", ScevCCComputer.javaTypeTag(null))
        assertEquals("boolean", ScevCCComputer.javaTypeTag(true))
        assertEquals("number", ScevCCComputer.javaTypeTag(1))
        assertEquals("number", ScevCCComputer.javaTypeTag(1L))
        assertEquals("number", ScevCCComputer.javaTypeTag(1.0))
        assertEquals("string", ScevCCComputer.javaTypeTag("x"))
        assertEquals("bytes", ScevCCComputer.javaTypeTag(ByteArray(1)))
        assertEquals("array", ScevCCComputer.javaTypeTag(listOf(1)))
        assertEquals("table", ScevCCComputer.javaTypeTag(mapOf("k" to 1)))
        assertTrue(ScevCCComputer.javaTypeTag(this).startsWith("other("))
    }
}
