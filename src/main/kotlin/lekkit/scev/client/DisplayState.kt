/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client

import com.mojang.blaze3d.platform.NativeImage
import java.nio.ByteBuffer
import java.util.UUID
import lekkit.scev.machine.FramebufferView
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.server.MachineState
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation

/**
 * Per-machine framebuffer uploaded to a [DynamicTexture]. Two modes:
 *
 *  - **Single-player (local):** reads straight from the server-side
 *    [MachineState.getDisplay] DMA buffer every frame.
 *  - **Multiplayer (remote):** accepts pixel blobs through
 *    [updateRemoteBuffer].
 *
 * Pixel format: source is little-endian A8R8G8B8 (byte order `B, G, R, A`);
 * destination [NativeImage] stores bytes as `R, G, B, A`. [PixelConverter.bgraToRgba]
 * does the swap.
 */
class DisplayState private constructor(
    @get:JvmName("getUuid")   val uuid: UUID,
    @get:JvmName("getWidth")  val width: Int,
    @get:JvmName("getHeight") val height: Int,
    private val remoteBuffer: ByteArray?,
    private val localMachine: MachineState?,
) {
    private var image: NativeImage? = null
    private var texture: DynamicTexture? = null
    private var textureId: ResourceLocation? = null
    private var dirty: Boolean = true

    /** Constructor for the multiplayer / remote case. */
    internal constructor(uuid: UUID, width: Int, height: Int) :
        this(uuid, width, height, ByteArray(width * height * 4), null)

    /** Constructor for the singleplayer / local-machine case. */
    internal constructor(machine: MachineState) : this(
        machine.getUuid(),
        machine.display?.width() ?: throw IllegalArgumentException("MachineState has no display"),
        machine.display!!.height(),
        null,
        machine,
    )

    fun isLocal(): Boolean = localMachine != null

    /**
     * True if this is a singleplayer DisplayState whose backing
     * [MachineState] has been destroyed (VM was powered off). The cache
     * entry is dead; [DisplayManager.get] evicts stale entries before
     * returning so the next lookup can construct fresh against the new
     * MachineState (or return null if no VM is running for the UUID).
     */
    val isStale: Boolean
        get() = isLocal() && !localMachine!!.isValid

    @Synchronized internal fun updateRemoteBuffer(src: ByteArray) {
        val buf = remoteBuffer ?: return
        if (src.size != buf.size) return
        System.arraycopy(src, 0, buf, 0, src.size)
        dirty = true
    }

    /**
     * Returns the texture id for this framebuffer, uploading pixels if
     * dirty. Must be called on the render thread.
     */
    @Synchronized fun getOrUploadTexture(): ResourceLocation {
        if (texture == null) {
            val img = NativeImage(NativeImage.Format.RGBA, width, height, false)
            image = img
            texture = DynamicTexture(img)
            textureId = ScalarEvolution.rl("display/" + uuid.toString().replace('-', '_'))
            Minecraft.getInstance().textureManager.register(textureId, texture)
        }
        if (dirty || isLocal()) {
            refreshPixels()
            texture!!.upload()
            dirty = false
        }
        return textureId!!
    }

    private fun refreshPixels() {
        val src: ByteBuffer = if (isLocal()) {
            val fb: FramebufferView? = localMachine!!.display
            if (fb == null) {
                // VM was torn down (power-off, chunk-unload free-all, etc.).
                // Paint the texture black so the last frame from the old VM
                // doesn't linger — stale DisplayStates are evicted from
                // DisplayManager on the next get(), but a mid-frame clear
                // avoids the "screen shows prior boot" flash when the user
                // re-opens the MachineScreen.
                clearPixels()
                return
            }
            fb.pixels()
        } else {
            ByteBuffer.wrap(remoteBuffer!!)
        }
        PixelConverter.bgraToRgba(src, image!!, width, height)
    }

    /** Zero every pixel in the NativeImage (opaque black). */
    private fun clearPixels() {
        val img = image ?: return
        for (y in 0 until height) for (x in 0 until width) img.setPixelRGBA(x, y, 0xFF000000.toInt())
    }

    @Synchronized fun destroy() {
        texture?.close()
        image = null
        texture = null
    }
}
