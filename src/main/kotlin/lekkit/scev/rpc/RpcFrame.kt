/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcErrors

/**
 * Typed view over a decoded RPC frame. Every wire frame is a MessagePack
 * array whose first element is a small integer tag:
 *
 * ```
 *   [0, id, method, args]                - request
 *   [1, id, err_or_nil, result]          - response
 *   [2, name, args]                      - event
 *   [3, id, stream_id, total_size]       - chunked response marker
 * ```
 *
 * The chunked marker stands in for a Response whose encoded form
 * exceeds the wire frame cap. The original full Response bytes are
 * cached host-side under `stream_id`; the guest reassembles them via
 * `read_chunk(stream_id, offset, max)` calls and decodes the result
 * as a normal [Response]. See [ScevRpcManager] for the chunking
 * mechanics.
 *
 * `err_or_nil` is `nil` on success, or a map `{code: str, message: str}`
 * on error. (For backward-decoding tolerance, a bare string is also
 * accepted by [RpcProtocol.decode] and lifted to `{code: GENERIC,
 * message: <str>}` — but the host always emits the map form.)
 *
 * IDs are unsigned 32-bit; we carry them as `Long` to dodge sign confusion
 * on the JVM side. They're guest-chosen, guest-unique within a session;
 * Java reflects them back on the matching response.
 *
 * Sealed so when expressions exhaust cleanly. Use the static constructors
 * rather than the data classes directly when building outbound frames —
 * they validate basic shape.
 */
sealed interface RpcFrame {

    data class Request(
        @get:JvmName("id") val id: Long,
        @get:JvmName("method") val method: String,
        @get:JvmName("args") val args: List<MsgValue>,
    ) : RpcFrame

    /**
     * Structured error payload carried in a [Response.error] slot.
     * `code` is one of [RpcErrors]; `message` is human-readable, never
     * null. Guests branch on `code` and display `message` to the user.
     */
    data class ErrorInfo(
        @get:JvmName("code") val code: String,
        @get:JvmName("message") val message: String,
    )

    data class Response(
        @get:JvmName("id") val id: Long,
        @get:JvmName("error") val error: ErrorInfo?,
        @get:JvmName("result") val result: MsgValue,
    ) : RpcFrame

    data class Event(
        @get:JvmName("name") val name: String,
        @get:JvmName("args") val args: List<MsgValue>,
    ) : RpcFrame

    /**
     * Marker frame sent in lieu of a [Response] whose encoded size
     * exceeds the wire cap. Guest fetches `total_size` bytes via
     * `read_chunk(stream_id, ...)` and decodes the assembled buffer
     * as a regular Response (with id == [responseId]).
     */
    data class Chunked(
        @get:JvmName("responseId") val responseId: Long,
        @get:JvmName("streamId") val streamId: Long,
        @get:JvmName("totalSize") val totalSize: Long,
    ) : RpcFrame

    companion object {
        const val TAG_REQ: Int = 0
        const val TAG_RSP: Int = 1
        const val TAG_EVT: Int = 2
        const val TAG_CHUNKED: Int = 3

        @JvmStatic
        fun ok(id: Long, result: MsgValue?): Response =
            Response(id, null, result ?: MsgValue.NIL)

        /** Build an error response with an explicit code. */
        @JvmStatic
        fun error(id: Long, code: String, message: String): Response =
            Response(id, ErrorInfo(code, message), MsgValue.NIL)

        /** Convenience: error with [RpcErrors.GENERIC] code. Prefer the
         *  three-arg form when a more specific code applies. */
        @JvmStatic
        fun error(id: Long, message: String): Response =
            error(id, RpcErrors.GENERIC, message)

        @JvmStatic
        fun event(name: String, args: List<MsgValue>?): Event =
            Event(name, args ?: emptyList())

        @JvmStatic
        fun chunked(responseId: Long, streamId: Long, totalSize: Long): Chunked =
            Chunked(responseId, streamId, totalSize)
    }
}
