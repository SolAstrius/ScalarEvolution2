/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

import java.nio.ByteBuffer

/**
 * Backend-agnostic view over a framebuffer's pixel storage.
 *
 * The underlying buffer format is fixed by contract: 32 bits per
 * pixel, little-endian `A8R8G8B8` layout — that is, each 4-byte pixel
 * in memory reads as `[B, G, R, A]` byte-by-byte. This matches RVVM's
 * native output and simplifies the client pixel-copy path.
 *
 * Implementations must return a buffer whose backing storage is reused
 * across calls (direct buffer with stable address in production, heap
 * buffer in tests). The buffer's position is reset to 0 by each call
 * so callers can [ByteBuffer.get] straight through.
 *
 * Intentionally small: downstream consumers (client rendering, tests)
 * need width, height, and a raw byte view. They do not need to know
 * whether the buffer lives behind JNI, on the heap, or in a stubbed
 * test fixture.
 */
interface FramebufferView {
    fun width(): Int

    fun height(): Int

    /** Total pixel storage in bytes. Always equals `width * height * 4`. */
    fun byteSize(): Int = width() * height() * PIXEL_SIZE_BYTES

    /**
     * The raw pixel buffer in `B, G, R, A` byte order (little-endian
     * A8R8G8B8).
     *
     * Position is reset to 0 on each call; mark/limit left untouched.
     * Mutating the buffer's content is allowed — the backend sees the
     * same memory. Mutating the buffer's position is reset by the next
     * call.
     */
    fun pixels(): ByteBuffer

    companion object {
        const val PIXEL_SIZE_BYTES: Int = 4
    }
}
