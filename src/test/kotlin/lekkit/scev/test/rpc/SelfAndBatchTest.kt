/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc

import kotlinx.coroutines.test.runTest
import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcErrors
import lekkit.scev.core.rpc.RpcHandler
import lekkit.scev.core.rpc.RpcProtocol
import lekkit.scev.rpc.ChunkStore
import lekkit.scev.rpc.RpcDispatcher
import lekkit.scev.rpc.RpcFrame
import lekkit.scev.rpc.ScevRpcHandlers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SelfAndBatchTest {
    private val uuid: UUID = UUID.randomUUID()

    private fun freshDispatcher(): RpcDispatcher {
        val d = RpcDispatcher()
        ScevRpcHandlers.registerDefaults(d, uuid, ChunkStore(uuid))
        return d
    }

    /* ---------------- self ---------------- */

    @Test fun `self advertises protocol version and capabilities`() = runTest {
        val d = freshDispatcher()
        val rsp = d.dispatch(RpcFrame.Request(1, RpcProtocol.METHOD_SELF, emptyList()))
        assertNull(rsp.error)
        val m = rsp.result.asMap()
        assertEquals(
            ScevRpcHandlers.PROTOCOL_VERSION,
            (m[MsgValue.of("protocol_version")] as MsgValue.Int).value,
        )
        val caps = (m[MsgValue.of("capabilities")] as MsgValue.Map).value
        assertEquals(
            true,
            (caps[MsgValue.of("structured_errors")] as MsgValue.Bool).value,
        )
        assertEquals(
            true,
            (caps[MsgValue.of("chunked_transfer")] as MsgValue.Bool).value,
        )
    }

    @Test fun `self limits include frame_max_bytes and chunk caps`() = runTest {
        val d = freshDispatcher()
        val rsp = d.dispatch(RpcFrame.Request(1, RpcProtocol.METHOD_SELF, emptyList()))
        val limits = (rsp.result.asMap()[MsgValue.of("limits")] as MsgValue.Map).value
        // Just assert presence + positivity; concrete numbers are
        // implementation-controlled and shouldn't make the test
        // brittle when we tune them.
        for (key in listOf(
            "frame_max_bytes",
            "chunk_max_streams",
            "chunk_max_stream_bytes",
            "chunk_max_total_bytes",
            "chunk_ttl_ms",
        )) {
            val v = limits[MsgValue.of(key)]
            assertNotNull(v, "limits.$key present")
            assertTrue((v as MsgValue.Int).value > 0L, "limits.$key positive")
        }
    }

    /* ---------------- batch ---------------- */

    @Test fun `batch returns one envelope per item in input order`() = runTest {
        val d = freshDispatcher()
        d.register("echo") { args -> args.getOrElse(0) { MsgValue.NIL } }

        val items = listOf(
            batchItem("echo", listOf(MsgValue.of("a"))),
            batchItem("echo", listOf(MsgValue.of("b"))),
            batchItem("echo", listOf(MsgValue.of("c"))),
        )
        val rsp = d.dispatch(
            RpcFrame.Request(1, RpcProtocol.METHOD_BATCH, listOf(MsgValue.ofArray(items))),
        )
        assertNull(rsp.error)
        val results = rsp.result.asArray()
        assertEquals(3, results.size)
        for ((idx, expected) in listOf("a", "b", "c").withIndex()) {
            val pair = results[idx].asArray()
            assertEquals(2, pair.size)
            assertTrue(pair[0].isNil, "item $idx success → nil err")
            assertEquals(expected, pair[1].asString())
        }
    }

    @Test fun `batch surfaces per-item errors with code and message`() = runTest {
        val d = freshDispatcher()
        d.register("ok") { _ -> MsgValue.of("fine") }
        d.register("nope") { _ ->
            throw RpcHandler.RpcException("intentional", RpcErrors.BAD_ARGS)
        }

        val rsp = d.dispatch(
            RpcFrame.Request(
                1, RpcProtocol.METHOD_BATCH,
                listOf(
                    MsgValue.ofArray(
                        listOf(
                            batchItem("ok", emptyList()),
                            batchItem("nope", emptyList()),
                            batchItem("ok", emptyList()),
                        ),
                    ),
                ),
            ),
        )
        val results = rsp.result.asArray()
        assertEquals(3, results.size)

        // success
        assertTrue(results[0].asArray()[0].isNil)
        assertEquals("fine", results[0].asArray()[1].asString())

        // error — nested {code, message} map
        val errMap = results[1].asArray()[0].asMap()
        assertEquals(RpcErrors.BAD_ARGS, (errMap[MsgValue.of("code")] as MsgValue.Str).value)
        assertEquals("intentional", (errMap[MsgValue.of("message")] as MsgValue.Str).value)
        assertTrue(results[1].asArray()[1].isNil, "errored item has nil result")

        // third item still runs (no stop_on_error)
        assertTrue(results[2].asArray()[0].isNil)
        assertEquals("fine", results[2].asArray()[1].asString())
    }

    @Test fun `batch with stop_on_error halts after first failure`() = runTest {
        val d = freshDispatcher()
        d.register("ok") { _ -> MsgValue.of("fine") }
        d.register("nope") { _ ->
            throw RpcHandler.RpcException("intentional", RpcErrors.BAD_ARGS)
        }

        val opts = MsgValue.ofMap(
            linkedMapOf(MsgValue.of("stop_on_error") to MsgValue.of(true)),
        )
        val rsp = d.dispatch(
            RpcFrame.Request(
                1, RpcProtocol.METHOD_BATCH,
                listOf(
                    MsgValue.ofArray(
                        listOf(
                            batchItem("ok", emptyList()),
                            batchItem("nope", emptyList()),
                            batchItem("ok", emptyList()),
                            batchItem("ok", emptyList()),
                        ),
                    ),
                    opts,
                ),
            ),
        )
        val results = rsp.result.asArray()
        assertEquals(4, results.size)

        // Items after the failure carry the SKIPPED code so the guest
        // can tell skipped slots apart from real failures.
        for (idx in 2..3) {
            val errMap = results[idx].asArray()[0].asMap()
            assertEquals(
                RpcErrors.SKIPPED,
                (errMap[MsgValue.of("code")] as MsgValue.Str).value,
                "idx $idx is SKIPPED",
            )
        }
    }

    @Test fun `batch refuses nested batch as unsupported`() = runTest {
        val d = freshDispatcher()
        val inner = MsgValue.ofArray(emptyList())
        val rsp = d.dispatch(
            RpcFrame.Request(
                1, RpcProtocol.METHOD_BATCH,
                listOf(
                    MsgValue.ofArray(
                        listOf(batchItem(RpcProtocol.METHOD_BATCH, listOf(inner))),
                    ),
                ),
            ),
        )
        val errMap = rsp.result.asArray()[0].asArray()[0].asMap()
        assertEquals(RpcErrors.UNSUPPORTED, (errMap[MsgValue.of("code")] as MsgValue.Str).value)
    }

    @Test fun `batch reports bad_args for malformed item`() = runTest {
        val d = freshDispatcher()
        // Item missing the [method, args] array shape.
        val rsp = d.dispatch(
            RpcFrame.Request(
                1, RpcProtocol.METHOD_BATCH,
                listOf(MsgValue.ofArray(listOf(MsgValue.of("not an array")))),
            ),
        )
        val errMap = rsp.result.asArray()[0].asArray()[0].asMap()
        assertEquals(RpcErrors.BAD_ARGS, (errMap[MsgValue.of("code")] as MsgValue.Str).value)
    }

    @Test fun `batch unknown method per-item is no_such_method`() = runTest {
        val d = freshDispatcher()
        val rsp = d.dispatch(
            RpcFrame.Request(
                1, RpcProtocol.METHOD_BATCH,
                listOf(
                    MsgValue.ofArray(
                        listOf(batchItem("does_not_exist", emptyList())),
                    ),
                ),
            ),
        )
        val errMap = rsp.result.asArray()[0].asArray()[0].asMap()
        assertEquals(
            RpcErrors.NO_SUCH_METHOD,
            (errMap[MsgValue.of("code")] as MsgValue.Str).value,
        )
    }

    private fun batchItem(method: String, args: List<MsgValue>): MsgValue =
        MsgValue.ofArray(listOf(MsgValue.of(method), MsgValue.ofArray(args)))
}
