/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcErrors
import lekkit.scev.rpc.RpcDispatcher
import lekkit.scev.rpc.RpcFrame
import lekkit.scev.core.rpc.RpcHandler
import lekkit.scev.core.rpc.RpcProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Dispatcher basics: unknown methods yield an error, typed handler
 * exceptions surface to the guest with their message, unexpected
 * runtime exceptions are caught and surfaced as a generic error.
 * Plus: suspending handlers complete through [RpcDispatcher.dispatch]
 * like any other — confirms the coroutine path works end-to-end.
 */
class RpcDispatcherTest {
    @Test fun `routes to handler and returns result`() = runTest {
        val d = RpcDispatcher()
        d.register("echo") { args -> args[0] }
        val req = RpcFrame.Request(1, "echo", listOf(MsgValue.of("hi")))
        val rsp = d.dispatch(req)
        assertEquals(1, rsp.id)
        assertNull(rsp.error)
        assertEquals("hi", rsp.result.asString())
    }

    @Test fun `unknown method yields error`() = runTest {
        val d = RpcDispatcher()
        val rsp = d.dispatch(RpcFrame.Request(7, "nope", emptyList()))
        assertEquals(7, rsp.id)
        assertNotNull(rsp.error)
        assertEquals(RpcErrors.NO_SUCH_METHOD, rsp.error!!.code)
        assertTrue(rsp.error!!.message.contains("unknown method"))
    }

    @Test fun `RpcException surfaces message and default code`() = runTest {
        val d = RpcDispatcher()
        d.register("fail") { _ -> throw RpcHandler.RpcException("specific cause") }
        val rsp = d.dispatch(RpcFrame.Request(42, "fail", emptyList()))
        assertEquals("specific cause", rsp.error?.message)
        assertEquals(RpcErrors.GENERIC, rsp.error?.code)
    }

    @Test fun `RpcException carries explicit code`() = runTest {
        val d = RpcDispatcher()
        d.register("fail") { _ ->
            throw RpcHandler.RpcException("bad input", RpcErrors.BAD_ARGS)
        }
        val rsp = d.dispatch(RpcFrame.Request(43, "fail", emptyList()))
        assertEquals(RpcErrors.BAD_ARGS, rsp.error?.code)
        assertEquals("bad input", rsp.error?.message)
    }

    @Test fun `runtime exception is internal_error and message is generic`() = runTest {
        val d = RpcDispatcher()
        d.register("boom") { _ -> throw IllegalStateException("SECRET") }
        val rsp = d.dispatch(RpcFrame.Request(0, "boom", emptyList()))
        assertEquals(RpcErrors.INTERNAL_ERROR, rsp.error?.code)
        assertEquals("internal error", rsp.error?.message)
        assertFalse(rsp.error!!.message.contains("SECRET"), "runtime exception detail must not leak")
    }

    @Test fun `suspending handler completes successfully`() = runTest {
        val d = RpcDispatcher()
        d.register("delayed") { _ ->
            delay(50)
            MsgValue.of("done")
        }
        val rsp = d.dispatch(RpcFrame.Request(5, "delayed", emptyList()))
        assertNull(rsp.error)
        assertEquals("done", rsp.result.asString())
    }

    @Test fun `suspending handler that throws surfaces RpcException`() = runTest {
        val d = RpcDispatcher()
        d.register("eventually_fails") { _ ->
            delay(10)
            throw RpcHandler.RpcException("after sleep")
        }
        val rsp = d.dispatch(RpcFrame.Request(9, "eventually_fails", emptyList()))
        assertEquals("after sleep", rsp.error?.message)
    }

    @Test fun `error map round-trips through encode and decode`() {
        val rsp = RpcFrame.error(7, RpcErrors.BAD_ARGS, "missing peer")
        val decoded = RpcProtocol.decode(RpcProtocol.encode(rsp))
        assertTrue(decoded is RpcFrame.Response)
        val r2 = decoded as RpcFrame.Response
        assertEquals(7, r2.id)
        assertEquals(RpcErrors.BAD_ARGS, r2.error?.code)
        assertEquals("missing peer", r2.error?.message)
    }

    @Test fun `protocol round-trip request`() {
        val req = RpcFrame.Request(
            123, RpcProtocol.METHOD_CALL,
            listOf(MsgValue.of("monitor_4"), MsgValue.of("write"), MsgValue.of("hello")),
        )
        val wire = RpcProtocol.encode(req)
        val decoded = RpcProtocol.decode(wire)
        assertTrue(decoded is RpcFrame.Request)
        val r2 = decoded as RpcFrame.Request
        assertEquals(req.id, r2.id)
        assertEquals(req.method, r2.method)
        assertEquals(3, r2.args.size)
    }

    @Test fun `protocol round-trip response`() {
        val ok = RpcFrame.ok(99, MsgValue.of(42L))
        val decoded = RpcProtocol.decode(RpcProtocol.encode(ok))
        assertTrue(decoded is RpcFrame.Response)
        val r2 = decoded as RpcFrame.Response
        assertEquals(99, r2.id)
        assertNull(r2.error)
        assertEquals(42L, r2.result.asInt())
    }

    @Test fun `protocol round-trip event`() {
        val evt = RpcFrame.event("redstone", listOf(MsgValue.of("left")))
        val decoded = RpcProtocol.decode(RpcProtocol.encode(evt))
        assertTrue(decoded is RpcFrame.Event)
        val e2 = decoded as RpcFrame.Event
        assertEquals("redstone", e2.name)
        assertEquals(1, e2.args.size)
    }
}
