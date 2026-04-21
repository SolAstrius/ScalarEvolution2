/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import java.util.UUID;
import lekkit.scev.machine.FramebufferView;
import lekkit.scev.machine.GpioDevice;
import lekkit.scev.machine.KeyboardDevice;
import lekkit.scev.machine.MachineBackend;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MouseDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps a live {@link MachineBackend} with machine-manager-level bookkeeping
 * (pause, unload, persistence toggles) and convenience accessors for the
 * attached peripherals.
 *
 * <p>Callers used to reach through to {@code state.getMachine().start()} /
 * {@code state.getMachine().reset()} etc. — that's gone. Use
 * {@link #start()}, {@link #pause()}, {@link #reset()}, {@link #isPowered()}
 * instead. The backend is the only thing holding RVVM state and all
 * access to it flows through the abstract interface so tests can swap in a
 * fake backend.
 */
public class MachineState {
    private final MachineSpec spec;
    private final MachineBackend backend;

    private boolean paused;
    private boolean unloaded;
    private boolean persisting = true;

    /**
     * World location of the block entity hosting this machine. Set by the
     * BE in its {@code powerOn()} after MachineManager hands it a state.
     * Null until then — callers (e.g. {@link SoundStreamManager}) must
     * gracefully skip dispatch when not yet located.
     */
    private volatile @Nullable ServerLevel level;
    private volatile @Nullable BlockPos pos;

    public MachineState(MachineSpec spec, MachineBackend backend) {
        this.spec = spec;
        this.backend = backend;
    }

    public UUID getUUID() { return spec.uuid(); }
    public MachineSpec getSpec() { return spec; }
    public MachineBackend getBackend() { return backend; }

    public void setPersisting(boolean p) { persisting = p; }
    public boolean isPersisting() { return persisting; }

    /**
     * Associate this machine with the world location of its hosting block
     * entity. Called from the BE's {@code powerOn} path. Safe to call
     * repeatedly — overwrites previous location.
     */
    public void setLocation(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    /** The {@link ServerLevel} hosting this machine, or null if not yet located. */
    public @Nullable ServerLevel getLevel() { return level; }

    /** The block position hosting this machine, or null if not yet located. */
    public @Nullable BlockPos getPos() { return pos; }

    /* ---------------- Device accessors ---------------- */

    public @Nullable FramebufferView getDisplay() { return backend.framebuffer(); }
    public @Nullable KeyboardDevice getKeyboard() { return backend.keyboard(); }
    public @Nullable MouseDevice getMouse() { return backend.mouse(); }
    public @Nullable GpioDevice getGPIO() { return backend.gpio(); }

    /* ---------------- Lifecycle ---------------- */

    public boolean start() { return backend.start(); }
    public boolean reset() { return backend.reset(); }
    public boolean isPowered() { return backend.isRunning(); }
    public boolean isValid() { return backend.isValid(); }

    public boolean loadSnapshot() { return false; /* TODO */ }
    public void saveSnapshot() { /* TODO */ }

    /** Pause for world-unload (server sleep). Call {@link #load()} to resume. */
    public void unload() {
        if (!unloaded && backend.isValid()) {
            unloaded = true;
            backend.pause();
        }
    }

    public void load() {
        if (unloaded) {
            unloaded = false;
            tryResume();
        }
    }

    /** Game-pause pause (player opened the pause screen). Distinct from unload. */
    public void pause() {
        if (!paused && backend.isValid()) {
            paused = true;
            backend.pause();
        }
    }

    public void unpause() {
        if (paused) {
            paused = false;
            tryResume();
        }
    }

    private void tryResume() {
        if (!unloaded && !paused && backend.isValid() && backend.isRunning()) {
            backend.start();
        }
    }

    public synchronized void destroy() {
        backend.close();
    }
}
