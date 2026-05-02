/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client

import com.mojang.blaze3d.platform.NativeImage
import java.nio.ByteBuffer

/**
 * Pixel-format conversion between the backend framebuffer (`B, G, R, A`
 * bytes in memory — little-endian A8R8G8B8 per RVVM's convention) and
 * NeoForge's [NativeImage] (`R, G, B, A` bytes in memory — ABGR-packed
 * int via [NativeImage.setPixelRGBA]).
 *
 * Split out of [DisplayState] so it's unit-testable without booting
 * Minecraft's graphics stack. The math is small but easy to get backwards.
 */
object PixelConverter {
    /**
     * Read [width]×[height] pixels from [src] in BGRA byte order and write
     * them into [dst] at the given resolution. [dst] must be at least
     * `width × height`.
     */
    @JvmStatic fun bgraToRgba(src: ByteBuffer, dst: NativeImage, width: Int, height: Int) {
        src.rewind()
        for (y in 0 until height) for (x in 0 until width) {
            val b = src.get().toInt() and 0xFF
            val g = src.get().toInt() and 0xFF
            val r = src.get().toInt() and 0xFF
            val a = src.get().toInt() and 0xFF
            // setPixelRGBA takes an ABGR-packed int (little-endian),
            // which stores bytes as {R, G, B, A} in memory.
            dst.setPixelRGBA(x, y, (a shl 24) or (b shl 16) or (g shl 8) or r)
        }
    }

    /**
     * Testable variant: writes the RGBA bytes directly into [out] as a
     * flat array (`out` must be at least `width × height × 4` bytes,
     * each pixel as `[R, G, B, A]`). Used by tests to verify conversion
     * without instantiating a [NativeImage].
     */
    @JvmStatic fun bgraToRgbaBytes(src: ByteBuffer, out: ByteArray, width: Int, height: Int) {
        src.rewind()
        var w = 0
        repeat(width * height) {
            val b = src.get()
            val g = src.get()
            val r = src.get()
            val a = src.get()
            out[w++] = r
            out[w++] = g
            out[w++] = b
            out[w++] = a
        }
    }
}
