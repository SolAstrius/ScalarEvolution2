/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

import net.minecraft.core.Direction

/**
 * Translates between two different 6-bit pin encodings.
 *
 * **World-oriented** pins use [Direction.ordinal] as the bit index:
 *
 * ```
 *   bit 0 = DOWN, 1 = UP, 2 = NORTH, 3 = SOUTH, 4 = WEST, 5 = EAST
 * ```
 *
 * This is the format Minecraft's neighbour-change / signal queries
 * speak.
 *
 * **Block-relative** pins are what the guest firmware inside the VM
 * sees:
 *
 * ```
 *   bit 0 = FRONT, 1 = BACK, 2 = LEFT, 3 = RIGHT, 4 = TOP, 5 = BOTTOM
 * ```
 *
 * FRONT is the block's `HORIZONTAL_FACING`. LEFT/RIGHT are the two
 * cardinal directions perpendicular to it (looking down). TOP/BOTTOM
 * are always world UP/DOWN — for our horizontally-faced blocks
 * there's no Y-axis rotation to consider.
 *
 * Keeping guest firmware on the block-relative map means authors get
 * a stable "port layout": writing to FRONT always emits on the front
 * face no matter which way the block was placed, so wrench-rotating a
 * case doesn't break the firmware.
 *
 * The raw SiFive GPIO device inside RVVM is width-32, but we only
 * wire the low 6 bits. Anything above bit 5 is dropped.
 */
object GpioPinMap {
    // Block-relative bit positions. Keep in sync with the kdoc above.
    const val BIT_FRONT: Int = 0
    const val BIT_BACK: Int = 1
    const val BIT_LEFT: Int = 2
    const val BIT_RIGHT: Int = 3
    const val BIT_TOP: Int = 4
    const val BIT_BOTTOM: Int = 5

    /** Low 6 bits — the mask applied on every input and output boundary. */
    const val PIN_MASK: Int = 0x3F

    /**
     * Project a world-oriented pin mask onto the block-relative map
     * given the block's horizontal facing. The inverse is
     * [relativeToWorld].
     *
     * @param worldPins `bit N = Direction.ordinal() N`, only low 6
     *                  bits honoured.
     * @param facing    the block's horizontal FACING. Vertical
     *                  directions fall through to TOP/BOTTOM
     *                  unchanged.
     * @return block-relative pin mask.
     */
    @JvmStatic
    fun worldToRelative(worldPins: Int, facing: Direction): Int {
        val front = facing
        val back  = facing.opposite
        val left  = facing.counterClockWise
        val right = facing.clockWise
        var out = 0
        if ((worldPins shr front.ordinal) and 1 != 0) out = out or (1 shl BIT_FRONT)
        if ((worldPins shr back.ordinal) and 1 != 0)  out = out or (1 shl BIT_BACK)
        if ((worldPins shr left.ordinal) and 1 != 0)  out = out or (1 shl BIT_LEFT)
        if ((worldPins shr right.ordinal) and 1 != 0) out = out or (1 shl BIT_RIGHT)
        if ((worldPins shr Direction.UP.ordinal) and 1 != 0)   out = out or (1 shl BIT_TOP)
        if ((worldPins shr Direction.DOWN.ordinal) and 1 != 0) out = out or (1 shl BIT_BOTTOM)
        return out
    }

    /**
     * Project a block-relative pin mask back onto world directions.
     * Inverse of [worldToRelative] — a round trip through both for any
     * legal 6-bit input + horizontal facing must equal the input.
     *
     * @param relPins block-relative mask (bit 0=FRONT, ..., bit 5=BOTTOM).
     * @param facing  the block's horizontal FACING.
     * @return world-oriented pin mask.
     */
    @JvmStatic
    fun relativeToWorld(relPins: Int, facing: Direction): Int {
        val front = facing
        val back  = facing.opposite
        val left  = facing.counterClockWise
        val right = facing.clockWise
        var out = 0
        if ((relPins shr BIT_FRONT) and 1 != 0)  out = out or (1 shl front.ordinal)
        if ((relPins shr BIT_BACK) and 1 != 0)   out = out or (1 shl back.ordinal)
        if ((relPins shr BIT_LEFT) and 1 != 0)   out = out or (1 shl left.ordinal)
        if ((relPins shr BIT_RIGHT) and 1 != 0)  out = out or (1 shl right.ordinal)
        if ((relPins shr BIT_TOP) and 1 != 0)    out = out or (1 shl Direction.UP.ordinal)
        if ((relPins shr BIT_BOTTOM) and 1 != 0) out = out or (1 shl Direction.DOWN.ordinal)
        return out
    }
}
