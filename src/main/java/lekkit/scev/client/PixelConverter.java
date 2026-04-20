/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;

/**
 * Pixel-format conversion between the backend framebuffer ({@code B, G, R, A}
 * bytes in memory — little-endian A8R8G8B8 per RVVM's convention) and
 * NeoForge's {@link NativeImage} ({@code R, G, B, A} bytes in memory — ABGR
 * packed int via {@link NativeImage#setPixelRGBA}).
 *
 * <p>Split out of {@link DisplayState} so it's unit-testable without booting
 * Minecraft's graphics stack. The math is small but easy to get backwards.
 */
public final class PixelConverter {
    private PixelConverter() {}

    /**
     * Read {@code width*height} pixels starting from {@code src.position()} in
     * BGRA byte order and write them into {@code dst} at the given resolution.
     * {@code dst} must be at least {@code width x height}.
     */
    public static void bgraToRgba(ByteBuffer src, NativeImage dst, int width, int height) {
        src.rewind();
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int b = src.get() & 0xFF;
                int g = src.get() & 0xFF;
                int r = src.get() & 0xFF;
                int a = src.get() & 0xFF;
                // NativeImage.setPixelRGBA takes an ABGR-packed int (little-endian),
                // which stores bytes as {R, G, B, A} in memory.
                dst.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
    }

    /**
     * Testable variant: writes the RGBA bytes directly into {@code out} as a
     * flat array. {@code out} must be at least {@code width * height * 4}
     * bytes. Each pixel is written as {@code [R, G, B, A]}.
     *
     * <p>Used by the unit tests to verify conversion correctness without
     * instantiating a {@link NativeImage}.
     */
    public static void bgraToRgbaBytes(ByteBuffer src, byte[] out, int width, int height) {
        src.rewind();
        int w = 0;
        for (int i = 0; i < width * height; i++) {
            int b = src.get() & 0xFF;
            int g = src.get() & 0xFF;
            int r = src.get() & 0xFF;
            int a = src.get() & 0xFF;
            out[w++] = (byte) r;
            out[w++] = (byte) g;
            out[w++] = (byte) b;
            out[w++] = (byte) a;
        }
    }
}
