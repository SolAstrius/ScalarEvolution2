/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package lekkit.rvvm;

import org.jetbrains.annotations.Nullable;

/**
 * Intel HD Audio (HDA) PCI controller, emulating a C-Media CM8888 codec.
 *
 * <p>Two attachment modes, selected by constructor:
 *
 * <ul>
 *   <li>{@link #SoundHDA(RVVMMachine, boolean)} with {@code useRing=true} —
 *       PCM lands in a native ring buffer; the caller polls it via
 *       {@link #poll(byte[])}. Used by the Minecraft mod: the server-tick
 *       thread drains the ring on each tick, downsamples, and broadcasts
 *       packets. Avoids the JVM-thread-attach failure that hits us when
 *       we try to call into Java directly from RVVM's stream-worker
 *       pthread.</li>
 *   <li>{@link #SoundHDA(RVVMMachine)} or {@code useRing=false} — the
 *       compile-time backend handles PCM (ALSA on Linux builds, silent
 *       otherwise). Retained for standalone / non-JVM usage.</li>
 * </ul>
 *
 * <p>PCM format is fixed by the HDA codec: 16-bit signed little-endian,
 * mono, 192 kHz.
 */
public class SoundHDA extends PCIDevice {
    /**
     * Opaque handle to the native ring buffer, or 0 if this SoundHDA was
     * constructed without ring support. Pass to {@link RVVMNative#sound_hda_poll}.
     */
    private long ringHandle;

    /**
     * Attach an HDA device. If {@code useRing} is {@code true}, PCM chunks
     * are staged into a native ring buffer that the caller drains via
     * {@link #poll(byte[])}; otherwise the compile-time host backend is
     * used.
     */
    public SoundHDA(RVVMMachine machine, boolean useRing) {
        super(machine);
        if (!machine.isValid()) return;
        long pci_bus = RVVMNative.get_pci_bus(machine.getPtr());
        if (pci_bus == 0) return;

        if (useRing) {
            long[] pciDevOut = new long[1];
            this.ringHandle = RVVMNative.sound_hda_init_with_ring(machine.getPtr(), pciDevOut);
            setPCIHandle(pciDevOut[0]);
        } else {
            setPCIHandle(RVVMNative.sound_hda_init_auto(machine.getPtr()));
        }
    }

    /** Convenience: attach with the compile-time host backend (no ring). */
    public SoundHDA(RVVMMachine machine) {
        this(machine, false);
    }

    /** True if this SoundHDA has a native ring buffer the caller can {@link #poll}. */
    public boolean hasRing() { return ringHandle != 0; }

    /**
     * Drain up to {@code out.length} PCM bytes from the native ring into
     * {@code out}. Returns the number of bytes read.
     *
     * @throws IllegalStateException if this device wasn't constructed with
     *         a ring ({@code useRing=false}).
     */
    public int poll(byte[] out) {
        if (ringHandle == 0) {
            throw new IllegalStateException("SoundHDA was constructed without a ring buffer");
        }
        return RVVMNative.sound_hda_poll(ringHandle, out);
    }

    /** Total bytes ever pushed into the ring (monotonic, increases with guest PCM writes). */
    public long totalPushed()  { return ringHandle == 0 ? 0 : RVVMNative.sound_hda_stats(ringHandle, 0); }
    /** Total bytes ever drained by {@link #poll(byte[])}. */
    public long totalPopped()  { return ringHandle == 0 ? 0 : RVVMNative.sound_hda_stats(ringHandle, 1); }
    /** Bytes dropped due to ring overflow (Java polling too slow). */
    public long totalDropped() { return ringHandle == 0 ? 0 : RVVMNative.sound_hda_stats(ringHandle, 2); }
    /** Current ring occupancy. */
    public long ringOccupancy() { return ringHandle == 0 ? 0 : RVVMNative.sound_hda_stats(ringHandle, 3); }
}
