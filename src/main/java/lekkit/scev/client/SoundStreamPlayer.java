/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lekkit.scev.network.SoundFramePayload;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import lekkit.scev.server.OpusCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

/**
 * Client-side receiver for {@link SoundFramePayload}. Maintains one
 * OpenAL streaming source per machine UUID, positioned at the hosting
 * block's world coordinates.
 *
 * <p>Frames arrive on Netty's network thread ({@link #acceptRemote}) and
 * are buffered into a thread-safe queue per machine. The actual OpenAL
 * work — buffer allocation, queueing, source placement — happens on the
 * Minecraft render thread via {@link #clientTick()} to avoid cross-thread
 * OpenAL-context races.
 *
 * <p><b>Lifecycle.</b> A source is created lazily on the first frame for
 * a given machine UUID. It's torn down when:
 * <ul>
 *   <li>{@link #destroy(UUID)} is called explicitly (e.g. from the
 *       server telling us the machine went away), or</li>
 *   <li>{@link #destroyAll()} runs on client disconnect, or</li>
 *   <li>The source has been silent (queue empty) for
 *       {@link #IDLE_DESTROY_MS} — freed automatically.</li>
 * </ul>
 *
 * <p><b>Format.</b> Matches {@link lekkit.scev.server.SoundStreamManager}:
 * 48 kHz / 16-bit signed LE / mono. OpenAL's mono format ({@code AL_FORMAT_MONO16})
 * is 3D-positional-audio friendly by default.
 */
public final class SoundStreamPlayer {
    private static final Logger LOG = LogUtils.getLogger();

    /** OpenAL's format enum for 16-bit signed mono. */
    private static final int AL_FORMAT = AL10.AL_FORMAT_MONO16;

    /** Sample rate delivered by the server. */
    private static final int SAMPLE_RATE_HZ = lekkit.scev.server.SoundStreamManager.CLIENT_SAMPLE_RATE_HZ;

    /**
     * Number of OpenAL buffers in the ring per source. Each buffer holds
     * one packet's worth of PCM (20 ms at 48 kHz mono = 1920 bytes).
     *
     * <p>Sizing: server tick jitter under Minecraft client load can hit
     * ±30-40 ms, so a 60 ms (3-buffer) ring underruns constantly — user
     * hears 20 ms of audio followed by 30 ms of silence, on repeat.
     * Sized at 16 = 320 ms nominal buffer, enough headroom that the
     * queue never empties between packets even when the client drops a
     * frame for GC.
     */
    private static final int BUFFERS_PER_SOURCE = 16;

    /**
     * Don't start playback until at least this many buffers are queued.
     * Trades ~60 ms of initial latency for smooth first playback; without
     * it the source starts on the first 20 ms buffer, drains, and the
     * first thing the user hears is a stutter.
     */
    private static final int PREBUFFER_THRESHOLD = 3;

    /**
     * If a source has been silent (empty queue) for this long, destroy it.
     * Keeps the OpenAL resource count bounded even if the server stops
     * telling us about a machine.
     */
    private static final long IDLE_DESTROY_MS = 2000;

    /**
     * Maximum bytes of queued Opus packets per machine before we drop
     * the oldest. 5 seconds of 64 kbps Opus = ~40 000 bytes; size to
     * 5 × that for comfortable headroom when the client tick stalls.
     * Preserves "latency over completeness" semantics — if the client
     * falls behind more than a few seconds, skip rather than build up a
     * growing delay.
     */
    private static final int MAX_QUEUED_BYTES = 5 * OpusCodec.MAX_ENCODED_BYTES * 50 /* frames/sec */;

    /* =================================================================== */
    /* Per-source state                                                    */
    /* =================================================================== */

    private static final class Source {
        /**
         * OpenAL source handle. -1 until allocated on the render thread;
         * writable so {@link #setSourceId} can promote a lazy entry that
         * was created on the network thread.
         */
        private int sourceId;
        /** Fresh, unused buffer IDs ready to be filled. */
        final Deque<Integer> freeBuffers = new ArrayDeque<>(BUFFERS_PER_SOURCE);
        /**
         * Opus-encoded packets queued for decode + OpenAL upload. Each
         * entry is one Opus frame (~160 bytes) → decodes to 1920 bytes
         * of 48 kHz mono 16-bit PCM.
         */
        final Deque<byte[]> pendingOpus = new ArrayDeque<>();
        int pendingBytes;
        long lastFrameAtMs;
        /**
         * Per-source Opus decoder. Stateful across frames — one per
         * stream, only used on the client render thread. Lazy-inited on
         * first decode; freed in {@link #freeSource}.
         */
        @Nullable OpusCodec.Decoder decoder;

        Source(int sourceId) {
            this.sourceId = sourceId;
            this.lastFrameAtMs = System.currentTimeMillis();
        }

        void setSourceId(int id) { this.sourceId = id; }
    }

    /** Protects both maps + each Source's pendingPcm. */
    private static final Object LOCK = new Object();
    private static final Map<UUID, Source> SOURCES = new HashMap<>();

    private SoundStreamPlayer() {}

    /* =================================================================== */
    /* Network thread entry — buffer the frame, don't touch OpenAL.        */
    /* =================================================================== */

    /**
     * Receive a PCM frame from the server. Runs on the Netty network
     * thread — must NOT call OpenAL here (not on the audio context
     * thread) or any Minecraft-client state.
     */
    public static void acceptRemote(SoundFramePayload payload) {
        if (payload.pcm().length == 0) return;
        synchronized (LOCK) {
            // Create-on-first-frame pattern: we still don't allocate OpenAL
            // resources here; {@link #clientTick} does that on the render
            // thread. The map slot just collects packets until then.
            Source s = SOURCES.computeIfAbsent(payload.machineUuid(), uuid -> new Source(-1));
            if (s.pendingBytes + payload.pcm().length > MAX_QUEUED_BYTES) {
                // Drop oldest until we fit. Preserves head-of-stream alignment
                // better than dropping the incoming packet.
                while (!s.pendingOpus.isEmpty()
                        && s.pendingBytes + payload.pcm().length > MAX_QUEUED_BYTES) {
                    byte[] evicted = s.pendingOpus.pollFirst();
                    if (evicted != null) s.pendingBytes -= evicted.length;
                }
            }
            s.pendingOpus.addLast(payload.pcm());
            s.pendingBytes += payload.pcm().length;
            s.lastFrameAtMs = System.currentTimeMillis();
        }
    }

    /* =================================================================== */
    /* Render thread entry — all OpenAL calls live here.                   */
    /* =================================================================== */

    /**
     * Must be called from the Minecraft render thread. Processes every
     * source: flushes queued PCM into OpenAL buffers, recycles buffers
     * OpenAL has finished with, updates positional data, and culls idle
     * sources.
     */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        long now = System.currentTimeMillis();
        java.util.List<UUID> toDestroy = null;

        synchronized (LOCK) {
            for (Map.Entry<UUID, Source> entry : SOURCES.entrySet()) {
                UUID uuid = entry.getKey();
                Source s = entry.getValue();

                // Lazy-allocate the OpenAL source on first touch from this
                // thread. gen* calls are only valid on the audio thread.
                if (s.sourceId == -1) {
                    int[] genBuffers = new int[BUFFERS_PER_SOURCE];
                    int newSource = AL10.alGenSources();
                    if (newSource == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                        // Out of OpenAL sources — drop the packet queue.
                        s.pendingOpus.clear();
                        s.pendingBytes = 0;
                        continue;
                    }
                    for (int i = 0; i < BUFFERS_PER_SOURCE; i++) {
                        genBuffers[i] = AL10.alGenBuffers();
                        s.freeBuffers.addLast(genBuffers[i]);
                    }
                    configureSource(newSource);
                    // Awkward: assign via reflection-free mutation by replacing
                    // the entry. Instead, give Source a settable sourceId
                    // field (below).
                    s.setSourceId(newSource);
                }

                // Lazy-init the Opus decoder on first packet for this source.
                if (s.decoder == null) {
                    s.decoder = new OpusCodec.Decoder();
                }

                // Position the source at the block's world coords, if known.
                // We look up the MachineState (available in SP; in MP the
                // server tells us via existing packets) — if we can't find
                // it, fall back to camera-relative playback at the listener.
                double sx = 0, sy = 0, sz = 0;
                MachineState state = MachineManager.getMachineState(uuid);
                if (state != null && state.getPos() != null) {
                    BlockPos pos = state.getPos();
                    sx = pos.getX() + 0.5;
                    sy = pos.getY() + 0.5;
                    sz = pos.getZ() + 0.5;
                    AL10.alSource3f(s.sourceId, AL10.AL_POSITION, (float) sx, (float) sy, (float) sz);
                } else if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
                    // No location known — stick to the camera so the player
                    // at least hears it. Rare (should always have a location
                    // from the BE once powerOn fires).
                    var cam = mc.gameRenderer.getMainCamera().getPosition();
                    AL10.alSource3f(s.sourceId, AL10.AL_POSITION,
                            (float) cam.x, (float) cam.y, (float) cam.z);
                }

                // Recycle any buffers OpenAL has finished playing back.
                int processed = AL10.alGetSourcei(s.sourceId, AL10.AL_BUFFERS_PROCESSED);
                while (processed > 0) {
                    int released = AL10.alSourceUnqueueBuffers(s.sourceId);
                    s.freeBuffers.addLast(released);
                    processed--;
                }

                // Decode queued Opus packets into PCM, upload to OpenAL,
                // and hand the buffer to the source. Each packet is a
                // fixed-size 20 ms frame so the decoded size is constant.
                while (!s.freeBuffers.isEmpty() && !s.pendingOpus.isEmpty()) {
                    byte[] opus = s.pendingOpus.pollFirst();
                    s.pendingBytes -= opus.length;
                    byte[] pcm;
                    try {
                        pcm = s.decoder.decode(opus);
                    } catch (RuntimeException e) {
                        LOG.warn("Opus decode failed for machine {}, dropping frame", uuid, e);
                        continue;
                    }
                    ByteBuffer nativeBuf = ByteBuffer.allocateDirect(pcm.length)
                            .order(ByteOrder.LITTLE_ENDIAN);
                    nativeBuf.put(pcm).rewind();
                    int bufId = s.freeBuffers.pollFirst();
                    AL10.alBufferData(bufId, AL_FORMAT, nativeBuf, SAMPLE_RATE_HZ);
                    AL10.alSourceQueueBuffers(s.sourceId, bufId);
                }

                // Start (or resume) playback once we have enough buffers
                // queued to survive one tick of jitter. Before: we started
                // on the first 20 ms buffer and immediately drained, which
                // sounds like "[20ms audio] [30ms silence] [20ms audio] ..."
                // — unmistakably stuttery.
                //
                // After an underrun OpenAL transitions to AL_STOPPED; we
                // re-prebuffer to PREBUFFER_THRESHOLD before kicking it
                // again, which rebuilds the ring before the user hears
                // another gap.
                int state_ = AL10.alGetSourcei(s.sourceId, AL10.AL_SOURCE_STATE);
                if (state_ != AL10.AL_PLAYING) {
                    int queued = AL10.alGetSourcei(s.sourceId, AL10.AL_BUFFERS_QUEUED);
                    if (queued >= PREBUFFER_THRESHOLD) {
                        AL10.alSourcePlay(s.sourceId);
                    }
                }

                // Cull source if silent for too long.
                if (s.pendingOpus.isEmpty()
                        && AL10.alGetSourcei(s.sourceId, AL10.AL_BUFFERS_QUEUED) == 0
                        && (now - s.lastFrameAtMs) > IDLE_DESTROY_MS) {
                    if (toDestroy == null) toDestroy = new java.util.ArrayList<>();
                    toDestroy.add(uuid);
                }
            }
            if (toDestroy != null) {
                for (UUID uuid : toDestroy) {
                    Source s = SOURCES.remove(uuid);
                    if (s != null) freeSource(s);
                }
            }
        }
    }

    /* =================================================================== */
    /* External teardown                                                   */
    /* =================================================================== */

    /** Destroy the streaming source for a specific machine, if any. */
    public static void destroy(UUID machineUuid) {
        Source removed;
        synchronized (LOCK) { removed = SOURCES.remove(machineUuid); }
        if (removed != null) freeSource(removed);
    }

    /** Destroy every streaming source. Called on client disconnect. */
    public static void destroyAll() {
        java.util.List<Source> toFree;
        synchronized (LOCK) {
            toFree = new java.util.ArrayList<>(SOURCES.values());
            SOURCES.clear();
        }
        for (Source s : toFree) freeSource(s);
    }

    /** Test-only: how many sources are currently live. */
    public static int liveSourceCount() {
        synchronized (LOCK) { return SOURCES.size(); }
    }

    /** Test-only: how many bytes are queued for a given machine (or -1 if no source). */
    public static int pendingBytes(UUID machineUuid) {
        synchronized (LOCK) {
            Source s = SOURCES.get(machineUuid);
            return s == null ? -1 : s.pendingBytes;
        }
    }

    /* =================================================================== */
    /* Helpers                                                             */
    /* =================================================================== */

    /**
     * Configure positional-audio parameters on a fresh OpenAL source so
     * the client's listener hears attenuation with distance — matches
     * vanilla's jukebox feel.
     */
    private static void configureSource(int sourceId) {
        AL10.alSourcef(sourceId, AL10.AL_GAIN, 1.0f);
        AL10.alSourcef(sourceId, AL10.AL_PITCH, 1.0f);
        // Linear-ish rolloff tuned to sound like a jukebox in practice.
        AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 4.0f);
        AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 64.0f);
        AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f);
        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
    }

    private static void freeSource(Source s) {
        if (s.sourceId != -1) {
            AL10.alSourceStop(s.sourceId);
            // Flush queued buffers so we can delete them cleanly.
            int queued = AL10.alGetSourcei(s.sourceId, AL10.AL_BUFFERS_QUEUED);
            for (int i = 0; i < queued; i++) {
                int b = AL10.alSourceUnqueueBuffers(s.sourceId);
                s.freeBuffers.addLast(b);
            }
            AL10.alDeleteSources(s.sourceId);
        }
        for (int buf : s.freeBuffers) {
            AL10.alDeleteBuffers(buf);
        }
        s.freeBuffers.clear();
        if (s.decoder != null) {
            s.decoder.close();
            s.decoder = null;
        }
    }
}
