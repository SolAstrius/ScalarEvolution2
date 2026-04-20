/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lekkit.scev.machine.MachineBackend;
import lekkit.scev.machine.MachineBackendFactory;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.rvvm.RvvmMachineBackend;
import org.slf4j.Logger;

/**
 * Thread-safe registry of currently-running {@link MachineState}s keyed by UUID.
 *
 * <p>The backing {@link MachineBackend} is constructed via a pluggable
 * {@link MachineBackendFactory} — by default the production
 * {@link RvvmMachineBackend}. Tests replace the factory with a fake so the
 * full create / attach / start pipeline runs without JNI.
 */
public final class MachineManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, MachineState> MACHINES = new HashMap<>();
    private static volatile MachineBackendFactory factory = RvvmMachineBackend::new;

    private MachineManager() {}

    /**
     * Override the backend factory. Intended for tests — production code
     * leaves it at the default. The change persists until set again.
     */
    public static synchronized void setBackendFactory(MachineBackendFactory f) {
        factory = f == null ? RvvmMachineBackend::new : f;
    }

    /** Create and register a new {@link MachineState} from a {@link MachineSpec}. */
    public static synchronized MachineState createMachineState(MachineSpec spec) {
        if (MACHINES.containsKey(spec.uuid())) {
            LOGGER.warn("Machine {} already exists", spec.uuid());
            return null;
        }
        MachineBackend backend = factory.create();
        if (!backend.initialize(spec)) {
            LOGGER.warn("Failed to initialize backend for machine {}", spec.uuid());
            backend.close();
            return null;
        }
        MachineState state = new MachineState(spec, backend);
        MACHINES.put(spec.uuid(), state);
        return state;
    }

    public static synchronized MachineState getMachineState(UUID uuid) {
        return MACHINES.get(uuid);
    }

    public static synchronized void removeMachineState(UUID uuid) {
        MachineState state = MACHINES.remove(uuid);
        if (state != null) state.destroy();
    }

    public static synchronized void destroyMachineState(UUID uuid) {
        removeMachineState(uuid);
        // TODO: delete snapshot file
    }

    public static synchronized boolean hasMachineStateSnapshot(UUID uuid) {
        // TODO: real snapshot lookup
        return false;
    }

    public static synchronized void finishAllMachines() {
        pauseAllMachines();
        for (MachineState state : MACHINES.values()) {
            state.saveSnapshot();
            state.destroy();
        }
        MACHINES.clear();
    }

    public static synchronized void pauseAllMachines() {
        for (MachineState state : MACHINES.values()) state.pause();
    }

    public static synchronized void unpauseAllMachines() {
        for (MachineState state : MACHINES.values()) state.unpause();
    }
}
