/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

import java.nio.ByteBuffer

/**
 * Abstraction over a virtual-machine implementation.
 *
 * In production, this is backed by RVVM via JNI
 * ([lekkit.scev.machine.rvvm.RvvmMachineBackend]). In tests we use
 * `lekkit.scev.machine.test.FakeMachineBackend` to exercise every
 * lifecycle path without loading librvvm.
 *
 * Lifecycle contract:
 *
 * ```
 *   new backend -> initialize(spec) -> start() -> (pause()/reset() allowed) -> close()
 * ```
 *
 * All methods return `false` if the backend isn't in the right state
 * (not yet initialized, already closed, etc.).
 *
 * Attach-order and peripheral wiring are the backend's responsibility.
 * The caller gives a spec, the backend constructs everything the spec
 * describes, loads firmware if present, and returns success / failure.
 * After initialize succeeds, [framebuffer], [keyboard], [mouse], and
 * [gpio] return non-null iff the spec requested that device.
 */
interface MachineBackend : AutoCloseable {
    /**
     * Construct the machine and attach all peripherals described by
     * [spec]. Must be called exactly once, before [start]. Returns
     * `true` on success.
     */
    fun initialize(spec: MachineSpec): Boolean

    /** Start (or resume) execution. Returns `true` on success. */
    fun start(): Boolean

    /**
     * Halt the emulation thread. Does not "power off" the machine —
     * subsequent [start] resumes from the same point. [isRunning]
     * stays `true` across pause; only [close] flips it.
     */
    fun pause(): Boolean

    /** Reset the machine's CPU to its entry point. The caller must re-[start]. */
    fun reset(): Boolean

    /**
     * Machine is logically powered on — [start] has been called and
     * [close] has not. This mirrors RVVM's `machine_powered` concept.
     * A paused machine is still running in this sense.
     */
    fun isRunning(): Boolean

    /** Machine is in a valid initialized state (not null, not freed). */
    fun isValid(): Boolean

    /** The spec used to build this machine. */
    fun spec(): MachineSpec

    /** The display, or null if this machine has no display. */
    fun framebuffer(): FramebufferView?

    /** The keyboard, or null if keyboard wasn't created. */
    fun keyboard(): KeyboardDevice?

    /** The mouse, or null if mouse wasn't created. */
    fun mouse(): MouseDevice?

    /** The GPIO device, or null if no GPIO card was installed. */
    fun gpio(): GpioDevice?

    /**
     * The RPC serial device — a dedicated NS16550A whose chardev is a
     * JVM-visible ring buffer, exposed to the guest as a second UART
     * (`/dev/ttyS1` once the kernel console claims ttyS0). Used by
     * [lekkit.scev.rpc.ScevRpcManager] to carry COBS-framed MessagePack
     * RPC traffic.
     *
     * Returns `null` if the backend is purely simulated (test fakes)
     * or if the JNI bridge failed to attach.
     */
    fun serial(): SerialDevice?

    /**
     * Direct read/write view into machine physical memory. Returns a
     * mutable [ByteBuffer] whose backing storage IS the VM's memory —
     * writes are seen by the CPU, reads reflect CPU-written values.
     *
     * Returns `null` if the backend doesn't support DMA (purely
     * simulated backends may return null) or the address range isn't
     * mapped.
     *
     * Used by:
     * - The bootrom installer to place a tiny demo program at the reset
     *   vector so the CPU has something to execute.
     * - E2E tests that need to verify the CPU actually ran code by
     *   reading back a memory-based side effect.
     * - Future firmware loaders that need to place initial memory state.
     */
    fun readMemory(addr: Long, size: Long): ByteBuffer?

    /**
     * Tear down the machine and all attached peripherals. After close
     * the backend is unusable; attempting to call anything else
     * returns false / null. Safe to call multiple times.
     */
    override fun close()
}
