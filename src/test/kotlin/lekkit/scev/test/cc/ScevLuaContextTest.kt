/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.cc

import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.api.lua.LuaTask
import lekkit.scev.compat.cc.ScevLuaContext
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the synchronous behavior of [ScevLuaContext]: tasks fire on the
 * caller thread, ids are monotonic, exceptions propagate.
 */
class ScevLuaContextTest {
    @Test fun `issueMainThreadTask runs synchronously`() {
        val ctx = ScevLuaContext()
        var ran = false
        val task = LuaTask { ran = true; null }
        assertDoesNotThrow { ctx.issueMainThreadTask(task) }
        assertTrue(ran, "task should have executed before issueMainThreadTask returned")
    }

    @Test fun `issueMainThreadTask ids are monotonic`() {
        val ctx = ScevLuaContext()
        val noop = LuaTask { null }
        val a = ctx.issueMainThreadTask(noop)
        val b = ctx.issueMainThreadTask(noop)
        val c = ctx.issueMainThreadTask(noop)
        assertTrue(b > a)
        assertTrue(c > b)
    }

    @Test fun `executeMainThreadTask wraps result as MethodResult`() {
        val ctx = ScevLuaContext()
        val task = LuaTask { arrayOf<Any?>(42L, "hello") }
        val r = ctx.executeMainThreadTask(task)
        assertArrayEquals(arrayOf<Any?>(42L, "hello"), r.result)
        assertNull(r.callback, "should not yield")
    }

    @Test fun `executeMainThreadTask maps null to empty`() {
        val ctx = ScevLuaContext()
        val r = ctx.executeMainThreadTask { null }
        // MethodResult.of() returns the shared empty singleton whose
        // getResult is null; that's how the caller infers "no values".
        assertNull(r.result)
        assertNull(r.callback)
    }

    @Test fun `LuaException from task propagates`() {
        val ctx = ScevLuaContext()
        val boom = LuaTask { throw LuaException("nope") }
        val thrown = assertThrows(LuaException::class.java) { ctx.issueMainThreadTask(boom) }
        assertEquals("nope", thrown.message)
    }
}
