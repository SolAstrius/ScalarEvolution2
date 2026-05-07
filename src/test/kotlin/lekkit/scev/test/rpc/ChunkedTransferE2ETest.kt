/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc

import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcProtocol
import lekkit.scev.rpc.Cobs
import lekkit.scev.rpc.FrameStream
import lekkit.scev.rpc.RpcFrame
import lekkit.scev.rpc.ScevRpcManager
import lekkit.scev.test.machine.FakeMachineBackend
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * End-to-end through [ScevRpcManager]: a handler that produces a
 * payload bigger than the wire cap should land as a TAG_CHUNKED
 * marker, with the original Response retrievable via repeated
 * `read_chunk` calls. Lives in Kotlin because [lekkit.scev.core.rpc.RpcHandler]
 * is a `suspend fun interface` and Java can't construct one with a
 * lambda.
 */
class ChunkedTransferE2ETest {
    private val uuid = UUID.randomUUID()

    @AfterEach fun cleanup() { ScevRpcManager.unregister(uuid) }

    @Test fun `oversized response is chunked and reassembles to original`() {
        val serial = FakeMachineBackend.FakeSerial()
        val mgr = ScevRpcManager.create(uuid, serial)

        // 100 KiB payload — well over the 64 KiB cap.
        val giant = ByteArray(100 * 1024) { (it and 0xFF).toByte() }
        mgr.dispatcher().register("big") { _ -> MsgValue.of(giant) }

        // Send the request.
        send(serial, RpcFrame.Request(11, "big", emptyList()))
        mgr.tick()

        // First reply: a TAG_CHUNKED marker (the response was too big).
        val markers = recvFrames(serial)
        assertEquals(1, markers.size, "exactly one marker frame expected")
        val marker = markers[0]
        assertTrue(marker is RpcFrame.Chunked, "first reply should be Chunked, got $marker")
        marker as RpcFrame.Chunked
        assertEquals(11L, marker.responseId)
        assertTrue(
            marker.totalSize > giant.size,
            "totalSize ${marker.totalSize} should exceed payload (msgpack overhead)",
        )

        // Drain the stream in 32 KiB slices.
        val assembled = ByteArrayOutputStream()
        var offset = 0L
        var nextId = 100L
        while (offset < marker.totalSize) {
            val want = minOf(32L * 1024L, marker.totalSize - offset)
            send(
                serial,
                RpcFrame.Request(
                    nextId++,
                    RpcProtocol.METHOD_READ_CHUNK,
                    listOf(
                        MsgValue.of(marker.streamId),
                        MsgValue.of(offset),
                        MsgValue.of(want),
                    ),
                ),
            )
            mgr.tick()
            val replies = recvFrames(serial)
            assertEquals(1, replies.size)
            val rsp = replies[0]
            assertTrue(rsp is RpcFrame.Response)
            rsp as RpcFrame.Response
            assertNull(rsp.error, "read_chunk should succeed: ${rsp.error?.message}")
            val slice = rsp.result.asBytes()
            assertTrue(slice.isNotEmpty() && slice.size <= want)
            assembled.write(slice)
            offset += slice.size
        }
        assertEquals(marker.totalSize, assembled.size().toLong())

        // The assembled bytes are exactly the original Response —
        // decode them locally.
        val original = RpcProtocol.decode(assembled.toByteArray())
        assertTrue(original is RpcFrame.Response)
        original as RpcFrame.Response
        assertEquals(11L, original.id)
        assertNull(original.error, "reassembled response is the original (success)")
        assertArrayEquals(giant, original.result.asBytes(), "payload survives chunking")
    }

    @Test fun `discard_chunk drops a stream and subsequent reads error`() {
        val serial = FakeMachineBackend.FakeSerial()
        val mgr = ScevRpcManager.create(uuid, serial)

        val giant = ByteArray(100 * 1024) { (it and 0xFF).toByte() }
        mgr.dispatcher().register("big") { _ -> MsgValue.of(giant) }

        send(serial, RpcFrame.Request(1, "big", emptyList()))
        mgr.tick()
        val marker = recvFrames(serial).single() as RpcFrame.Chunked

        // Discard before draining.
        send(
            serial,
            RpcFrame.Request(
                2,
                RpcProtocol.METHOD_DISCARD_CHUNK,
                listOf(MsgValue.of(marker.streamId)),
            ),
        )
        mgr.tick()
        val discardRsp = recvFrames(serial).single() as RpcFrame.Response
        assertNull(discardRsp.error)
        assertEquals(true, discardRsp.result.asBool(), "discard reports presence on first call")

        // Now read_chunk on the same id should error.
        send(
            serial,
            RpcFrame.Request(
                3,
                RpcProtocol.METHOD_READ_CHUNK,
                listOf(MsgValue.of(marker.streamId), MsgValue.of(0L), MsgValue.of(64L)),
            ),
        )
        mgr.tick()
        val readRsp = recvFrames(serial).single() as RpcFrame.Response
        assertEquals(lekkit.scev.core.rpc.RpcErrors.NO_SUCH_PEER, readRsp.error?.code)
    }

    /* ------------------ helpers ------------------ */

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
