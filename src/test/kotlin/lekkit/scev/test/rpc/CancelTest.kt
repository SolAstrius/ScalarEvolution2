/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc

import kotlinx.coroutines.CompletableDeferred
import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcProtocol
import lekkit.scev.rpc.Cobs
import lekkit.scev.rpc.FrameStream
import lekkit.scev.rpc.RpcFrame
import lekkit.scev.rpc.ScevRpcManager
import lekkit.scev.test.machine.FakeMachineBackend
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * In-flight request cancellation through ScevRpcManager. Validates
 * that:
 *  - cancel(id) interrupts a still-running handler and produces no
 *    Response on the wire,
 *  - cancel(id) for an unknown id returns {cancelled: false},
 *  - the cancel handler itself is reachable + survives unrelated
 *    in-flight handlers.
 */
class CancelTest {
    private val uuid: UUID = UUID.randomUUID()

    @AfterEach fun cleanup() { ScevRpcManager.unregister(uuid) }

    @Test fun `cancel interrupts running handler and emits no response`() {
        val serial = FakeMachineBackend.FakeSerial()
        val mgr = ScevRpcManager.create(uuid, serial)

        // Handler that suspends until released. We never release it —
        // the test cancels it instead.
        val gate = CompletableDeferred<Unit>()
        mgr.dispatcher().register("park") { _ ->
            gate.await()
            MsgValue.of("never reached")
        }

        // Kick off the long-running request.
        send(serial, RpcFrame.Request(7, "park", emptyList()))
        mgr.tick()
        // No response yet — handler is parked.
        assertEquals(0, serial.consumeRx().size, "no response while handler is parked")
        assertTrue(7L in mgr.inflightRequestIds(), "id 7 should be tracked as in-flight")

        // Cancel it.
        send(serial, RpcFrame.Request(8, RpcProtocol.METHOD_CANCEL, listOf(MsgValue.of(7L))))
        mgr.tick()
        val replies = recvFrames(serial)
        // We should see exactly one Response — the cancel reply for id=8.
        // The original id=7 produces NO response.
        assertEquals(1, replies.size, "only the cancel reply, not the original request")
        val rsp = replies[0] as RpcFrame.Response
        assertEquals(8L, rsp.id)
        assertNull(rsp.error)
        val m = rsp.result.asMap()
        assertEquals(true, (m[MsgValue.of("cancelled")] as MsgValue.Bool).value)

        // After tick processes the cancellation, the handler should
        // be gone from inflight.
        // (Cancellation propagates through the coroutine; the finally
        // in handleIncoming removes the entry.)
        // Process any pending coroutine continuations.
        mgr.tick()
        assertFalse(7L in mgr.inflightRequestIds(), "cancelled handler removed from inflight")

        // Don't leave the handler parked — it's already cancelled,
        // but freeing the latch keeps things tidy.
        gate.complete(Unit)
    }

    @Test fun `cancel for unknown id is a no-op with cancelled=false`() {
        val serial = FakeMachineBackend.FakeSerial()
        val mgr = ScevRpcManager.create(uuid, serial)

        send(serial, RpcFrame.Request(1, RpcProtocol.METHOD_CANCEL, listOf(MsgValue.of(9999L))))
        mgr.tick()
        val rsp = recvFrames(serial).single() as RpcFrame.Response
        assertEquals(1L, rsp.id)
        assertNull(rsp.error)
        val m = rsp.result.asMap()
        assertEquals(false, (m[MsgValue.of("cancelled")] as MsgValue.Bool).value)
    }

    @Test fun `cancel with bad args returns bad_args error`() {
        val serial = FakeMachineBackend.FakeSerial()
        val mgr = ScevRpcManager.create(uuid, serial)

        // No id passed.
        send(serial, RpcFrame.Request(1, RpcProtocol.METHOD_CANCEL, emptyList()))
        mgr.tick()
        val rsp = recvFrames(serial).single() as RpcFrame.Response
        assertNotNull(rsp.error)
        assertEquals(
            lekkit.scev.core.rpc.RpcErrors.BAD_ARGS,
            rsp.error?.code,
        )
    }

    /* helpers */

    private fun send(serial: FakeMachineBackend.FakeSerial, frame: RpcFrame) {
        val payload = RpcProtocol.encode(frame)
        val out = ByteArray(Cobs.maxEncodedSize(payload.size))
        val n = Cobs.encode(payload, 0, payload.size, out, 0)
        val cobsed = ByteArray(n)
        System.arraycopy(out, 0, cobsed, 0, n)
        serial.produceTx(cobsed)
    }

    private fun recvFrames(serial: FakeMachineBackend.FakeSerial): List<RpcFrame> {
        val all = ByteArrayOutputStream()
        while (true) {
            val rx = serial.consumeRx()
            if (rx.isEmpty()) break
            all.write(rx)
        }
        val raw = all.toByteArray()
        if (raw.isEmpty()) return emptyList()
        val stream = FrameStream(ScevRpcManager.MAX_FRAME_BYTES)
        return stream.feed(raw, 0, raw.size).mapNotNull { RpcProtocol.decode(it) }
    }
}
