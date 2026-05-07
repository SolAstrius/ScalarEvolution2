/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.core.rpc

import lekkit.scev.rpc.MsgPack

import lekkit.scev.rpc.RpcFrame

/**
 * Wire-level encode/decode of [RpcFrame]s to/from MessagePack bytes.
 * Separate from the [Cobs] framing so the two layers stay independently
 * testable.
 *
 * Method names ([METHOD_PING] et al.) are flat strings; dot namespacing
 * is a convention, not a structural thing. Handler lookup is a plain
 * string equality check.
 */
object RpcProtocol {

    const val METHOD_PING        = "ping"
    const val METHOD_LOG         = "log"
    const val METHOD_LIST        = "list"
    const val METHOD_METHODS     = "methods"
    const val METHOD_CALL        = "call"
    const val METHOD_QUEUE_EVENT = "queue_event"
    const val METHOD_SUBSCRIBE   = "subscribe"
    const val METHOD_UNSUBSCRIBE = "unsubscribe"

    // Introspection surface. describe / schema / trace require CC; self
    // is machine-scoped and deliberately CC-independent.
    const val METHOD_DESCRIBE    = "describe"
    const val METHOD_SCHEMA      = "schema"
    const val METHOD_TYPE        = "type"
    const val METHOD_TRACE       = "trace"
    const val METHOD_SELF        = "self"

    // Chunked-transfer pull methods. The host sends a TAG_CHUNKED
    // frame in lieu of an oversized Response; the guest then issues
    // [METHOD_READ_CHUNK] / [METHOD_DISCARD_CHUNK] requests to drain
    // or abort the stream.
    const val METHOD_READ_CHUNK    = "read_chunk"
    const val METHOD_DISCARD_CHUNK = "discard_chunk"

    /**
     * Ordered batch — runs a sequence of `(method, args)` pairs as
     * one logical request. Items dispatch sequentially in input order
     * (no parallelism); results come back in the same order. See
     * `ScevRpcHandlers.batch` for the response envelope shape and
     * the optional `stop_on_error` knob.
     */
    const val METHOD_BATCH         = "batch"

    /**
     * Parallel batch — same envelope as [METHOD_BATCH], but items
     * dispatch concurrently. Same-peripheral calls still serialise
     * (per-peer mutex on the host); cross-peripheral calls run in
     * parallel. Always runs every item — `stop_on_error` doesn't
     * apply because there's no meaningful "halt" once the fan-out is
     * launched. Useful for fan-out reads (`describe`, `inventory.list`
     * across many chests) where round-trip count would otherwise
     * dominate latency.
     */
    const val METHOD_BATCH_PAR     = "batch_par"

    /**
     * Cancel an in-flight request by id. Args: `(id: int) -> bool`.
     * Returns true if the host had a matching live coroutine and
     * cancelled it; false if the id is unknown (already completed,
     * never existed, …). Idempotent and race-tolerant: completing
     * normally between the guest's decision to cancel and the host
     * processing the cancel returns false rather than erroring.
     *
     * Cancelled handlers don't produce a Response — the guest is
     * expected to have already moved on (typically because of a
     * client-side timeout), so a synthesized error reply would just
     * be noise.
     */
    const val METHOD_CANCEL        = "cancel"

    // Error-info map keys. Kept private — callers should construct
    // [RpcFrame.ErrorInfo] directly and let encode/decode handle the
    // wire shape.
    private val KEY_CODE = MsgValue.of("code")
    private val KEY_MESSAGE = MsgValue.of("message")

    /** Encode a frame to its MessagePack byte representation. */
    @JvmStatic
    fun encode(f: RpcFrame): ByteArray {
        val arr = ArrayList<MsgValue>(4)
        when (f) {
            is RpcFrame.Request -> {
                arr += MsgValue.of(RpcFrame.TAG_REQ.toLong())
                arr += MsgValue.of(f.id)
                arr += MsgValue.of(f.method)
                arr += MsgValue.ofArray(f.args)
            }
            is RpcFrame.Response -> {
                arr += MsgValue.of(RpcFrame.TAG_RSP.toLong())
                arr += MsgValue.of(f.id)
                arr += encodeError(f.error)
                arr += f.result
            }
            is RpcFrame.Event -> {
                arr += MsgValue.of(RpcFrame.TAG_EVT.toLong())
                arr += MsgValue.of(f.name)
                arr += MsgValue.ofArray(f.args)
            }
            is RpcFrame.Chunked -> {
                arr += MsgValue.of(RpcFrame.TAG_CHUNKED.toLong())
                arr += MsgValue.of(f.responseId)
                arr += MsgValue.of(f.streamId)
                arr += MsgValue.of(f.totalSize)
            }
        }
        return MsgPack.encode(MsgValue.ofArray(arr))
    }

    private fun encodeError(e: RpcFrame.ErrorInfo?): MsgValue {
        if (e == null) return MsgValue.NIL
        val m = LinkedHashMap<MsgValue, MsgValue>(2)
        m[KEY_CODE] = MsgValue.of(e.code)
        m[KEY_MESSAGE] = MsgValue.of(e.message)
        return MsgValue.ofMap(m)
    }

    /**
     * Decode a MessagePack-encoded frame. Returns `null` if the payload
     * isn't a well-formed RPC frame — caller logs + discards.
     */
    @JvmStatic
    fun decode(payload: ByteArray): RpcFrame? = try {
        val v = MsgPack.decode(payload)
        val arr = (v as? MsgValue.Arr)?.value ?: return null
        val tag = (arr.firstOrNull() as? MsgValue.Int)?.value?.toInt() ?: return null
        when (tag) {
            RpcFrame.TAG_REQ -> decodeRequest(arr)
            RpcFrame.TAG_RSP -> decodeResponse(arr)
            RpcFrame.TAG_EVT -> decodeEvent(arr)
            RpcFrame.TAG_CHUNKED -> decodeChunked(arr)
            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun decodeRequest(arr: List<MsgValue>): RpcFrame? {
        if (arr.size != 4) return null
        val id = (arr[1] as? MsgValue.Int)?.value ?: return null
        val method = (arr[2] as? MsgValue.Str)?.value ?: return null
        val args = (arr[3] as? MsgValue.Arr)?.value ?: return null
        return RpcFrame.Request(id, method, args)
    }

    private fun decodeResponse(arr: List<MsgValue>): RpcFrame? {
        if (arr.size != 4) return null
        val id = (arr[1] as? MsgValue.Int)?.value ?: return null
        val error = decodeError(arr[2]) ?: return null
        return RpcFrame.Response(id, error.orNull(), arr[3])
    }

    /**
     * Decode the error slot. Accepts:
     *  - `nil`                              → success (no error)
     *  - `{code, message}` map              → structured error
     *  - bare string (legacy)               → wrap as [RpcErrors.GENERIC]
     *
     * Returns a wrapping [DecodedError] so the caller can distinguish
     * "successfully decoded as no-error" from "malformed slot". `null`
     * return means the slot wasn't a recognised shape.
     */
    private fun decodeError(slot: MsgValue): DecodedError? = when (slot) {
        is MsgValue.Nil -> DecodedError(null)
        is MsgValue.Str -> DecodedError(RpcFrame.ErrorInfo(RpcErrors.GENERIC, slot.value))
        is MsgValue.Map -> {
            val m = slot.value
            val code = (m[KEY_CODE] as? MsgValue.Str)?.value ?: RpcErrors.GENERIC
            val msg = (m[KEY_MESSAGE] as? MsgValue.Str)?.value ?: ""
            DecodedError(RpcFrame.ErrorInfo(code, msg))
        }
        else -> null
    }

    private class DecodedError(private val info: RpcFrame.ErrorInfo?) {
        fun orNull(): RpcFrame.ErrorInfo? = info
    }

    private fun decodeEvent(arr: List<MsgValue>): RpcFrame? {
        if (arr.size != 3) return null
        val name = (arr[1] as? MsgValue.Str)?.value ?: return null
        val args = (arr[2] as? MsgValue.Arr)?.value ?: return null
        return RpcFrame.Event(name, args)
    }

    private fun decodeChunked(arr: List<MsgValue>): RpcFrame? {
        if (arr.size != 4) return null
        val id = (arr[1] as? MsgValue.Int)?.value ?: return null
        val streamId = (arr[2] as? MsgValue.Int)?.value ?: return null
        val total = (arr[3] as? MsgValue.Int)?.value ?: return null
        return RpcFrame.Chunked(id, streamId, total)
    }
}
