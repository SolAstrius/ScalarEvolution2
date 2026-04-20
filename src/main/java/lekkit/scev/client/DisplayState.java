/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.util.UUID;
import lekkit.scev.machine.FramebufferView;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.server.MachineState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Per-machine framebuffer that's uploaded to a {@link DynamicTexture}. Supports two modes:
 * <ul>
 *   <li><b>Single-player (local)</b>: reads straight from the server-side
 *       {@link MachineState#getDisplay()}'s DMA buffer every frame.</li>
 *   <li><b>Multiplayer (remote)</b>: accepts pixel blobs through
 *       {@link #updateRemoteBuffer(byte[])}.</li>
 * </ul>
 *
 * <p>Pixel format: the source buffer is little-endian A8R8G8B8 (byte order
 * {@code B, G, R, A}); the destination {@link NativeImage} stores bytes as
 * {@code R, G, B, A}. {@link PixelConverter#bgraToRgba} does the swap.
 */
public class DisplayState {
    private final UUID uuid;
    private final int width;
    private final int height;

    private final byte[] remoteBuffer;           // only used when !isLocal()
    private final MachineState localMachine;     // only used when isLocal()

    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private boolean dirty = true;

    DisplayState(UUID uuid, int width, int height) {
        this.uuid = uuid;
        this.width = width;
        this.height = height;
        this.remoteBuffer = new byte[width * height * 4];
        this.localMachine = null;
    }

    DisplayState(MachineState machine) {
        FramebufferView fb = machine.getDisplay();
        if (fb == null) throw new IllegalArgumentException("MachineState has no display");
        this.uuid = machine.getUUID();
        this.width = fb.width();
        this.height = fb.height();
        this.remoteBuffer = null;
        this.localMachine = machine;
    }

    public UUID getUuid() { return uuid; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isLocal() { return localMachine != null; }

    synchronized void updateRemoteBuffer(byte[] src) {
        if (remoteBuffer == null || src.length != remoteBuffer.length) return;
        System.arraycopy(src, 0, remoteBuffer, 0, src.length);
        dirty = true;
    }

    /**
     * Returns the texture id for this framebuffer, uploading pixels if dirty.
     * Must be called on the render thread.
     */
    public synchronized ResourceLocation getOrUploadTexture() {
        if (texture == null) {
            image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            texture = new DynamicTexture(image);
            textureId = ScalarEvolution.rl("display/" + uuid.toString().replace('-', '_'));
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
        }
        if (dirty || isLocal()) {
            refreshPixels();
            texture.upload();
            dirty = false;
        }
        return textureId;
    }

    private void refreshPixels() {
        ByteBuffer src;
        if (isLocal()) {
            FramebufferView fb = localMachine.getDisplay();
            if (fb == null) return;
            src = fb.pixels();
        } else {
            src = ByteBuffer.wrap(remoteBuffer);
        }
        PixelConverter.bgraToRgba(src, image, width, height);
    }

    public synchronized void destroy() {
        if (texture != null) texture.close();
        image = null;
        texture = null;
    }
}
