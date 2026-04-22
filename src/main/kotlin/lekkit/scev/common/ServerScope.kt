/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.common

import com.mojang.logging.LogUtils
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.server.MinecraftServer
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent

/**
 * Lifecycle-bound server coroutine scope and tick-thread dispatcher.
 *
 * [dispatcher] wraps the [MinecraftServer]'s tick thread so
 * `withContext(ServerScope.dispatcher)` suspends the caller until the
 * server thread is ready to run the block. When the caller is *already*
 * on the server thread, [ServerThreadDispatcher.isDispatchNeeded]
 * returns false and the block runs inline with no hop — critical for
 * the RPC dispatch fast path where sync handlers must complete within
 * the same tick.
 *
 * [scope] is a [CoroutineScope] rooted on a [SupervisorJob] with
 * [dispatcher] as its context, so all launches default to executing on
 * the server thread and one failing child does not cascade.
 *
 * Lifecycle:
 * - Before the integrated/dedicated server starts: [dispatcher] and
 *   [scope] default to [Dispatchers.Unconfined]. This is the behavior
 *   unit tests rely on — launches run inline on the caller, and tests
 *   that exercise per-machine managers outside a running server don't
 *   need to mock anything.
 * - [onServerStarting] is wired as an [EVENT_BUS] listener in
 *   `ScalarEvolution`. It installs the real [ServerThreadDispatcher]
 *   and opens a fresh [SupervisorJob]-rooted scope.
 * - [onServerStopping] cancels the server-bound scope, cascading
 *   cancellation to every child scope parented to it (per-machine RPC
 *   managers, etc.), then reverts to the Unconfined fallback so any
 *   post-stop code path doesn't NPE on a null dispatcher.
 */
object ServerScope {
    private val LOG = LogUtils.getLogger()

    @Volatile
    private var active: Binding = Binding.fallback()

    /** Dispatcher routing work to the server tick thread (when active). */
    @JvmStatic
    val dispatcher: CoroutineDispatcher
        get() = active.dispatcher

    /**
     * Server-scoped [CoroutineScope]. Children launched on this scope
     * are cancelled on [onServerStopping]. Create per-feature child
     * scopes with a [SupervisorJob] parented to [scope]'s [Job] for
     * independent cancellation without cascading into the server scope.
     */
    @JvmStatic
    val scope: CoroutineScope
        get() = active.scope

    /** True once a server is running and the real dispatcher is in place. */
    @JvmStatic
    fun isActive(): Boolean = active.isReal

    @JvmStatic
    fun onServerStarting(event: ServerStartingEvent) {
        val server = event.server
        val realDispatcher = ServerThreadDispatcher(server)
        val job = SupervisorJob()
        val realScope = CoroutineScope(job + realDispatcher)
        active = Binding(realDispatcher, realScope, isReal = true)
        LOG.debug("[scev-scope] attached to server {}", server.motd)
    }

    @JvmStatic
    fun onServerStopping(event: ServerStoppingEvent) {
        val previous = active
        active = Binding.fallback()
        previous.scope.cancel(CancellationException("server stopping"))
        LOG.debug("[scev-scope] server scope cancelled")
    }

    /** Visible for tests that want to reset between runs without a real server. */
    @JvmStatic
    fun resetForTests() {
        val previous = active
        active = Binding.fallback()
        if (previous.isReal) previous.scope.cancel(CancellationException("test reset"))
    }

    private data class Binding(
        val dispatcher: CoroutineDispatcher,
        val scope: CoroutineScope,
        val isReal: Boolean,
    ) {
        companion object {
            fun fallback(): Binding {
                val dispatcher = Dispatchers.Unconfined
                return Binding(
                    dispatcher = dispatcher,
                    scope = CoroutineScope(SupervisorJob() + dispatcher),
                    isReal = false,
                )
            }
        }
    }
}

/**
 * [CoroutineDispatcher] that routes work to the Minecraft server's
 * tick thread. [isDispatchNeeded] returns false when the caller is
 * already on the server thread, so `scope.launch(dispatcher) { ... }`
 * runs inline in that case and doesn't pay for a hop through
 * [MinecraftServer.execute].
 */
private class ServerThreadDispatcher(
    private val server: MinecraftServer,
) : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !server.isSameThread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // BlockableEventLoop.execute(Runnable) queues onto the server's
        // pending task queue; the task runs at the next poll point
        // (between ticks or during a blocking wait).
        server.execute(block)
    }

    override fun toString(): String = "ServerThreadDispatcher"
}
