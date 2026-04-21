/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lekkit.rvvm.SoundHDA;
import lekkit.rvvm.SoundSink;
import lekkit.scev.network.SoundFramePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Per-machine audio pipeline.
 *
 * <p>Receives 192 kHz / 16-bit signed / mono PCM from RVVM's HDA worker
 * thread, downsamples to 48 kHz, slices into 20 ms frames, and on each
 * server tick broadcasts every queued frame as a {@link SoundFramePayload}
 * to players within the machine's audible radius.
 *
 * <p>The flow must hop threads because Minecraft's player-list iteration
 * and packet dispatch aren't safe on non-server threads:
 *
 * <pre>
 *   RVVM HDA worker thread             Server tick thread
 *   ─────────────────────              ──────────────────
 *   onAudio(bytes)        →  [lock]
 *   append to rawIn       →
 *   drain downsample      →
 *   slice frames          →  [queue]  ──→  tick() drains queue
 *                                         ──→  broadcast frames
 * </pre>
 *
 * <p><b>Downsampling.</b> 4:1 box-filter — average each block of 4
 * consecutive 16-bit samples into one. Trivial CPU, acceptable quality for
 * speech / tones / chiptune. A sinc or Lanczos filter would preserve more
 * high-frequency content but the current guest codec (CM8888) and the
 * "in-game computer" use case don't justify it.
 *
 * <p><b>Frame size.</b> 20 ms of 48 kHz mono = 960 samples = 1920 bytes
 * per packet. One frame per server tick (20 TPS = 50 ms) in the steady
 * state, but backlogged frames are drained in a single tick if the guest
 * is ahead of the server. Queue is capped — overflow drops the oldest
 * frames to keep latency bounded.
 *
 * <p><b>Jukebox-like radius.</b> Vanilla jukeboxes use volume 4.0 with
 * {@code SoundSource.RECORDS}; OpenAL's linear distance attenuation
 * makes that audible out to ~64 blocks. We broadcast at the same radius
 * and let the client-side streaming source apply distance rolloff.
 */
public final class SoundStreamManager implements SoundSink {
    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Rate at which the guest HDA codec produces PCM. Matches the
     * {@code CODEC_PARAM_SUPP_PCM_SIZE_RATES} response in RVVM's
     * {@code sound-hda.c} (advertises only 48 kHz) and the stream
     * worker's pacing constant. The three MUST stay in lockstep:
     * changing one and forgetting the others leads to 4× slow playback
     * and crackling.
     */
    public static final int GUEST_SAMPLE_RATE_HZ  = 48_000;

    /**
     * Rate the client plays at. Matches the guest rate — we ship PCM
     * unprocessed to the client's OpenAL source. No resampling anywhere
     * in the pipeline, as long as source files are converted to 48 kHz
     * mono 16-bit before they reach the guest (ffmpeg / afconvert does
     * this trivially).
     */
    public static final int CLIENT_SAMPLE_RATE_HZ = GUEST_SAMPLE_RATE_HZ;
    public static final int BYTES_PER_SAMPLE      = 2; // 16-bit signed LE

    public static final int FRAME_MS             = 20;
    public static final int FRAME_SAMPLES        = (CLIENT_SAMPLE_RATE_HZ * FRAME_MS) / 1000;     // 960
    public static final int FRAME_BYTES          = FRAME_SAMPLES * BYTES_PER_SAMPLE;              // 1920

    /**
     * Maximum queued frames before we drop the oldest. 50 frames * 20 ms =
     * 1 s of latency cushion. If the server tick stalls for longer than
     * that, we prefer "sound skips, stays in sync" over "sound keeps
     * playing but is N seconds behind video".
     */
    public static final int MAX_QUEUED_FRAMES = 50;

    /**
     * Audibility radius in blocks — matches vanilla jukebox volume 4.0
     * with OpenAL's default rolloff.
     */
    public static final double AUDIBLE_RADIUS_BLOCKS = 64.0;

    /** Registry of live managers, one per machine UUID. */
    private static final Map<UUID, SoundStreamManager> MANAGERS = new ConcurrentHashMap<>();

    private final UUID machineUuid;

    /**
     * Bound to a {@link SoundHDA} device with a native ring buffer. The
     * manager drains it on every server tick; the guest's PCM writes end
     * up in the ring without any JVM thread attachment needed.
     */
    private @Nullable SoundHDA soundDevice;

    /** Scratch buffer for native ring polls — sized so one tick worth of
     *  PCM ({@code ~1920 bytes} post-downsample, {@code ~7680 bytes} raw)
     *  fits comfortably even on ticks that drain backlog. */
    private final byte[] pollBuf = new byte[32 * 1024];

    /** Guards all mutable fields below. */
    private final Object lock = new Object();

    /**
     * Unconsumed PCM bytes awaiting frame boundary. Historically this was
     * two stages (raw 192 kHz + downsampled 48 kHz), but we now ship
     * 192 kHz straight through so they're one and the same.
     */
    private final java.io.ByteArrayOutputStream outBuffer = new java.io.ByteArrayOutputStream();

    /** Complete frames ready to ship on the next server tick. */
    private final Deque<byte[]> pendingFrames = new ArrayDeque<>();

    private long totalPcmBytesIn;
    private long totalFramesOut;
    private long droppedFrames;

    /**
     * Per-stream Opus encoder. Lazy: created on first frame emission and
     * destroyed when the manager is unregistered. Opus encoders carry
     * stateful prediction, so one instance per source, on one thread.
     */
    private @Nullable OpusCodec.Encoder opusEncoder;

    private SoundStreamManager(UUID machineUuid) {
        this.machineUuid = machineUuid;
    }

    /**
     * Associate this manager with a {@link SoundHDA} device. Called by
     * {@code RvvmMachineBackend.initialize} right after the device is
     * attached. The manager polls the device's native ring buffer on
     * each server tick.
     */
    public void bindDevice(SoundHDA device) {
        this.soundDevice = device;
    }

    /**
     * Create and register a manager for the given machine UUID. The caller
     * is responsible for installing this as the sink on the RVVM HDA device
     * (via {@link lekkit.rvvm.SoundHDA}) and for calling
     * {@link #unregister(UUID)} at machine teardown.
     */
    public static SoundStreamManager create(UUID machineUuid) {
        SoundStreamManager mgr = new SoundStreamManager(machineUuid);
        MANAGERS.put(machineUuid, mgr);
        return mgr;
    }

    /** Release a manager. Safe to call with an unknown UUID. */
    public static void unregister(UUID machineUuid) {
        SoundStreamManager mgr = MANAGERS.remove(machineUuid);
        if (mgr != null && mgr.opusEncoder != null) {
            mgr.opusEncoder.close();
            mgr.opusEncoder = null;
        }
    }

    /** Read-only snapshot for tests and debug. */
    public static int liveManagerCount() { return MANAGERS.size(); }

    /** For tests: retrieve a manager by UUID, or {@code null} if unregistered. */
    public static SoundStreamManager get(UUID machineUuid) { return MANAGERS.get(machineUuid); }

    /* =========================================================== */
    /* SoundSink — runs on the RVVM HDA worker thread.             */
    /* =========================================================== */

    @Override
    public void onAudio(byte[] pcm192kHz) {
        // Direct callback path — kept for tests and future use cases where
        // JVM thread-attach works. Current production path goes through
        // pollFromRing() below on each server tick.
        if (pcm192kHz == null || pcm192kHz.length == 0) return;
        synchronized (lock) {
            if (totalPcmBytesIn == 0) {
                LOG.info("[scev-audio] first PCM callback for {} ({} bytes)",
                        machineUuid, pcm192kHz.length);
            }
            totalPcmBytesIn += pcm192kHz.length;
            outBuffer.write(pcm192kHz, 0, pcm192kHz.length);
            drainFrames();
        }
    }

    /**
     * Drain the bound SoundHDA's native ring buffer into our staging
     * buffers. Called from the server tick before {@link #tick()} so any
     * freshly-polled PCM gets packetized in the same tick.
     */
    private void pollFromRing() {
        SoundHDA device = soundDevice;
        if (device == null || !device.hasRing()) return;
        int n;
        while ((n = device.poll(pollBuf)) > 0) {
            synchronized (lock) {
                totalPcmBytesIn += n;
                outBuffer.write(pollBuf, 0, n);
                drainFrames();
            }
            if (n < pollBuf.length) break;  // ring drained
        }
    }

    /**
     * 4:1 box-filter downsampler. Unused by production (we ship 192 kHz
     * straight through) but kept available as a utility — the math is
     * unit-tested and trivially reusable if bandwidth ever becomes a
     * concern and a better resampler isn't yet written.
     *
     * <p>Input: {@code len} bytes (must be a multiple of 8) of 16-bit
     * signed LE PCM. Output: {@code len / 4} bytes, each output sample
     * the arithmetic mean of 4 consecutive input samples.
     */
    public static byte[] downsample4to1(byte[] in, int off, int len) {
        final int ratio        = 4;
        final int groupBytes   = ratio * 2;
        if ((len % groupBytes) != 0) {
            throw new IllegalArgumentException("downsample input must be multiple of "
                    + groupBytes + " bytes, got " + len);
        }
        int outLen = len / ratio;
        byte[] out = new byte[outLen];
        int groups = len / groupBytes;
        for (int g = 0; g < groups; g++) {
            int base = off + g * groupBytes;
            int sum = 0;
            for (int i = 0; i < ratio; i++) {
                int lo = in[base + i * 2] & 0xFF;
                int hi = in[base + i * 2 + 1]; // sign-extended
                sum += (hi << 8) | lo;
            }
            int avg = sum / ratio;
            if (avg >  32767) avg =  32767;
            if (avg < -32768) avg = -32768;
            out[g * 2]     = (byte) (avg & 0xFF);
            out[g * 2 + 1] = (byte) ((avg >> 8) & 0xFF);
        }
        return out;
    }

    /**
     * Slice as many complete {@code FRAME_BYTES} chunks as possible from
     * {@code outBuffer} and enqueue them. Overflow (queue full) drops
     * the oldest pending frame — preserves latency, loses audio.
     */
    private void drainFrames() {
        while (outBuffer.size() >= FRAME_BYTES) {
            byte[] buf = outBuffer.toByteArray();
            byte[] frame = new byte[FRAME_BYTES];
            System.arraycopy(buf, 0, frame, 0, FRAME_BYTES);
            int leftover = buf.length - FRAME_BYTES;
            outBuffer.reset();
            if (leftover > 0) outBuffer.write(buf, FRAME_BYTES, leftover);

            if (pendingFrames.size() >= MAX_QUEUED_FRAMES) {
                pendingFrames.pollFirst();
                droppedFrames++;
            }
            pendingFrames.addLast(frame);
        }
    }

    /* =========================================================== */
    /* Server tick — runs on the main server thread.               */
    /* =========================================================== */

    /** Event listener invoked by NeoForge on each server tick. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (MANAGERS.isEmpty()) return;
        for (SoundStreamManager mgr : MANAGERS.values()) {
            mgr.tick();
        }
    }

    /**
     * Drain the bound device's native ring buffer into staging buffers,
     * then ship every complete frame as a packet to nearby players. Runs
     * on the server tick thread, so Minecraft API calls (PacketDistributor,
     * ServerLevel) are safe here.
     */
    void tick() {
        pollFromRing();

        List<byte[]> framesToSend;
        synchronized (lock) {
            if (pendingFrames.isEmpty()) return;
            framesToSend = new ArrayList<>(pendingFrames);
            pendingFrames.clear();
        }

        MachineState state = MachineManager.getMachineState(machineUuid);
        if (state == null) return;
        ServerLevel level = state.getLevel();
        BlockPos pos = state.getPos();
        if (level == null || pos == null) return;

        // Lazy-init encoder on first dispatch (not in the constructor —
        // machines without sound cards never allocate it).
        if (opusEncoder == null) {
            opusEncoder = new OpusCodec.Encoder();
        }

        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        for (byte[] frame : framesToSend) {
            // Opus-encode before shipping. Each 1920-byte PCM frame
            // becomes ~160 bytes of Opus at 64 kbps. Network bandwidth
            // per listener drops from 96 KB/s (raw PCM) to ~8 KB/s.
            byte[] encoded;
            try {
                encoded = opusEncoder.encode(frame);
            } catch (RuntimeException e) {
                LOG.warn("Opus encode failed for machine {}, dropping frame", machineUuid, e);
                continue;
            }
            PacketDistributor.sendToPlayersNear(level, null, x, y, z, AUDIBLE_RADIUS_BLOCKS,
                    new SoundFramePayload(machineUuid, encoded));
            totalFramesOut++;
        }
    }

    /* =========================================================== */
    /* Inspection — test-only accessors.                           */
    /* =========================================================== */

    public long totalPcmBytesIn()   { synchronized (lock) { return totalPcmBytesIn; } }
    public long totalFramesOut()    { synchronized (lock) { return totalFramesOut; } }
    public long droppedFrames()     { synchronized (lock) { return droppedFrames; } }
    public int  pendingFrameCount() { synchronized (lock) { return pendingFrames.size(); } }

    /** Test-only: drain and return the next queued frame without dispatching. */
    public byte[] pollFrame() {
        synchronized (lock) { return pendingFrames.pollFirst(); }
    }
}
