/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

import org.jetbrains.annotations.Nullable;

/**
 * Abstraction over a virtual-machine implementation.
 *
 * <p>In production, this is backed by RVVM via JNI
 * ({@link lekkit.scev.machine.rvvm.RvvmMachineBackend}). In tests we use
 * {@link lekkit.scev.machine.test.FakeMachineBackend} to exercise every
 * lifecycle path without loading librvvm.
 *
 * <p>Lifecycle contract:
 * <pre>
 *   new backend -> initialize(spec) -> start() -> (pause()/reset() allowed) -> close()
 * </pre>
 * All methods return {@code false} if the backend isn't in the right state
 * (not yet initialized, already closed, etc.).
 *
 * <p>Attach-order and peripheral wiring are the backend's responsibility. The
 * caller gives a spec, the backend constructs everything the spec describes,
 * loads firmware if present, and returns success / failure. After initialize
 * succeeds, {@link #framebuffer()}, {@link #keyboard()}, {@link #mouse()}, and
 * {@link #gpio()} return non-null iff the spec requested that device.
 */
public interface MachineBackend extends AutoCloseable {
    /**
     * Construct the machine and attach all peripherals described by {@code spec}.
     * Must be called exactly once, before {@link #start()}. Returns {@code true}
     * on success.
     */
    boolean initialize(MachineSpec spec);

    /** Start (or resume) execution. Returns {@code true} on success. */
    boolean start();

    /**
     * Halt the emulation thread. Does not "power off" the machine — subsequent
     * {@link #start()} resumes from the same point. {@link #isRunning()} stays
     * {@code true} across pause; only {@link #close()} flips it.
     */
    boolean pause();

    /** Reset the machine's CPU to its entry point. The caller must re-{@link #start()}. */
    boolean reset();

    /**
     * Machine is logically powered on — {@link #start()} has been called and
     * {@link #close()} has not. This mirrors RVVM's {@code machine_powered}
     * concept. A paused machine is still running in this sense.
     */
    boolean isRunning();

    /** Machine is in a valid initialized state (not null, not freed). */
    boolean isValid();

    /** The spec used to build this machine. */
    MachineSpec spec();

    /** The display, or null if this machine has no display. */
    @Nullable FramebufferView framebuffer();

    /** The keyboard, or null if keyboard wasn't created. */
    @Nullable KeyboardDevice keyboard();

    /** The mouse, or null if mouse wasn't created. */
    @Nullable MouseDevice mouse();

    /** The GPIO device, or null if no GPIO card was installed. */
    @Nullable GpioDevice gpio();

    /**
     * Direct read/write view into machine physical memory. Returns a mutable
     * {@link java.nio.ByteBuffer} whose backing storage IS the VM's memory —
     * writes are seen by the CPU, reads reflect CPU-written values.
     *
     * <p>Returns {@code null} if the backend doesn't support DMA (purely
     * simulated backends may return null) or the address range isn't mapped.
     *
     * <p>Used by:
     * <ul>
     *   <li>The bootrom installer to place a tiny demo program at the reset
     *       vector so the CPU has something to execute.</li>
     *   <li>E2E tests that need to verify the CPU actually ran code by
     *       reading back a memory-based side effect.</li>
     *   <li>Future firmware loaders that need to place initial memory state.</li>
     * </ul>
     */
    @Nullable java.nio.ByteBuffer readMemory(long addr, long size);

    /**
     * Tear down the machine and all attached peripherals. After close the
     * backend is unusable; attempting to call anything else returns false /
     * null. Safe to call multiple times.
     */
    @Override void close();
}
