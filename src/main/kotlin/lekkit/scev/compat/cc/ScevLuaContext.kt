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
 * **Why we still queue `task_complete` events.** When CC processes a
 * `mainThread = true` method on an [IDynamicPeripheral] (the path
 * generic-inventory chests/hoppers/furnaces take), it wraps the call in
 * a [dan200.computercraft.core.computer.GuardedLuaContext] that
 * delegates only [issueMainThreadTask] back to us — *not*
 * [executeMainThreadTask]. The default `executeMainThreadTask` impl
 * builds a `TaskCallback`, calls our [issueMainThreadTask] (which runs
 * the task synchronously and stores the result inside the callback),
 * then returns `MethodResult.pullEvent("task_complete", callback)`. Our
 * caller's dispatch loop is now waiting on a `task_complete` event that
 * nobody else will queue. So we queue it ourselves — with the matching
 * task id and a `true` success flag — right after the task runs. The
 * pullEvent resumes immediately on the next dispatch-loop iteration,
 * `TaskCallback.resume` reads back its stored result, and the call
 * returns synchronously the way our overridden [executeMainThreadTask]
 * intends.
 *
 * If [computer] is null (in tests, mostly), `task_complete` events are
 * not queued — direct callers of static `@LuaFunction(mainThread=true)`
 * methods that go through our [executeMainThreadTask] override still
 * work, but generic-inventory paths that flow through GuardedLuaContext
 * will hang. Production callers (the RPC `call` handler) must pass a
 * non-null [computer].
 *
 * Not thread-safe against concurrent dispatch — a [ScevLuaContext]
 * instance is scoped to a single RPC call on the tick thread.
 */
class ScevLuaContext @JvmOverloads constructor(
    private val computer: ScevCCComputer? = null,
) : ILuaContext {
    private val nextTaskId = AtomicLong(1)

    /**
     * Run `task` now. Returns the assigned task id so callers that use
     * the [ILuaContext.issueMainThreadTask] contract (fire-and-forget)
     * work. If the task throws a [LuaException], it propagates.
     *
     * On both success and failure (including non-Lua exceptions), a
     * `task_complete` event is queued on [computer] — matching the
     * shape `TaskCallback` expects — so callers who saw a
     * `pullEvent("task_complete", …)` MethodResult can resume.
     */
    override fun issueMainThreadTask(task: LuaTask): Long {
        val id = nextTaskId.getAndIncrement()
        try {
            task.execute()
            // Success. Real value (if any) is captured by the
            // TaskCallback's own `execute()` side-effect; the event
            // payload only needs the id + success flag.
            computer?.queueEvent("task_complete", id, true)
        } catch (e: LuaException) {
            computer?.queueEvent("task_complete", id, false, e.message ?: "task error")
            throw e
        } catch (e: RuntimeException) {
            computer?.queueEvent("task_complete", id, false, e.message ?: "internal error")
            throw e
        }
        return id
    }

    /**
     * Run `task` now and wrap its return as a [MethodResult]. A real
     * [ILuaContext] yields the caller's coroutine until `task_complete`
     * arrives — we short-circuit straight to the result. Note that this
     * override is only honoured when CC dispatches against *our* context
     * directly; the GuardedLuaContext wrapper around it for dynamic
     * peripherals does not delegate this call (see class kdoc).
     */
    override fun executeMainThreadTask(task: LuaTask): MethodResult {
        val result = task.execute()
        return if (result == null) MethodResult.of() else MethodResult.of(*result)
    }
}
