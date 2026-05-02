/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network

import lekkit.scev.blockentity.TerminalBlockEntity
import lekkit.scev.client.DisplayManager
import lekkit.scev.client.SoundStreamPlayer
import lekkit.scev.client.terminal.SerialDispatcher
import lekkit.scev.client.terminal.setup.SetupSyncDispatcher
import lekkit.scev.menu.TerminalMenu
// SetupEditPayload + SetupSyncPayload live in this same package; no
// import needed but listing for find-by-symbol grep convenience.
import lekkit.scev.rpc.ScevRpcManager
import lekkit.scev.menu.ComputerCaseMenu
import lekkit.scev.menu.FlashProgrammerMenu
import lekkit.scev.menu.MachineMenu
import lekkit.scev.menu.McuBoardMenu
import lekkit.scev.server.FlashProgrammerService
import lekkit.scev.server.IMachineHandle
import lekkit.scev.server.MachineManager
import lekkit.scev.server.VideoKeyframeRequests
import lekkit.scev.server.TerminalSubscriberRegistry
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Registers every custom packet payload and wires each to its server-
 * or client-side handler. Payload classes live under
 * `lekkit.scev.network`; add a new `r.playToServer` / `r.playToClient`
 * entry below when introducing one.
 */
object ScevNetwork {

    @JvmStatic
    fun register(modBus: IEventBus) {
        modBus.addListener(::onRegister)
    }

    private fun onRegister(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("1").optional()

        r.playToServer(MachineInputPayload.TYPE, MachineInputPayload.STREAM_CODEC,
            ::handleInputOnServer)
        r.playToServer(MachineResetPayload.TYPE, MachineResetPayload.STREAM_CODEC,
            ::handleResetOnServer)
        r.playToServer(FlashProgrammerWritePayload.TYPE, FlashProgrammerWritePayload.STREAM_CODEC,
            ::handleProgrammerWriteOnServer)
        r.playToServer(KeyframeRequestPayload.TYPE, KeyframeRequestPayload.STREAM_CODEC,
            ::handleKeyframeRequestOnServer)
        r.playToServer(SerialInPayload.TYPE, SerialInPayload.STREAM_CODEC,
            ::handleSerialInOnServer)
        r.playToServer(SerialAmbientSubscribePayload.TYPE, SerialAmbientSubscribePayload.STREAM_CODEC,
            ::handleSerialAmbientOnServer)
        r.playToServer(TeletypePrintTestPayload.TYPE, TeletypePrintTestPayload.STREAM_CODEC,
            ::handleTeletypePrintTestOnServer)
        r.playToServer(SetupEditPayload.TYPE, SetupEditPayload.STREAM_CODEC,
            ::handleSetupEditOnServer)
        r.playToClient(SetupSyncPayload.TYPE, SetupSyncPayload.STREAM_CODEC,
            ::handleSetupSyncOnClient)
        r.playToClient(DisplayPayload.TYPE, DisplayPayload.STREAM_CODEC,
            ::handleDisplayOnClient)
        r.playToClient(DisplayDisposePayload.TYPE, DisplayDisposePayload.STREAM_CODEC,
            ::handleDisplayDisposeOnClient)
        r.playToClient(SoundFramePayload.TYPE, SoundFramePayload.STREAM_CODEC,
            ::handleSoundFrameOnClient)
        r.playToClient(SerialOutPayload.TYPE, SerialOutPayload.STREAM_CODEC,
            ::handleSerialOutOnClient)
    }

    /* ---------------- Handlers ---------------- */

    private fun handleInputOnServer(payload: MachineInputPayload, ctx: IPayloadContext) {
        val sp = ctx.player() as? ServerPlayer ?: return
        val mm = sp.containerMenu as? MachineMenu ?: return
        val state = MachineManager.getMachineState(mm.machineUuid) ?: return
        when (payload.kind) {
            MachineInputPayload.Kind.KEY_PRESS     -> state.keyboard?.press(payload.keyByte)
            MachineInputPayload.Kind.KEY_RELEASE   -> state.keyboard?.release(payload.keyByte)
            MachineInputPayload.Kind.MOUSE_PRESS   -> state.mouse?.press(payload.keyByte)
            MachineInputPayload.Kind.MOUSE_RELEASE -> state.mouse?.release(payload.keyByte)
            MachineInputPayload.Kind.MOUSE_SCROLL  -> state.mouse?.scroll(payload.keyByte)
            MachineInputPayload.Kind.MOUSE_MOVE    -> state.mouse?.move(payload.mouseX.toInt(), payload.mouseY.toInt())
            MachineInputPayload.Kind.MOUSE_PLACE   -> state.mouse?.place(payload.mouseX.toInt(), payload.mouseY.toInt())
        }
    }

    private fun handleResetOnServer(payload: MachineResetPayload, ctx: IPayloadContext) {
        val sp = ctx.player() as? ServerPlayer ?: return
        val menu = sp.containerMenu
        val handle: IMachineHandle = when (menu) {
            is MachineMenu -> menu.machineHandle ?: return
            // Power button on the component-editor screen: resolve the handle
            // through the case block entity directly.
            is ComputerCaseMenu -> menu.caseBE
            // Same story for the MCU board's install menu — the block entity
            // IS the handle. Without this branch the power button on the MCU
            // screen silently no-ops (packet sent, server drops it, DataSlot
            // never flips, button visual looks broken).
            is McuBoardMenu -> menu.mcu
            else -> return
        }
        if (payload.reset) handle.reset() else handle.power()
    }

    private fun handleProgrammerWriteOnServer(payload: FlashProgrammerWritePayload, ctx: IPayloadContext) {
        val sp = ctx.player() as? ServerPlayer ?: return
        val fp = sp.containerMenu as? FlashProgrammerMenu ?: return
        // Disk read hops to Util.ioPool(); the apply stage comes back on
        // the server thread, so reportStatus (which mutates a DataSlot)
        // is called safely.
        FlashProgrammerService.writeAsync(fp.prog).thenAccept(fp::reportStatus)
    }

    private fun handleKeyframeRequestOnServer(payload: KeyframeRequestPayload, ctx: IPayloadContext) {
        // No auth check — see KeyframeRequestPayload class javadoc. The
        // handler just flags a UUID; the BE's next broadcastFramebuffer
        // consumes the flag and forces an IDR before encoding.
        VideoKeyframeRequests.request(payload.machineUuid)
    }

    private fun handleDisplayOnClient(payload: DisplayPayload, ctx: IPayloadContext) {
        DisplayManager.acceptRemote(payload)
    }

    private fun handleDisplayDisposeOnClient(payload: DisplayDisposePayload, ctx: IPayloadContext) {
        DisplayManager.dispose(payload.machineUuid)
    }

    private fun handleSoundFrameOnClient(payload: SoundFramePayload, ctx: IPayloadContext) {
        SoundStreamPlayer.acceptRemote(payload)
    }

    private fun handleSerialOutOnClient(payload: SerialOutPayload, ctx: IPayloadContext) {
        SerialDispatcher.acceptRemote(payload.machineUuid, payload.bytes)
    }

    /**
     * Player typed into a VT100 screen. The target machine is
     * resolved from the player's open menu — the wire payload
     * carries no UUID, so a malicious client can't push bytes into
     * an arbitrary UART by guessing IDs. Same containerMenu-as-auth
     * trick MachineInputPayload uses for HID. Once accepted the
     * bytes go into the kernel UART RX queue verbatim; the guest's
     * tty driver does the line-discipline interpretation.
     */
    /**
     * Player's client wants kernel TX bytes for [machineUuid] even
     * without a TerminalMenu open — used by the in-world block-face
     * renderer. Auth gate: we only honor the subscribe if a VT100
     * BE is currently bound to this UUID somewhere in the world.
     */
    private fun handleSerialAmbientOnServer(payload: SerialAmbientSubscribePayload, ctx: IPayloadContext) {
        val sp = ctx.player() as? ServerPlayer ?: return
        if (payload.subscribe) {
            TerminalSubscriberRegistry.addAmbient(payload.machineUuid, sp)
        } else {
            TerminalSubscriberRegistry.removeAmbient(payload.machineUuid, sp)
        }
    }

    /**
     * Player clicked "Print Test Page" on a Teletype GUI. Resolve via
     * containerMenu (same auth pattern as the other Serial-* handlers).
     */
    private fun handleTeletypePrintTestOnServer(@Suppress("UNUSED_PARAMETER") payload: TeletypePrintTestPayload, ctx: IPayloadContext) {
        val sp = ctx.player() as? ServerPlayer ?: return
        val menu = sp.containerMenu as? lekkit.scev.menu.TeletypeMenu ?: return
        menu.be.printText(lekkit.scev.blockentity.TeletypeBlockEntity.TEST_PAGE)
    }

    private fun handleSerialInOnServer(payload: SerialInPayload, ctx: IPayloadContext) {
        val sp = ctx.player() as? ServerPlayer ?: return
        val menu = sp.containerMenu as? TerminalMenu ?: return
        val uuid = menu.machineUuid ?: return
        ScevRpcManager.get(uuid)?.feedKernelConsoleInput(payload.bytes)
    }

    /**
     * Authority gate for Setup edits. Resolves the target block from the
     * sender's open [TerminalMenu] (same trick the SerialIn handler uses
     * — never trust a client-supplied UUID for write authority), forwards
     * to the BE which validates structurally, persists, and broadcasts a
     * [SetupSyncPayload] to every viewer.
     */
    private fun handleSetupEditOnServer(payload: SetupEditPayload, ctx: IPayloadContext) {
        val sp = ctx.player() as? ServerPlayer ?: return
        val menu = sp.containerMenu as? TerminalMenu ?: return
        val be = sp.serverLevel().getBlockEntity(menu.blockPos) as? TerminalBlockEntity
            ?: return
        be.applySetupEdit(payload.state)
    }

    /**
     * Hand the synced state off to the [SetupSyncDispatcher] (per-UUID
     * subscriber map, mirrors [SerialDispatcher] for kernel TX). The
     * client-side dispatcher routes to the open TerminalScreen if any.
     */
    private fun handleSetupSyncOnClient(payload: SetupSyncPayload, ctx: IPayloadContext) {
        SetupSyncDispatcher.acceptRemote(payload.machineUuid, payload.state)
    }
}
