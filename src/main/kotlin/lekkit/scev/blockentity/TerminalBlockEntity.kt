/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity

import java.util.UUID
import lekkit.scev.bus.PeripheralBusElement
import lekkit.scev.bus.PeripheralDeviceKind
import lekkit.scev.client.terminal.setup.SetupModel
import lekkit.scev.main.ScevRegistry
import lekkit.scev.menu.TerminalMenu
import lekkit.scev.network.SerialOutPayload
import lekkit.scev.network.SetupSyncPayload
import lekkit.scev.rpc.KernelConsoleSink
import lekkit.scev.rpc.ScevRpcManager
import lekkit.scev.server.TerminalSubscriberRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent

/**
 * VT100 terminal block entity.
 *
 * Bus role: SERIAL — the [PeripheralBusController] on a neighbouring
 * computer block discovers this BE and stamps it with the machine's
 * UUID. Once bound, the BE registers a [KernelConsoleSink] on the
 * machine's RPC manager so guest TX bytes (kernel printk, getty
 * prompt, shell output, etc. — everything written to ttyS0) get
 * fanned out to clients viewing this terminal.
 *
 * Client → Server (typed keystrokes) lives in the
 * [lekkit.scev.network.SerialInPayload] handler — see ScevNetwork.
 *
 * Sink lifetime: registered the first server tick we observe a
 * non-null bound machine; deregistered when the bound machine
 * changes or the BE is removed. Idempotent — re-registering the
 * same UUID is a no-op.
 *
 * **Targeted fan-out.** The kernel TX broadcast goes only to players
 * who currently have a [TerminalMenu] open for the matching machine —
 * not "every player in the level." A 50-player public server with
 * one VT100 doesn't pay the boot-log bandwidth (~80 KB during Alpine
 * boot) on 49 players who don't have a screen open. The
 * containerMenu check is the same authority model the input path
 * uses: server-side state, can't be spoofed by a client.
 *
 * **Replay on open.** When a player opens a TerminalMenu, the static
 * [onMenuOpen] listener pulls the matching machine's recent
 * [ScevRpcManager.consoleReplaySnapshot] and sends it as one big
 * [SerialOutPayload] to that single player. Their client mlterm
 * parses it and arrives at the same screen state as everyone who's
 * been watching from the start (deterministic VT/xterm parsing
 * makes the byte stream the canonical state — no snapshot of mlterm
 * internals needed).
 */
class TerminalBlockEntity(
    pos: BlockPos, state: BlockState,
    /** Era / capability profile. Set at construction by the concrete
     *  block class (Vt100Block → VT100, future Vt220Block → VT220, …)
     *  and then frozen for the BE's lifetime — kind never changes
     *  after placement. NBT save/load preserves it across world
     *  reloads. Defaults to VT100 so a BE deserialized from a save
     *  that predates this field still loads sensibly. */
    initialKind: TerminalKind = TerminalKind.DEFAULT,
) : ScevBlockEntity(ScevRegistry.TERMINAL_BE.get(), pos, state),
    PeripheralBusElement {

    /** See [initialKind] kdoc. Mutable only via NBT load on the
     *  server thread; treat as read-only otherwise. */
    var kind: TerminalKind = initialKind
        private set

    /**
     * Per-block Setup state — equivalent to a real VT100's NVR
     * contents (tabs, switchpacks, speeds, answerback, intensity,
     * 80/132 column flag) plus our additions (CRT FX, scrollback).
     *
     * Server-authoritative; mutated only via [applySetupEdit] on the
     * server thread. Save/load round-trips through NBT so settings
     * survive world reloads. On change, broadcast a [SetupSyncPayload]
     * to every viewer of the block — same fan-out as kernel TX.
     *
     * Defaults match a freshly-shipped VT100 (DEC factory NVR
     * defaults), see [SetupModel.PersistentState] kdoc.
     */
    var setupState: SetupModel.PersistentState = SetupModel.PersistentState()
        private set

    private var bound: UUID? = null
    private var boundPos: BlockPos? = null

    /** Tracks which UUID we've registered our sink on, so we can
     *  unregister + re-register on rebind. Null = no sink installed. */
    private var sinkRegisteredFor: UUID? = null

    /** Captured `level` for the deregister path — `level` is null
     *  inside [setRemoved] sometimes depending on chunk-unload
     *  ordering, but we still want to drop the sink. */
    private var serverLevelRef: ServerLevel? = null

    /** The sink itself. One per BE. Closes over `this` so it can
     *  reach `serverLevelRef` and broadcast. */
    private val consoleSink = object : KernelConsoleSink {
        override fun onConsoleBytes(bytes: ByteArray, len: Int) {
            val uuid = bound ?: return
            val sl = serverLevelRef ?: return
            // Send only to players whose currently-open menu is a
            // TerminalMenu pointing at this machine. Everyone else is
            // either looking at something else or has nothing open;
            // sending to them would be pure wire waste plus they'd
            // drop it anyway.
            val packet = ClientboundCustomPayloadPacket(
                SerialOutPayload(uuid, bytes.copyOf(len))
            )
            // Recipient set = (players with the matching menu open)
            // ∪ (ambient subscribers — clients with an in-world block
            // face hosted for this UUID). Ambient subs were authorized
            // at subscribe time against hasBoundBE; that gate plus
            // "they sent the packet" is the same authority shape as
            // the menu-open path.
            val sent = HashSet<ServerPlayer>(8)
            for (p in sl.players()) {
                val menu = p.containerMenu as? TerminalMenu
                if (menu?.machineUuid == uuid && sent.add(p)) {
                    p.connection.send(packet)
                }
            }
            for (p in TerminalSubscriberRegistry.ambientSubscribers(uuid)) {
                if (p.level() === sl && sent.add(p)) {
                    p.connection.send(packet)
                }
            }
        }
    }

    override fun peripheralKinds(): Set<PeripheralDeviceKind> = setOf(PeripheralDeviceKind.SERIAL)

    override fun boundMachineUuid(): UUID? = bound
    override fun setBoundMachineUuid(uuid: UUID?) {
        if (bound == uuid) return
        bound = uuid
        // Mark the BE dirty + push a block update so the client BE's
        // bound state matches and TerminalRenderer can find the
        // active host. Without this the client BE stays bound=null
        // forever, even though the server side is fully wired —
        // the in-world block face goes black even when the GUI works.
        setChanged()
        if (level != null && !level!!.isClientSide) {
            level!!.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
        // Sink (re-)attachment happens on next serverTick rather
        // than here, because PeripheralBusController.scan can fire
        // setBoundMachineUuid before MachineManager has the machine
        // state populated for a freshly-built case.
    }
    override fun boundMachinePos(): BlockPos? = boundPos
    override fun setBoundMachinePos(pos: BlockPos?) { boundPos = pos }

    override fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        if (level !is ServerLevel) return
        serverLevelRef = level

        val want = bound
        val have = sinkRegisteredFor
        if (want == have) return  // steady state: already (un)registered correctly

        // De-register from old machine if any.
        if (have != null) {
            ScevRpcManager.get(have)?.removeConsoleSink(consoleSink)
            sinkRegisteredFor = null
        }
        // Register against new machine if we're bound.
        if (want != null) {
            val mgr = ScevRpcManager.get(want)
            if (mgr != null) {
                mgr.addConsoleSink(consoleSink)
                sinkRegisteredFor = want
            }
            // If find() returned null the machine isn't up yet;
            // we'll retry on the next tick (want != have will still
            // hold so this branch re-runs).
            // Always register the bound-position even if the manager
            // isn't ready yet — clients can ambient-subscribe before
            // the guest VM finishes boot.
            TerminalSubscriberRegistry.addBoundBE(want, pos)
        }
    }

    /**
     * Apply a fresh persistent Setup state authoritatively on the
     * server side, persist to NBT, and broadcast a [SetupSyncPayload]
     * to every viewer of this block. No-op if [next] is structurally
     * equal to the current state (avoids broadcast storms when a
     * client retransmits the same state).
     *
     * Called from [lekkit.scev.network.ScevNetwork]'s
     * [SetupEditPayload] handler after it has validated the sender's
     * open menu matches this BE.
     */
    fun applySetupEdit(next: SetupModel.PersistentState) {
        if (next == setupState) return
        setupState = next
        setChanged()                       // mark NBT dirty
        // Push a block update so the client BE sees the new state via
        // getUpdatePacket → saveAdditional → applies on load. The
        // SetupSyncPayload below covers the open-menu's local model
        // (keystroke responsiveness across multi-viewer rooms), but
        // the BE renderer reads be.setupState directly — without this
        // the in-world block face won't pick up phosphor / scanline /
        // brightness changes until the chunk reloads.
        if (level != null && !level!!.isClientSide) {
            level!!.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
        broadcastSetupState()
    }

    /**
     * Push the current persistent state to all viewers. Called
     * unconditionally from [applySetupEdit] (something just changed)
     * and on demand from menu-open in the companion (push the initial
     * state so a freshly-opened screen renders the right Setup pages).
     */
    fun broadcastSetupState() {
        val uuid = bound ?: return
        val sl = serverLevelRef ?: return
        val packet = ClientboundCustomPayloadPacket(
            SetupSyncPayload(uuid, setupState)
        )
        val sent = HashSet<ServerPlayer>(8)
        for (p in sl.players()) {
            val menu = p.containerMenu as? TerminalMenu
            if (menu?.machineUuid == uuid && sent.add(p)) {
                p.connection.send(packet)
            }
        }
        for (p in TerminalSubscriberRegistry.ambientSubscribers(uuid)) {
            if (p.level() === sl && sent.add(p)) {
                p.connection.send(packet)
            }
        }
    }

    /* ----- NBT — persist [kind] + [setupState] + [bound] across save/load.
     * The base class's getUpdateTag uses saveWithoutMetadata which calls
     * saveAdditional, so anything written here ALSO ships to clients
     * via the BE update packet. That's how the client renderer learns
     * the bound UUID — without including it here, the client BE stays
     * bound=null and the in-world block face never lights up. */

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains("kind")) {
            kind = TerminalKind.byNameOrDefault(tag.getString("kind"))
        }
        if (tag.contains("setup")) {
            setupState = SetupModel.PersistentState.fromNbt(tag.getCompound("setup"))
        }
        bound = if (tag.hasUUID("bound")) tag.getUUID("bound") else null
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString("kind", kind.name)
        tag.put("setup", setupState.toNbt())
        bound?.let { tag.putUUID("bound", it) }
    }

    override fun setRemoved() {
        val have = sinkRegisteredFor
        if (have != null) {
            ScevRpcManager.get(have)?.removeConsoleSink(consoleSink)
            sinkRegisteredFor = null
        }
        bound?.let { TerminalSubscriberRegistry.removeBoundBE(it, blockPos) }
        super.setRemoved()
    }

    companion object {
        /**
         * NeoForge fires this after the menu's been opened on the
         * server side and slot sync has run. Catch [TerminalMenu]
         * specifically and ship the replay snapshot to that one
         * player so they don't stare at black until the kernel
         * coughs up its next byte.
         *
         * Live bytes that arrive AFTER this point reach the player
         * via the per-tick fan-out in [consoleSink]; the replay
         * captures everything before. Brief overlap with bytes that
         * arrived during the same tick this fired is fine —
         * deterministic parsing means duplicating a few bytes lands
         * in the same screen state.
         */
        /**
         * Drop a disconnecting player from every ambient-subscriber
         * set so we don't leak a stale ServerPlayer reference (and
         * keep sending packets to a closed connection).
         */
        @SubscribeEvent
        @JvmStatic
        fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
            val sp = event.entity as? ServerPlayer ?: return
            lekkit.scev.server.TerminalSubscriberRegistry.clearPlayer(sp)
        }

        @SubscribeEvent
        @JvmStatic
        fun onMenuOpen(event: PlayerContainerEvent.Open) {
            val player = event.entity as? ServerPlayer ?: return
            val menu = event.container as? TerminalMenu ?: return
            val uuid = menu.machineUuid ?: return

            // Push the BE's current persistent Setup state to this
            // single player so their screen has the right values for
            // SET-UP A/B/CRT FX/MOD pages immediately on open. Live
            // edits afterwards are handled by [applySetupEdit]'s
            // broadcast.
            val sl = player.serverLevel()
            val be = sl.getBlockEntity(menu.blockPos) as? TerminalBlockEntity
            if (be != null) {
                player.connection.send(
                    ClientboundCustomPayloadPacket(
                        SetupSyncPayload(uuid, be.setupState)
                    )
                )
            }

            val bytes = ScevRpcManager.get(uuid)?.consoleReplaySnapshot() ?: return
            if (bytes.isEmpty()) return
            player.connection.send(
                ClientboundCustomPayloadPacket(SerialOutPayload(uuid, bytes))
            )
        }
    }
}
