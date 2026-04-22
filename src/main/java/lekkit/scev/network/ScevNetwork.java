/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network;

import lekkit.scev.server.IMachineHandle;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers every custom packet payload and wires each to its server- or
 * client-side handler. Payload classes live under {@code lekkit.scev.network};
 * add a new {@code r.playToServer} / {@code r.playToClient} entry below
 * when introducing one.
 */
public final class ScevNetwork {
    private ScevNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(ScevNetwork::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("1").optional();

        r.playToServer(MachineInputPayload.TYPE, MachineInputPayload.STREAM_CODEC,
                ScevNetwork::handleInputOnServer);
        r.playToServer(MachineResetPayload.TYPE, MachineResetPayload.STREAM_CODEC,
                ScevNetwork::handleResetOnServer);
        r.playToServer(FlashProgrammerWritePayload.TYPE, FlashProgrammerWritePayload.STREAM_CODEC,
                ScevNetwork::handleProgrammerWriteOnServer);
        r.playToServer(KeyframeRequestPayload.TYPE, KeyframeRequestPayload.STREAM_CODEC,
                ScevNetwork::handleKeyframeRequestOnServer);
        r.playToClient(DisplayPayload.TYPE, DisplayPayload.STREAM_CODEC,
                ScevNetwork::handleDisplayOnClient);
        r.playToClient(SoundFramePayload.TYPE, SoundFramePayload.STREAM_CODEC,
                ScevNetwork::handleSoundFrameOnClient);
    }

    /* ---------------- Handlers ---------------- */

    private static void handleInputOnServer(MachineInputPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        AbstractContainerMenu menu = sp.containerMenu;
        if (!(menu instanceof lekkit.scev.menu.MachineMenu mm)) return;
        MachineState state = MachineManager.getMachineState(mm.getMachineUuid());
        if (state == null) return;
        switch (payload.kind()) {
            case KEY_PRESS     -> { if (state.getKeyboard() != null) state.getKeyboard().press(payload.keyByte()); }
            case KEY_RELEASE   -> { if (state.getKeyboard() != null) state.getKeyboard().release(payload.keyByte()); }
            case MOUSE_PRESS   -> { if (state.getMouse() != null) state.getMouse().press(payload.keyByte()); }
            case MOUSE_RELEASE -> { if (state.getMouse() != null) state.getMouse().release(payload.keyByte()); }
            case MOUSE_SCROLL  -> { if (state.getMouse() != null) state.getMouse().scroll(payload.keyByte()); }
            case MOUSE_MOVE    -> { if (state.getMouse() != null) state.getMouse().move(payload.mouseX(), payload.mouseY()); }
            case MOUSE_PLACE   -> { if (state.getMouse() != null) state.getMouse().place(payload.mouseX(), payload.mouseY()); }
        }
    }

    private static void handleResetOnServer(MachineResetPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        AbstractContainerMenu menu = sp.containerMenu;
        IMachineHandle handle = null;
        if (menu instanceof lekkit.scev.menu.MachineMenu mm) {
            handle = mm.getMachineHandle();
        } else if (menu instanceof lekkit.scev.menu.ComputerCaseMenu cc) {
            // Power button on the component-editor screen: resolve the handle
            // through the case block entity directly.
            handle = cc.getCaseBE();
        } else if (menu instanceof lekkit.scev.menu.McuBoardMenu mcu) {
            // Same story for the MCU board's install menu — the block entity
            // IS the handle. Without this branch the power button on the MCU
            // screen silently no-ops (packet sent, server drops it, DataSlot
            // never flips, button visual looks broken).
            handle = mcu.getMcu();
        }
        if (handle == null) return;
        if (payload.reset()) handle.reset(); else handle.power();
    }

    private static void handleProgrammerWriteOnServer(FlashProgrammerWritePayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        AbstractContainerMenu menu = sp.containerMenu;
        if (!(menu instanceof lekkit.scev.menu.FlashProgrammerMenu fp)) return;
        // Disk read hops to Util.ioPool(); the apply stage comes back on
        // the server thread, so reportStatus (which mutates a DataSlot)
        // is called safely.
        lekkit.scev.server.FlashProgrammerService.writeAsync(fp.getProgrammer())
                .thenAccept(fp::reportStatus);
    }

    private static void handleKeyframeRequestOnServer(KeyframeRequestPayload payload, IPayloadContext ctx) {
        // No auth check — see KeyframeRequestPayload class javadoc. The
        // handler just flags a UUID; the BE's next broadcastFramebuffer
        // consumes the flag and forces an IDR before encoding.
        lekkit.scev.server.VideoKeyframeRequests.request(payload.machineUuid());
    }

    private static void handleDisplayOnClient(DisplayPayload payload, IPayloadContext ctx) {
        lekkit.scev.client.DisplayManager.acceptRemote(payload);
    }

    private static void handleSoundFrameOnClient(SoundFramePayload payload, IPayloadContext ctx) {
        lekkit.scev.client.SoundStreamPlayer.acceptRemote(payload);
    }
}
