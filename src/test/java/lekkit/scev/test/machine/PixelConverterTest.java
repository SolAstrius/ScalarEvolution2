/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import lekkit.scev.client.PixelConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates the BGRA -> RGBA pixel format conversion used in
 * {@link lekkit.scev.client.DisplayState}.
 *
 * <p>The source buffer is RVVM's native format: little-endian A8R8G8B8, which
 * means bytes in memory are {@code [B, G, R, A]} per pixel. The destination
 * is NeoForge's {@link net.minecraft.client.renderer.texture.DynamicTexture}
 * which uses {@link com.mojang.blaze3d.platform.NativeImage.Format#RGBA}
 * (bytes {@code [R, G, B, A]}).
 *
 * <p>Bugs in the channel swap show up as "colors are wrong" in-game. This
 * test pins down the exact byte order.
 */
class PixelConverterTest {

    @Test
    @DisplayName("Single pixel: BGRA (10, 20, 30, 40) -> RGBA (30, 20, 10, 40)")
    void singlePixel() {
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put(0, (byte) 10);  // B
        src.put(1, (byte) 20);  // G
        src.put(2, (byte) 30);  // R
        src.put(3, (byte) 40);  // A

        byte[] out = new byte[4];
        PixelConverter.bgraToRgbaBytes(src, out, 1, 1);

        assertEquals(30, out[0] & 0xFF, "R channel");
        assertEquals(20, out[1] & 0xFF, "G channel");
        assertEquals(10, out[2] & 0xFF, "B channel");
        assertEquals(40, out[3] & 0xFF, "A channel");
    }

    @Test
    @DisplayName("Multiple pixels convert left-to-right, top-to-bottom")
    void multiplePixels() {
        // 2x2 image, each pixel has a distinct BGRA.
        ByteBuffer src = ByteBuffer.allocate(2 * 2 * 4);
        int[][] pixels = {
                { 0x01, 0x02, 0x03, 0x04 },  // (0,0) BGRA
                { 0x11, 0x12, 0x13, 0x14 },  // (1,0)
                { 0x21, 0x22, 0x23, 0x24 },  // (0,1)
                { 0x31, 0x32, 0x33, 0x34 },  // (1,1)
        };
        for (int i = 0; i < pixels.length; i++) {
            src.put(i * 4,     (byte) pixels[i][0]);
            src.put(i * 4 + 1, (byte) pixels[i][1]);
            src.put(i * 4 + 2, (byte) pixels[i][2]);
            src.put(i * 4 + 3, (byte) pixels[i][3]);
        }

        byte[] out = new byte[2 * 2 * 4];
        PixelConverter.bgraToRgbaBytes(src, out, 2, 2);

        for (int i = 0; i < pixels.length; i++) {
            assertEquals(pixels[i][2], out[i * 4]     & 0xFF, "pixel " + i + " R");
            assertEquals(pixels[i][1], out[i * 4 + 1] & 0xFF, "pixel " + i + " G");
            assertEquals(pixels[i][0], out[i * 4 + 2] & 0xFF, "pixel " + i + " B");
            assertEquals(pixels[i][3], out[i * 4 + 3] & 0xFF, "pixel " + i + " A");
        }
    }

    @Test
    @DisplayName("All-zero source -> all-zero destination")
    void allZero() {
        ByteBuffer src = ByteBuffer.allocate(4);
        byte[] out = new byte[4];
        PixelConverter.bgraToRgbaBytes(src, out, 1, 1);
        for (int i = 0; i < 4; i++) assertEquals(0, out[i]);
    }

    @Test
    @DisplayName("Opaque solid color survives conversion unchanged except for channel order")
    void opaqueSolidColor() {
        // Source: pure red in BGRA is B=0, G=0, R=255, A=255.
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put(0, (byte) 0);
        src.put(1, (byte) 0);
        src.put(2, (byte) 0xFF);
        src.put(3, (byte) 0xFF);

        byte[] out = new byte[4];
        PixelConverter.bgraToRgbaBytes(src, out, 1, 1);
        assertEquals(0xFF, out[0] & 0xFF);
        assertEquals(0, out[1]);
        assertEquals(0, out[2]);
        assertEquals(0xFF, out[3] & 0xFF);
    }
}
