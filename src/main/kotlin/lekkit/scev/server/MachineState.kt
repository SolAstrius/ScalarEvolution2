/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server

import java.util.UUID
import lekkit.scev.core.time.MachineClock
import lekkit.scev.machine.FramebufferView
import lekkit.scev.machine.GpioDevice
import lekkit.scev.machine.KeyboardDevice
import lekkit.scev.machine.MachineBackend
import lekkit.scev.machine.MachineSpec
import lekkit.scev.machine.MouseDevice
import lekkit.scev.machine.SerialDevice
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Wraps a live [MachineBackend] with manager-level bookkeeping (pause,
 * unload, persistence toggles) and convenience accessors for attached
 * peripherals.
 *
 * The backend is the only thing holding RVVM state and all access flows
 * through the abstract interface so tests can swap in a fake backend.
 */
open class MachineState(
    @get:JvmName("getSpec")    val spec: MachineSpec,
    @get:JvmName("getBackend") val backend: MachineBackend,
) {
    /**
     * Per-machine A/V sync clock. Stamps audio + video frames with
     * presentation timestamps so the client can render them against a common
     * media clock. Reset on [pause] / [unload] so the stream's timeline
     * restarts cleanly; the next emitted frame re-anchors the origin.
     */
    @get:JvmName("getClock")
    val clock: MachineClock = MachineClock(MachineClock.DEFAULT_SAMPLE_RATE_HZ)

    private var paused = false
    private var unloaded = false
    private var persisting = true

    /**
     * World location of the BE hosting this machine. Set by the BE in its
     * `powerOn()` after MachineManager hands it a state. Null until then —
     * callers (e.g. [SoundStreamManager]) must gracefully skip dispatch when
     * not yet located.
     */
    @Volatile @get:JvmName("getLevel")
    var level: ServerLevel? = null
        private set
    @Volatile @get:JvmName("getPos")
    var pos: BlockPos? = null
        private set

    @JvmName("getUUID")        fun getUuid(): UUID = spec.uuid

    fun setPersisting(p: Boolean) { persisting = p }
    fun isPersisting(): Boolean = persisting

    /**
     * Associate this machine with the world location of its hosting BE.
     * Safe to call repeatedly — overwrites previous location.
     */
    fun setLocation(level: ServerLevel, pos: BlockPos) {
        this.level = level
        this.pos = pos
    }

    /* ----- Device accessors -------------------------------------------- */

    @get:JvmName("getDisplay")  val display: FramebufferView? get() = backend.framebuffer()
    @get:JvmName("getKeyboard") val keyboard: KeyboardDevice? get() = backend.keyboard()
    @get:JvmName("getMouse")    val mouse: MouseDevice?       get() = backend.mouse()
    @get:JvmName("getGPIO")     val gpio: GpioDevice?         get() = backend.gpio()
    @get:JvmName("getSerial")   val serial: SerialDevice?     get() = backend.serial()

    /* ----- Lifecycle ---------------------------------------------------- */

    fun start(): Boolean = backend.start()

    /**
     * Power-cycle the emulated hardware. Also resets the A/V sync clock so
     * the post-reset audio/video stream starts a new epoch — the client's
     * MediaClock detects the backward PTS jump and re-anchors.
     */
    fun reset(): Boolean {
        clock.reset()
        return backend.reset()
    }

    val isPowered: Boolean get() = backend.isRunning()
    val isValid: Boolean get() = backend.isValid()

    fun loadSnapshot(): Boolean = false /* TODO */
    fun saveSnapshot() { /* TODO */ }

    /** Pause for world-unload (server sleep). Call [load] to resume. */
    fun unload() {
        if (!unloaded && backend.isValid()) {
            unloaded = true
            backend.pause()
            // Halting frame emission freezes the audio sample counter but
            // wall-clock keeps going; without a reset, video PTS on resume
            // would jump forward by the pause duration and desync from
            // audio. Reset both so the resume frame re-anchors the
            // client's MediaClock cleanly.
            clock.reset()
        }
    }

    fun load() {
        if (unloaded) {
            unloaded = false
            tryResume()
        }
    }

    /** Game-pause pause (player opened the pause screen). Distinct from unload. */
    fun pause() {
        if (!paused && backend.isValid()) {
            paused = true
            backend.pause()
            // Same reasoning as unload(): keep audio/video PTS in step
            // across the pause boundary by dropping the shared clock origin.
            clock.reset()
        }
    }

    fun unpause() {
        if (paused) {
            paused = false
            tryResume()
        }
    }

    private fun tryResume() {
        if (!unloaded && !paused && backend.isValid() && backend.isRunning()) {
            backend.start()
        }
    }

    @Synchronized fun destroy() {
        backend.close()
    }
}
