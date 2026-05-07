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
}
