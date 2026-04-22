/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc

import dan200.computercraft.api.lua.ILuaContext
import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.api.lua.LuaTask
import dan200.computercraft.api.lua.MethodResult
import java.util.concurrent.atomic.AtomicLong

/**
 * [ILuaContext] stand-in for scev's faux-computer.
 *
 * Real CC computers run peripheral methods on their Lua thread. CC's
 * `@LuaFunction(mainThread = true)` machinery uses [ILuaContext] to
 * schedule Minecraft-world-touching code back onto the server main
 * thread, yielding the Lua coroutine until a `task_complete` event
 * arrives. We have no Lua thread and no coroutines, but we're already
 * *on* the server main thread — dispatch runs inside the tick listener
 * ([lekkit.scev.rpc.ScevRpcManager.onServerTick]) — so both
 * [issueMainThreadTask] and [executeMainThreadTask] can run tasks
 * synchronously.
 *
 * The returned task id is a monotonic counter kept for API compliance;
 * nothing downstream consumes it (the `task_complete` event that a real
 * CC runtime would queue is unobserved because our RPC client got its
 * result synchronously).
 *
 * Not thread-safe against concurrent dispatch — a [ScevLuaContext]
 * instance is scoped to a single RPC call on the tick thread.
 */
class ScevLuaContext : ILuaContext {
    private val nextTaskId = AtomicLong(1)

    /**
     * Run `task` now. Returns the assigned task id so callers that use
     * the [ILuaContext.issueMainThreadTask] contract (fire-and-forget)
     * work. If the task throws a [LuaException], it propagates.
     */
    override fun issueMainThreadTask(task: LuaTask): Long {
        val id = nextTaskId.getAndIncrement()
        // We're synchronous — fire the task now and drop its return
        // value on the floor. In a real CC runtime the return values
        // would be appended to the `task_complete` event the Lua
        // coroutine is waiting on.
        task.execute()
        return id
    }

    /**
     * Run `task` now and wrap its return as a [MethodResult]. A real
     * [ILuaContext] yields the caller's coroutine until `task_complete`
     * arrives — we short-circuit straight to the result.
     */
    override fun executeMainThreadTask(task: LuaTask): MethodResult {
        val result = task.execute()
        return if (result == null) MethodResult.of() else MethodResult.of(*result)
    }
}
