/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc

import com.mojang.logging.LogUtils
import lekkit.scev.rpc.ScevRpcManager
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CC: Tweaked integration wiring.
 *
 * Soft-dep: only reachable from [ScevCCBootstrap], which classloads
 * this file behind a `ModList.isLoaded("computercraft")` check. Any
 * reference to `dan200.computercraft.*` types belongs here or in
 * sibling classes that are transitively reached only through here —
 * keeping [lekkit.scev.rpc.ScevRpcManager] free of CC-typed symbols
 * so a server without CC installed never links CC symbols on load.
 *
 * What used to live in the legacy version of this module: registering
 * scev block entities as CC peripherals (the "Lua → scev" direction)
 * so a CC computer adjacent to a scev block could power it on. That
 * direction is gone — scev now impersonates a CC computer and calls
 * *out* to adjacent peripherals. The only wiring left is:
 *
 *  1. Hook every live [ScevRpcManager] up to a per-machine
 *     [ScevCCComputer] so `list` / `methods` / `call` / `queue_event`
 *     RPCs have something to dispatch against.
 *  2. Tick those computers on every server tick to keep the
 *     side-adjacent peripheral map fresh.
 */
internal object ScevCCCompat {
    private val LOG = LogUtils.getLogger()

    /**
     * Live per-machine computers. Indexed by machine UUID so [tick]
     * can GC entries whose backing [ScevRpcManager] disappeared
     * (unregister has no listener hook we can subscribe to, so we
     * detect it by polling ScevRpcManager.get).
     */
    private val computers = ConcurrentHashMap<UUID, ScevCCComputer>()

    fun register() {
        ScevRpcManager.addCreateListener(::onManagerCreated)
        NeoForge.EVENT_BUS.register(ScevCCCompat)
        LOG.info("[scev-cc] CC: Tweaked integration registered (scev-as-computer direction)")
    }

    private fun onManagerCreated(mgr: ScevRpcManager) {
        val uuid = mgr.machineUuid()
        val computer = computers.computeIfAbsent(uuid) { ScevCCComputer(it) }
        ScevCCHandlers.install(mgr.dispatcher(), computer)
    }

    // Intentionally non-static: NeoForge's EVENT_BUS.register(instance)
    // expects instance-level @SubscribeEvent methods; a static-typed
    // @JvmStatic method here would make NeoForge reject the whole object.
    @SubscribeEvent
    fun onServerTickPost(event: ServerTickEvent.Post) {
        if (computers.isEmpty()) return
        val iter = computers.entries.iterator()
        while (iter.hasNext()) {
            val (uuid, computer) = iter.next()
            if (ScevRpcManager.get(uuid) == null) {
                // Machine unregistered — drop the computer so its side
                // map and any future peripheral references can be GC'd.
                computer.shutdown()
                iter.remove()
            } else {
                computer.tick()
            }
        }
    }
}
