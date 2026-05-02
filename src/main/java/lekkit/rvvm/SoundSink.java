/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package lekkit.rvvm;

/**
 * Callback invoked by RVVM's HDA stream worker with raw PCM chunks.
 *
 * <p>Passed to {@link SoundHDA}'s constructor; the JNI layer caches the
 * {@link #onAudio(byte[])} method ID via reflection. Any class that
 * declares a public method with the matching signature works — a formal
 * interface isn't required on the C side, but having one here makes the
 * Java callsites self-documenting.
 *
 * <p><b>Threading:</b> {@code onAudio} runs on RVVM's HDA stream worker
 * thread, not a Minecraft server thread. Implementations must not touch
 * Minecraft game state directly — buffer into a thread-safe queue and
 * drain from the server tick, or hop to the server executor.
 *
 * <p><b>Call rate.</b> Determined by the guest's BDL period. Linux's
 * default ALSA period is 10–20 ms, giving ~50–100 callbacks/sec; chunk
 * size is whatever the period rounds to in samples (e.g. 480 frames per
 * 10 ms at 48 kHz). Each callback allocates a fresh {@code byte[]} on
 * the C side via {@code NewByteArray}, so GC pressure is real but
 * manageable. The HDA stream worker caps individual writes at 4096
 * frames internally, so a guest configuring an unusually large period
 * still produces multiple bounded chunks.
 *
 * <p><b>PCM format.</b> 16-bit signed little-endian, mono. Sample rate
 * is data-driven: RVVM's HDA codec advertises 44.1 / 48 / 88.2 / 96 kHz
 * and the worker derives bytes/sec from whichever rate the guest
 * configures via SDnFMT. 48 kHz is the common case (Linux default).
 * Mono is enforced by the codec descriptor — guests can't configure
 * stereo. Higher layers are responsible for any resampling needed for
 * downstream consumers.
 */
public interface SoundSink {
    /**
     * Receive a chunk of PCM audio from the emulated HDA device.
     *
     * @param pcm  Raw PCM bytes in the format described above. The array
     *             is freshly allocated per call; the implementation may
     *             retain it (e.g. enqueue for processing) or copy out.
     */
    void onAudio(byte[] pcm);
}
