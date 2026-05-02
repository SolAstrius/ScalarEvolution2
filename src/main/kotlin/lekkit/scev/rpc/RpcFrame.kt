/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

import lekkit.scev.core.rpc.MsgValue

/**
 * Typed view over a decoded RPC frame. Every wire frame is a MessagePack
 * array whose first element is a small integer tag:
 *
 * ```
 *   [0, id, method, args]         - request
 *   [1, id, err_or_nil, result]   - response
 *   [2, name, args]               - event
 * ```
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

    data class Response(
        @get:JvmName("id") val id: Long,
        @get:JvmName("error") val error: String?,
        @get:JvmName("result") val result: MsgValue,
    ) : RpcFrame

    data class Event(
        @get:JvmName("name") val name: String,
        @get:JvmName("args") val args: List<MsgValue>,
    ) : RpcFrame

    companion object {
        const val TAG_REQ: Int = 0
        const val TAG_RSP: Int = 1
        const val TAG_EVT: Int = 2

        @JvmStatic
        fun ok(id: Long, result: MsgValue?): Response =
            Response(id, null, result ?: MsgValue.NIL)

        @JvmStatic
        fun error(id: Long, message: String): Response =
            Response(id, message, MsgValue.NIL)

        @JvmStatic
        fun event(name: String, args: List<MsgValue>?): Event =
            Event(name, args ?: emptyList())
    }
}
