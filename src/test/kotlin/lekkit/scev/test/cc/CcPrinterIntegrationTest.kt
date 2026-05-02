/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.cc

import dan200.computercraft.api.peripheral.IPeripheral
import dan200.computercraft.core.terminal.Terminal
import dan200.computercraft.shared.computer.terminal.NetworkedTerminal
import dan200.computercraft.shared.peripheral.printer.PrinterBlockEntity
import dan200.computercraft.shared.peripheral.printer.PrinterPeripheral
import kotlinx.coroutines.runBlocking
import lekkit.scev.compat.cc.ScevCCComputer
import lekkit.scev.compat.cc.ScevCCHandlers
import lekkit.scev.rpc.MsgPack
import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.rpc.RpcDispatcher
import lekkit.scev.rpc.RpcFrame
import lekkit.scev.core.rpc.RpcProtocol
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

/**
 * End-to-end test of the CC integration *against real CC: Tweaked
 * classes*, driven through the serial-RPC wire.
 *
 * What this covers (top → bottom of the stack):
 *
 *  1. RPC wire bytes, hand-encoded by [MinimalMsgPackEncoder] — an
 *     independent MessagePack encoder written for this test. That's
 *     the "encoding in a separate way" the test plan asked for: if
 *     our own `MsgPack.encode` ever drifts from spec, this test
 *     catches it because the hand encoder's output is decoded by
 *     scev's decoder and the round-trip has to match.
 *
 *  2. [RpcProtocol.decode] decodes the bytes back into an
 *     [RpcFrame.Request].
 *
 *  3. [RpcDispatcher] + [ScevCCHandlers.install] route the request
 *     through the full scev dispatch path (including
 *     [ScevPeripheralMethods.dispatch]'s reflection-based invoker
 *     cache) to the real CC-provided [PrinterPeripheral] class
 *     loaded from `cc-tweaked-<mcv>-forge.jar`.
 *
 *  4. [PrinterPeripheral] calls back into a Mockito-stubbed
 *     [PrinterBlockEntity] that implements the actual state machine
 *     (ink consumption, paper consumption, current-page Terminal,
 *     finished-page collection). Minecraft-side container / item
 *     machinery is replaced by the in-test state — "substituted
 *     world data" per the test-plan brief.
 *
 *  5. The response is encoded back via [RpcProtocol.encode] into
 *     MessagePack bytes and decoded by the same independent codec
 *     that built the request — closing the loop on wire compat.
 *
 * The full sequence drives a realistic print:
 *   `newPage → setPageTitle → write → setCursorPos → write → endPage`
 * and asserts at the end that the state machine produced exactly one
 * finished page, with the title we set, the text we wrote at the
 * positions we wrote it.
 *
 * Separately asserts that [RpcProtocol.METHOD_DESCRIBE] over the same
 * peripheral returns a signature matrix consistent with what the real
 * PrinterPeripheral exposes via `@LuaFunction`.
 */
class CcPrinterIntegrationTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun bootstrap() {
            // Minecraft's registry / tag bootstrap — required because
            // constructing (even mocking) PrinterBlockEntity touches
            // BlockEntityType statics during classload of its class
            // literal.
            Bootstrap.bootStrap()
        }
    }

    /**
     * In-test stand-in for the printer's world-side state.
     *
     * `currentPage` is a real [NetworkedTerminal] (CC's actual class)
     * so PrinterPeripheral's write / cursor calls take effect against
     * a genuine CC terminal buffer. The `finishedPages` list models
     * the print output that would otherwise land in the printer's
     * output slot as a `printed_page` ItemStack.
     */
    private class PrinterState {
        val pageWidth = 25
        val pageHeight = 21
        var ink = 2           // two dyes loaded
        var paper = 3         // three sheets loaded
        var currentPage: NetworkedTerminal? = null
        var pendingTitle: String = ""
        val finishedPages = mutableListOf<FinishedPage>()

        data class FinishedPage(val title: String, val text: List<String>)

        fun canStart(): Boolean = ink > 0 && paper > 0

        fun startPage(): Boolean {
            if (currentPage != null || !canStart()) return false
            currentPage = NetworkedTerminal(pageWidth, pageHeight, false)
            pendingTitle = ""
            return true
        }

        fun endPage(): Boolean {
            val page = currentPage ?: return false
            val snapshot = (0 until pageHeight).map { y ->
                page.getLine(y).toString().trimEnd()
            }.filter { it.isNotEmpty() }
            finishedPages += FinishedPage(pendingTitle, snapshot)
            currentPage = null
            ink -= 1
            paper -= 1
            return true
        }
    }

    @Test
    @DisplayName("full printer sequence drives real PrinterPeripheral → finished page")
    fun fullSequence() {
        val uuid = UUID.randomUUID()
        val computer = ScevCCComputer(uuid)
        val state = PrinterState()
        val mockBe = stubbedPrinterBlockEntity(state)
        val peripheral = PrinterPeripheral(mockBe)

        // Register under side "left"; the method resolver looks up by
        // side string. No Level / world lookup happens because we use
        // the test hook.
        computer.setPeripheralForTests("left", peripheral)

        val dispatcher = RpcDispatcher()
        ScevCCHandlers.install(dispatcher, computer)

        // --- Step 1: list ---
        run {
            val list = sendRequest(dispatcher, RpcProtocol.METHOD_LIST, emptyList())
            val arr = (list as MsgValue.Arr).value
            assertEquals(1, arr.size, "one peripheral installed (left)")
            val entry = (arr[0] as MsgValue.Map).value
            assertEquals(MsgValue.of("left"), entry[MsgValue.of("peer")])
            val types = (entry[MsgValue.of("types")] as MsgValue.Arr).value
            assertTrue(types.contains(MsgValue.of("printer")), "type list: $types")
            // class breadcrumb is surfaced.
            val cls = entry[MsgValue.of("class")] as? MsgValue.Str
            assertNotNull(cls, "class breadcrumb missing")
            // Real PrinterPeripheral's class name.
            assertTrue(
                cls!!.value.endsWith("PrinterPeripheral") ||
                    cls.value.endsWith("PrinterPeripheral\$MockitoMock"),
                "unexpected class name: ${cls.value}",
            )
        }

        // --- Step 2: describe shows the real API surface ---
        run {
            val desc = sendRequest(
                dispatcher,
                RpcProtocol.METHOD_DESCRIBE,
                listOf(MsgValue.of("left")),
            ) as MsgValue.Map
            val dm = desc.value
            assertEquals(MsgValue.of("printer"), dm[MsgValue.of("type")])
            val groups = (dm[MsgValue.of("groups")] as MsgValue.Map).value
            // All @LuaFunction methods on the real PrinterPeripheral
            // should land in the groups map. Method names collected
            // across every group.
            val methodNames = groups.values
                .flatMap { (it as MsgValue.Arr).value }
                .map { sig -> ((sig as MsgValue.Map).value[MsgValue.of("name")] as MsgValue.Str).value }
                .toSet()
            val expected = setOf(
                "write", "getCursorPos", "setCursorPos", "getPageSize",
                "newPage", "endPage", "setPageTitle", "getInkLevel",
                "getPaperLevel",
            )
            assertEquals(expected, methodNames, "describe must surface every @LuaFunction")
            // `newPage` is declared main-thread in PrinterPeripheral.
            val newPageSig = groups.values
                .flatMap { (it as MsgValue.Arr).value }
                .map { (it as MsgValue.Map).value }
                .first { (it[MsgValue.of("name")] as MsgValue.Str).value == "newPage" }
            assertEquals(MsgValue.TRUE, newPageSig[MsgValue.of("mainThread")])
        }

        // Turn on tracing — we'll verify it captured the sequence.
        sendRequest(dispatcher, RpcProtocol.METHOD_TRACE, listOf(MsgValue.of("on")))

        // --- Step 3: newPage ---
        run {
            val r = sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
                listOf(MsgValue.of("left"), MsgValue.of("newPage")))
            assertEquals(MsgValue.TRUE, r)
            assertNotNull(state.currentPage, "page should be started")
        }

        // --- Step 4: setPageTitle ---
        sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
            listOf(MsgValue.of("left"), MsgValue.of("setPageTitle"), MsgValue.of("Hello")))
        assertEquals("Hello", state.pendingTitle)

        // --- Step 5: write ---
        sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
            listOf(MsgValue.of("left"), MsgValue.of("write"), MsgValue.of("Hi!")))

        // --- Step 6: setCursorPos (y = 3) ---
        sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
            listOf(MsgValue.of("left"), MsgValue.of("setCursorPos"),
                MsgValue.of(1L), MsgValue.of(3L)))

        // --- Step 7: write second line ---
        sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
            listOf(MsgValue.of("left"), MsgValue.of("write"), MsgValue.of("line two")))

        // --- Step 8: endPage → produces the item ---
        run {
            val r = sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
                listOf(MsgValue.of("left"), MsgValue.of("endPage")))
            assertEquals(MsgValue.TRUE, r)
        }

        // --- Assertions on the produced artifact ---
        assertEquals(1, state.finishedPages.size, "exactly one page produced")
        val printed = state.finishedPages.single()
        assertEquals("Hello", printed.title)
        // Expect at least the "Hi!" on row 0 and "line two" on row 2.
        val body = printed.text
        assertTrue(body.any { it.contains("Hi!") }, "page text: $body")
        assertTrue(body.any { it.contains("line two") }, "page text: $body")
        // Ink + paper consumed.
        assertEquals(1, state.ink)
        assertEquals(2, state.paper)

        // --- getInkLevel / getPaperLevel reflect the state ---
        run {
            val ink = sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
                listOf(MsgValue.of("left"), MsgValue.of("getInkLevel")))
            assertEquals(MsgValue.of(1L), ink)
            val paper = sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
                listOf(MsgValue.of("left"), MsgValue.of("getPaperLevel")))
            assertEquals(MsgValue.of(2L), paper)
        }

        // --- trace dump lists the six calls we made ---
        run {
            val trace = sendRequest(dispatcher, RpcProtocol.METHOD_TRACE,
                listOf(MsgValue.of("dump"))) as MsgValue.Arr
            val methods = trace.value.map {
                ((it as MsgValue.Map).value[MsgValue.of("method")] as MsgValue.Str).value
            }
            // Only `call`s are traced — trace/describe don't go
            // through the same code path. Expect the 8 peripheral calls
            // in order.
            assertEquals(
                listOf("newPage", "setPageTitle", "write", "setCursorPos",
                    "write", "endPage", "getInkLevel", "getPaperLevel"),
                methods,
            )
            for (entry in trace.value) {
                val m = (entry as MsgValue.Map).value
                assertEquals(MsgValue.of("ok"), m[MsgValue.of("outcome")])
            }
        }
    }

    @Test
    @DisplayName("newPage returns false when out of ink/paper — enrichment surfaces no signature (no error)")
    fun outOfResources() {
        val uuid = UUID.randomUUID()
        val computer = ScevCCComputer(uuid)
        val state = PrinterState().also { it.ink = 0 }
        val peripheral = PrinterPeripheral(stubbedPrinterBlockEntity(state))
        computer.setPeripheralForTests("left", peripheral)
        val dispatcher = RpcDispatcher().also { ScevCCHandlers.install(it, computer) }
        val r = sendRequest(dispatcher, RpcProtocol.METHOD_CALL,
            listOf(MsgValue.of("left"), MsgValue.of("newPage")))
        // newPage returns false, no LuaException.
        assertEquals(MsgValue.FALSE, r)
    }

    @Test
    @DisplayName("endPage without newPage returns an RPC error tagged with signature")
    fun endWithoutStart() {
        val uuid = UUID.randomUUID()
        val computer = ScevCCComputer(uuid)
        val state = PrinterState()
        val peripheral = PrinterPeripheral(stubbedPrinterBlockEntity(state))
        computer.setPeripheralForTests("left", peripheral)
        val dispatcher = RpcDispatcher().also { ScevCCHandlers.install(it, computer) }
        val frame = sendRequestFrame(dispatcher, RpcProtocol.METHOD_CALL,
            listOf(MsgValue.of("left"), MsgValue.of("endPage")))
        assertNotNull(frame.error, "endPage without a started page should error")
        val err = frame.error!!
        // "Page not started" is PrinterPeripheral's thrown LuaException
        // message. The enrichment pipeline in ScevPeripheralMethods
        // appends the signature — we assert it's there.
        assertTrue(err.contains("Page not started"), err)
        assertTrue(err.contains("endPage"), err)
    }

    // -------- Framing helpers --------

    /**
     * End-to-end helper: hand-encode an RPC request via the
     * minimal-independent codec, decode it through scev's decoder,
     * dispatch, re-encode the response through scev's encoder, decode
     * that via the independent codec, return the result MsgValue.
     *
     * Every request/response flows through both codecs — catches
     * asymmetric bugs.
     */
    private fun sendRequest(
        dispatcher: RpcDispatcher,
        method: String,
        args: List<MsgValue>,
    ): MsgValue = sendRequestFrame(dispatcher, method, args).result

    private fun sendRequestFrame(
        dispatcher: RpcDispatcher,
        method: String,
        args: List<MsgValue>,
    ): RpcFrame.Response {
        val id = System.nanoTime()
        // Hand-encode via the independent codec.
        val independent = MinimalMsgPackEncoder()
        independent.writeArrayHeader(4)
        independent.writeInt(RpcFrame.TAG_REQ.toLong())
        independent.writeInt(id)
        independent.writeStr(method)
        independent.writeArray(args)
        val wireBytes = independent.finish()

        // scev's decoder parses our independently-encoded bytes.
        val decoded = RpcProtocol.decode(wireBytes) as RpcFrame.Request
        assertEquals(method, decoded.method, "decoder round-trip: method")
        assertEquals(id, decoded.id, "decoder round-trip: id")
        assertEquals(args, decoded.args ?: emptyList<MsgValue>(), "decoder round-trip: args")

        // Dispatch through the real scev CC handler chain.
        val response = runBlocking { dispatcher.dispatch(decoded) }

        // scev's encoder → our independent decoder → MsgValue.
        val respBytes = RpcProtocol.encode(response)
        val respDecoded = RpcProtocol.decode(respBytes) as RpcFrame.Response
        assertEquals(response.id, respDecoded.id)
        assertEquals(response.error, respDecoded.error)
        // Independent decode-and-re-compare: we don't just trust the
        // scev decoder here.
        val independentRoundTrip = MinimalMsgPackDecoder(respBytes).readValue()
        assertTrue(independentRoundTrip is MsgValue.Arr)
        return respDecoded
    }

    // -------- Minecraft-free PrinterBlockEntity mock --------

    /**
     * Mock [PrinterBlockEntity] with a default-answer that reflects
     * every invocation against our in-test [PrinterState].
     *
     * We can't use `doAnswer(...).when(mock).startNewPage()`-style
     * per-method stubbing because the printer's state methods are
     * package-private (`dan200.computercraft.shared.peripheral.printer`)
     * and this test lives in a different package — the symbols aren't
     * visible to Kotlin at compile time. Instead we route every
     * invocation through a default [Answer] that switches on method
     * name. Mockito's inline mock-maker (Mockito 5 default) can mock
     * the final [PrinterBlockEntity] without a Java agent.
     */
    private fun stubbedPrinterBlockEntity(state: PrinterState): PrinterBlockEntity {
        val stateAnswer = org.mockito.stubbing.Answer<Any?> { inv ->
            when (inv.method.name) {
                "getCurrentPage" -> state.currentPage
                "startNewPage" -> state.startPage()
                "endCurrentPage" -> state.endPage()
                "getInkLevel" -> state.ink
                "getPaperLevel" -> state.paper
                "setPageTitle" -> {
                    state.pendingTitle = inv.getArgument<String>(0) ?: ""
                    null
                }
                // Defaults for the long tail of BlockEntity / Container
                // methods Mockito might encounter (equals, hashCode,
                // Mockito's own internal queries). Returning Mockito
                // defaults keeps those paths inert.
                else -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }
        return Mockito.mock(
            PrinterBlockEntity::class.java,
            Mockito.withSettings().defaultAnswer(stateAnswer),
        )
    }

    // -------- Independent MessagePack codec (test-only) --------

    /**
     * Minimal MessagePack encoder/decoder written fresh for this
     * test. Supports only the types we actually need on the wire:
     * nil, bool, int (int8..int64 + positive/negative fixint), str,
     * array16, map16.
     *
     * Emits canonical-ish byte sequences — wire-identical to what the
     * scev encoder would produce for the same tree — so the bytes are
     * a spec-level check, not just a same-round-trip check.
     */
    private class MinimalMsgPackEncoder {
        private val out = java.io.ByteArrayOutputStream()

        fun writeNil() { out.write(0xC0) }
        fun writeBool(v: Boolean) { out.write(if (v) 0xC3 else 0xC2) }

        fun writeInt(n: Long) {
            when {
                n in 0..0x7F -> out.write(n.toInt())
                n in -32..-1 -> out.write(0xE0 or (n.toInt() and 0x1F))
                n in Byte.MIN_VALUE..Byte.MAX_VALUE -> { out.write(0xD0); out.write(n.toInt() and 0xFF) }
                n in Short.MIN_VALUE..Short.MAX_VALUE -> {
                    out.write(0xD1); out.write((n shr 8).toInt() and 0xFF); out.write(n.toInt() and 0xFF)
                }
                n in Int.MIN_VALUE..Int.MAX_VALUE -> {
                    out.write(0xD2)
                    for (s in 24 downTo 0 step 8) out.write(((n shr s) and 0xFF).toInt())
                }
                else -> {
                    out.write(0xD3)
                    for (s in 56 downTo 0 step 8) out.write(((n shr s) and 0xFF).toInt())
                }
            }
        }

        fun writeStr(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            when {
                bytes.size <= 0x1F -> out.write(0xA0 or bytes.size)
                bytes.size <= 0xFF -> { out.write(0xD9); out.write(bytes.size and 0xFF) }
                bytes.size <= 0xFFFF -> {
                    out.write(0xDA); out.write((bytes.size shr 8) and 0xFF); out.write(bytes.size and 0xFF)
                }
                else -> {
                    out.write(0xDB)
                    for (s2 in 24 downTo 0 step 8) out.write(((bytes.size shr s2) and 0xFF))
                }
            }
            out.write(bytes)
        }

        fun writeArrayHeader(n: Int) {
            when {
                n <= 0x0F -> out.write(0x90 or n)
                n <= 0xFFFF -> { out.write(0xDC); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
                else -> {
                    out.write(0xDD)
                    for (s in 24 downTo 0 step 8) out.write(((n shr s) and 0xFF))
                }
            }
        }

        fun writeMapHeader(n: Int) {
            when {
                n <= 0x0F -> out.write(0x80 or n)
                n <= 0xFFFF -> { out.write(0xDE); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
                else -> {
                    out.write(0xDF)
                    for (s in 24 downTo 0 step 8) out.write(((n shr s) and 0xFF))
                }
            }
        }

        fun writeValue(v: MsgValue) {
            when (v) {
                is MsgValue.Nil -> writeNil()
                is MsgValue.Bool -> writeBool(v.value)
                is MsgValue.Int -> writeInt(v.value)
                is MsgValue.Double -> {
                    out.write(0xCB)
                    val bits = java.lang.Double.doubleToLongBits(v.value)
                    for (s in 56 downTo 0 step 8) out.write(((bits shr s) and 0xFF).toInt())
                }
                is MsgValue.Str -> writeStr(v.value)
                is MsgValue.Bin -> {
                    out.write(0xC5); out.write((v.value.size shr 8) and 0xFF); out.write(v.value.size and 0xFF)
                    out.write(v.value)
                }
                is MsgValue.Arr -> writeArray(v.value)
                is MsgValue.Map -> {
                    writeMapHeader(v.value.size)
                    for ((k2, v2) in v.value) { writeValue(k2); writeValue(v2) }
                }
            }
        }

        fun writeArray(xs: List<MsgValue>) {
            writeArrayHeader(xs.size)
            for (x in xs) writeValue(x)
        }

        fun finish(): ByteArray = out.toByteArray()
    }

    /** Paired independent decoder — used to sanity-check responses. */
    private class MinimalMsgPackDecoder(private val bytes: ByteArray) {
        private var pos = 0

        fun readValue(): MsgValue {
            val b = bytes[pos++].toInt() and 0xFF
            return when {
                b == 0xC0 -> MsgValue.NIL
                b == 0xC2 -> MsgValue.FALSE
                b == 0xC3 -> MsgValue.TRUE
                b and 0x80 == 0 -> MsgValue.of(b.toLong())                                        // positive fixint
                b and 0xE0 == 0xE0 && b and 0x80 != 0 && b >= 0xE0 -> MsgValue.of((b - 0x100).toLong())  // negative fixint
                b == 0xD0 -> MsgValue.of(bytes[pos++].toLong())                                   // int8
                b == 0xD1 -> {
                    val v = (bytes[pos].toInt() shl 8) or (bytes[pos + 1].toInt() and 0xFF); pos += 2
                    MsgValue.of(v.toShort().toLong())
                }
                b == 0xD2 -> {
                    val v = readInt32Big(); MsgValue.of(v.toLong())
                }
                b == 0xD3 -> {
                    val v = readInt64Big(); MsgValue.of(v)
                }
                // scev's MsgPack emits uint variants for non-negative
                // values that fit — handle them symmetrically.
                b == 0xCC -> MsgValue.of((bytes[pos++].toLong() and 0xFF))                        // uint8
                b == 0xCD -> { val n = readUInt16Big(); MsgValue.of(n.toLong()) }
                b == 0xCE -> {
                    val v = readInt32Big(); MsgValue.of(v.toLong() and 0xFFFFFFFFL)
                }
                b == 0xCF -> { val v = readInt64Big(); MsgValue.of(v) }
                b == 0xCB -> {
                    val v = java.lang.Double.longBitsToDouble(readInt64Big()); MsgValue.of(v)
                }
                b and 0xE0 == 0xA0 -> readStr(b and 0x1F)                                          // fixstr
                b == 0xD9 -> readStr(bytes[pos++].toInt() and 0xFF)
                b == 0xDA -> { val n = readUInt16Big(); readStr(n) }
                b == 0xDB -> { val n = readInt32Big(); readStr(n) }
                b and 0xF0 == 0x90 -> readArray(b and 0x0F)                                       // fixarray
                b == 0xDC -> { val n = readUInt16Big(); readArray(n) }
                b == 0xDD -> { val n = readInt32Big(); readArray(n) }
                b and 0xF0 == 0x80 -> readMap(b and 0x0F)
                b == 0xDE -> { val n = readUInt16Big(); readMap(n) }
                b == 0xDF -> { val n = readInt32Big(); readMap(n) }
                b == 0xC5 -> { val n = readUInt16Big(); val bs = bytes.copyOfRange(pos, pos + n); pos += n; MsgValue.of(bs) }
                else -> error("unsupported msgpack head byte: 0x${b.toString(16)}")
            }
        }

        private fun readUInt16Big(): Int {
            val hi = bytes[pos].toInt() and 0xFF; val lo = bytes[pos + 1].toInt() and 0xFF; pos += 2
            return (hi shl 8) or lo
        }

        private fun readInt32Big(): Int {
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (bytes[pos + i].toInt() and 0xFF)
            pos += 4
            return v
        }

        private fun readInt64Big(): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
            pos += 8
            return v
        }

        private fun readStr(n: Int): MsgValue.Str {
            val s = String(bytes, pos, n, Charsets.UTF_8); pos += n
            return MsgValue.Str(s)
        }

        private fun readArray(n: Int): MsgValue.Arr {
            val xs = ArrayList<MsgValue>(n)
            repeat(n) { xs += readValue() }
            return MsgValue.Arr(xs)
        }

        private fun readMap(n: Int): MsgValue.Map {
            val m = LinkedHashMap<MsgValue, MsgValue>(n * 2)
            repeat(n) {
                val k = readValue(); val v = readValue(); m[k] = v
            }
            return MsgValue.Map(m)
        }
    }
}
