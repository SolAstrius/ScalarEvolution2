/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

import lekkit.scev.core.rpc.MsgValue
import lekkit.scev.core.rpc.RpcErrors
import lekkit.scev.core.rpc.RpcHandler
import lekkit.scev.core.rpc.RpcProtocol

import com.mojang.logging.LogUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Per-machine method table and request dispatcher.
 *
 * One [RpcHandler] per method name. [dispatch] is a suspend function,
 * so sync handlers complete inline on the caller thread while async
 * handlers (peripheral pullEvent loops, delays, remote I/O) suspend
 * without blocking the server tick. [ScevRpcManager] launches a
 * coroutine per incoming request on its scope and queues the returned
 * [RpcFrame.Response] for the next-tick flush.
 *
 * Last-writer-wins on repeated registration (logged).
 */
class RpcDispatcher {
    private val handlers = ConcurrentHashMap<String, RpcHandler>()

    fun register(method: String, handler: RpcHandler): RpcDispatcher {
        val prev = handlers.put(method, handler)
        if (prev != null) LOG.warn("RPC method {} replaced", method)
        return this
    }

    fun unregister(method: String) {
        handlers.remove(method)
    }

    fun hasHandler(method: String): Boolean = handlers.containsKey(method)

    /**
     * Dispatch a request. Returns a non-null [RpcFrame.Response] — any
     * handler exception is caught and surfaced as an error response.
     * Cancellation (from scope teardown on machine unregister)
     * propagates to the caller so the manager can drop the request
     * without writing a response for a dead stream.
     */
    @Throws(CancellationException::class)
    suspend fun dispatch(req: RpcFrame.Request): RpcFrame.Response {
        val handler = handlers[req.method]
            ?: return RpcFrame.error(req.id, RpcErrors.NO_SUCH_METHOD, "unknown method: ${req.method}")
        val args: List<MsgValue> = req.args ?: emptyList()
        return try {
            val result = handler.invoke(args)
            RpcFrame.ok(req.id, result ?: MsgValue.NIL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcHandler.RpcException) {
            RpcFrame.error(req.id, e.code, e.message ?: "rpc error")
        } catch (e: RuntimeException) {
            LOG.warn("RPC handler {} threw", req.method, e)
            RpcFrame.error(req.id, RpcErrors.INTERNAL_ERROR, "internal error")
        }
    }

    companion object {
        private val LOG = LogUtils.getLogger()
    }
}
