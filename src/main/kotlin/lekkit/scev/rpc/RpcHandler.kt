/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

/**
 * One handler per method name. Runs inside a coroutine scope owned by
 * the per-machine [ScevRpcManager]. The scope's dispatcher is
 * [lekkit.scev.common.ServerScope.dispatcher] — a tick-thread-aware
 * dispatcher whose `isDispatchNeeded` returns false when the caller is
 * already on the server thread. A strictly-synchronous handler thus
 * executes inline on the tick thread with no context switch, and its
 * response reaches the serial RX ring before `launch` returns. A
 * handler that suspends (peripheral pullEvent, remote I/O,
 * `delay(…)`) yields its coroutine; on resume, the dispatcher routes
 * the continuation back to the server thread for the response write.
 *
 * Handlers should NOT throw for expected-failure cases — throw
 * [RpcException] with a message the guest can display. Any other
 * exception is caught by the dispatcher and turned into a generic
 * "internal error" response (full trace logged server-side only).
 */
fun interface RpcHandler {
    @Throws(RpcHandler.RpcException::class)
    suspend operator fun invoke(args: List<MsgValue>): MsgValue

    /** Signals a controlled failure whose message should reach the guest. */
    class RpcException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
