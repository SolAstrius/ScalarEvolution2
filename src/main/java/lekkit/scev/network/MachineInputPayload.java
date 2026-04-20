/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.network;

import io.netty.buffer.ByteBuf;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> Server: keyboard / mouse input for an open {@link lekkit.scev.menu.MachineMenu}.
 *
 * <p>Wire format: one byte of {@link Kind}, then either a single key/button byte, or two shorts
 * for mouse coordinates.
 */
public record MachineInputPayload(Kind kind, byte keyByte, short mouseX, short mouseY)
        implements CustomPacketPayload {

    public enum Kind {
        KEY_PRESS, KEY_RELEASE, MOUSE_MOVE, MOUSE_PLACE, MOUSE_SCROLL, MOUSE_PRESS, MOUSE_RELEASE
    }

    public static final CustomPacketPayload.Type<MachineInputPayload> TYPE =
            new CustomPacketPayload.Type<>(ScalarEvolution.rl("machine_input"));

    public static final StreamCodec<ByteBuf, MachineInputPayload> STREAM_CODEC =
            StreamCodec.of(MachineInputPayload::encode, MachineInputPayload::decode);

    public static MachineInputPayload keyPress(byte key)    { return new MachineInputPayload(Kind.KEY_PRESS, key, (short)0, (short)0); }
    public static MachineInputPayload keyRelease(byte key)  { return new MachineInputPayload(Kind.KEY_RELEASE, key, (short)0, (short)0); }
    public static MachineInputPayload mousePress(byte btn)  { return new MachineInputPayload(Kind.MOUSE_PRESS, btn, (short)0, (short)0); }
    public static MachineInputPayload mouseRelease(byte btn){ return new MachineInputPayload(Kind.MOUSE_RELEASE, btn, (short)0, (short)0); }
    public static MachineInputPayload mouseScroll(byte d)   { return new MachineInputPayload(Kind.MOUSE_SCROLL, d, (short)0, (short)0); }
    public static MachineInputPayload mouseMove(short x, short y)  { return new MachineInputPayload(Kind.MOUSE_MOVE, (byte)0, x, y); }
    public static MachineInputPayload mousePlace(short x, short y) { return new MachineInputPayload(Kind.MOUSE_PLACE, (byte)0, x, y); }

    private static void encode(ByteBuf buf, MachineInputPayload p) {
        buf.writeByte(p.kind.ordinal());
        switch (p.kind) {
            case KEY_PRESS, KEY_RELEASE, MOUSE_PRESS, MOUSE_RELEASE, MOUSE_SCROLL -> buf.writeByte(p.keyByte);
            case MOUSE_MOVE, MOUSE_PLACE -> { buf.writeShort(p.mouseX); buf.writeShort(p.mouseY); }
        }
    }

    private static MachineInputPayload decode(ByteBuf buf) {
        int kindOrd = buf.readByte() & 0xFF;
        if (kindOrd >= Kind.values().length) kindOrd = 0;
        Kind kind = Kind.values()[kindOrd];
        byte key = 0;
        short mx = 0, my = 0;
        switch (kind) {
            case KEY_PRESS, KEY_RELEASE, MOUSE_PRESS, MOUSE_RELEASE, MOUSE_SCROLL -> key = buf.readByte();
            case MOUSE_MOVE, MOUSE_PLACE -> { mx = buf.readShort(); my = buf.readShort(); }
        }
        return new MachineInputPayload(kind, key, mx, my);
    }

    @Override public Type<MachineInputPayload> type() { return TYPE; }
}
