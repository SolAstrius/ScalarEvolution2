/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

import net.minecraft.core.Direction;

/**
 * Translates between two different 6-bit pin encodings.
 *
 * <p><b>World-oriented</b> pins use {@link Direction#ordinal()} as the bit
 * index:
 * <pre>
 *   bit 0 = DOWN, 1 = UP, 2 = NORTH, 3 = SOUTH, 4 = WEST, 5 = EAST
 * </pre>
 * This is the format Minecraft's neighbour-change / signal queries speak.
 *
 * <p><b>Block-relative</b> pins are what the guest firmware inside the VM
 * sees:
 * <pre>
 *   bit 0 = FRONT, 1 = BACK, 2 = LEFT, 3 = RIGHT, 4 = TOP, 5 = BOTTOM
 * </pre>
 * FRONT is the block's {@code HORIZONTAL_FACING}. LEFT/RIGHT are the two
 * cardinal directions perpendicular to it (looking down). TOP/BOTTOM are
 * always world UP/DOWN — for our horizontally-faced blocks there's no Y-axis
 * rotation to consider.
 *
 * <p>Keeping guest firmware on the block-relative map means authors get a
 * stable "port layout": writing to FRONT always emits on the front face no
 * matter which way the block was placed, so wrench-rotating a case doesn't
 * break the firmware.
 *
 * <p>The raw SiFive GPIO device inside RVVM is width-32, but we only wire
 * the low 6 bits. Anything above bit 5 is dropped.
 */
public final class GpioPinMap {
    private GpioPinMap() {}

    // Block-relative bit positions. Keep in sync with the javadoc above.
    public static final int BIT_FRONT  = 0;
    public static final int BIT_BACK   = 1;
    public static final int BIT_LEFT   = 2;
    public static final int BIT_RIGHT  = 3;
    public static final int BIT_TOP    = 4;
    public static final int BIT_BOTTOM = 5;

    /** Low 6 bits — the mask applied on every input and output boundary. */
    public static final int PIN_MASK = 0x3F;

    /**
     * Project a world-oriented pin mask onto the block-relative map given the
     * block's horizontal facing. The inverse is {@link #relativeToWorld}.
     *
     * @param worldPins {@code bit N = Direction.ordinal() N}, only low 6 bits honoured.
     * @param facing    the block's horizontal FACING. Vertical directions fall through
     *                  to TOP/BOTTOM unchanged.
     * @return block-relative pin mask.
     */
    public static int worldToRelative(int worldPins, Direction facing) {
        Direction front = facing;
        Direction back  = facing.getOpposite();
        Direction left  = facing.getCounterClockWise();
        Direction right = facing.getClockWise();
        int out = 0;
        if (((worldPins >> front.ordinal())        & 1) != 0) out |= 1 << BIT_FRONT;
        if (((worldPins >> back.ordinal())         & 1) != 0) out |= 1 << BIT_BACK;
        if (((worldPins >> left.ordinal())         & 1) != 0) out |= 1 << BIT_LEFT;
        if (((worldPins >> right.ordinal())        & 1) != 0) out |= 1 << BIT_RIGHT;
        if (((worldPins >> Direction.UP.ordinal())   & 1) != 0) out |= 1 << BIT_TOP;
        if (((worldPins >> Direction.DOWN.ordinal()) & 1) != 0) out |= 1 << BIT_BOTTOM;
        return out;
    }

    /**
     * Project a block-relative pin mask back onto world directions. Inverse
     * of {@link #worldToRelative} — a round trip through both for any legal
     * 6-bit input + horizontal facing must equal the input.
     *
     * @param relPins block-relative mask (bit 0=FRONT, ..., bit 5=BOTTOM).
     * @param facing  the block's horizontal FACING.
     * @return world-oriented pin mask.
     */
    public static int relativeToWorld(int relPins, Direction facing) {
        Direction front = facing;
        Direction back  = facing.getOpposite();
        Direction left  = facing.getCounterClockWise();
        Direction right = facing.getClockWise();
        int out = 0;
        if (((relPins >> BIT_FRONT)  & 1) != 0) out |= 1 << front.ordinal();
        if (((relPins >> BIT_BACK)   & 1) != 0) out |= 1 << back.ordinal();
        if (((relPins >> BIT_LEFT)   & 1) != 0) out |= 1 << left.ordinal();
        if (((relPins >> BIT_RIGHT)  & 1) != 0) out |= 1 << right.ordinal();
        if (((relPins >> BIT_TOP)    & 1) != 0) out |= 1 << Direction.UP.ordinal();
        if (((relPins >> BIT_BOTTOM) & 1) != 0) out |= 1 << Direction.DOWN.ordinal();
        return out;
    }
}
