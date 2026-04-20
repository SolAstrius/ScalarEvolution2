/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware;

import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Static registry of {@link ScevFirmware} entries keyed by
 * {@link ResourceLocation}. Deliberately kept as a plain static singleton:
 * the registry is consumed in a handful of server-side code paths and
 * doesn't need the full Forge DeferredRegister / mod-bus ceremony.
 *
 * <h2>When to populate</h2>
 *
 * <p>Call {@link #registerBuiltins()} once during {@code FMLCommonSetupEvent}
 * — this installs the three first-party firmwares (LINUX, OPENSBI_ONLY,
 * OPEN_FIRMWARE). Other mods can register additional entries from their
 * own common-setup hook; duplicate IDs log a warning and keep the first
 * registration.
 *
 * <h2>Lookup</h2>
 *
 * <p>{@link #get(ResourceLocation)} returns the firmware or {@code null}.
 * Callers must handle {@code null} as "firmware missing / misspelled ID /
 * mod removed between world saves": in {@code RvvmMachineBackend} we log
 * and fall through to the demo-bootrom path rather than crash.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Backing map is a {@link LinkedHashMap} guarded by synchronizing on the
 * class lock. Registration happens at mod init (single-threaded), lookups
 * happen per-machine on the tick thread; contention is nil in practice.
 */
public final class FirmwareRegistry {
    private static final Logger LOG = LogUtils.getLogger();

    /** Backing map. Access under the FirmwareRegistry class lock. */
    private static final Map<ResourceLocation, ScevFirmware> ENTRIES = new LinkedHashMap<>();

    /**
     * OpenSBI + Linux kernel. The current default "flash chip installed"
     * firmware — loads {@code fw_jump.bin} as the bootrom and {@code Image}
     * as the kernel, with console routing in the cmdline and a 256 MiB RAM
     * floor so the shipped Buildroot kernel + 26 MiB initramfs don't OOM.
     */
    public static final ResourceLocation LINUX = rl("linux");

    /**
     * OpenSBI only ({@code fw_jump.bin}). Boots to S-mode at {@code 0x80200000}
     * where, without a kernel, it hits zero-initialized RAM and traps. Useful
     * as a building block for future "user ships their own kernel" flows
     * (pair with a {@link lekkit.scev.machine.MachineSpec.KernelSpec} or a
     * bootable disk image).
     */
    public static final ResourceLocation OPENSBI_ONLY = rl("opensbi_only");

    /**
     * OpenSBI + U-Boot ({@code fw_payload.bin}). Boots to a U-Boot shell
     * on UART with a 3 s autoboot countdown. Power-user firmware — manual
     * boot commands, NVMe scanning, etc.
     */
    public static final ResourceLocation OPEN_FIRMWARE = rl("open_firmware");

    private FirmwareRegistry() {}

    /**
     * Register (or re-register) a firmware under {@code id}. Duplicate
     * registrations log a warning and keep the first one, matching
     * Forge's DeferredRegister semantics — this way a crashed-then-retried
     * mod init doesn't silently clobber a firmware the player relies on.
     */
    public static synchronized void register(ResourceLocation id, ScevFirmware firmware) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firmware, "firmware");
        if (firmware.payloads() == null || firmware.payloads().isEmpty()) {
            throw new IllegalArgumentException("firmware " + id + " has no payloads");
        }
        ScevFirmware prior = ENTRIES.putIfAbsent(id, firmware);
        if (prior != null) {
            LOG.warn("Duplicate firmware registration for {}: keeping {}, ignoring {}",
                    id, prior.getClass().getSimpleName(), firmware.getClass().getSimpleName());
        } else {
            LOG.debug("Registered firmware {} -> {}", id, firmware.getClass().getSimpleName());
        }
    }

    /**
     * Retrieve the firmware registered under {@code id}, or {@code null} if
     * there isn't one. Thread-safe; returns a reference to the shared
     * singleton instance (firmwares are stateless).
     */
    public static synchronized @Nullable ScevFirmware get(@Nullable ResourceLocation id) {
        if (id == null) return null;
        return ENTRIES.get(id);
    }

    /** Does this id resolve to a registered firmware? */
    public static synchronized boolean contains(@Nullable ResourceLocation id) {
        return id != null && ENTRIES.containsKey(id);
    }

    /** All registered firmware ids, in registration order. Defensive copy. */
    public static synchronized Collection<ResourceLocation> ids() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(ENTRIES).keySet());
    }

    /** How many firmwares are registered right now. */
    public static synchronized int size() {
        return ENTRIES.size();
    }

    /**
     * Install the three built-in firmwares. Idempotent — calling twice
     * leaves the existing registrations in place (logging a dedup warning
     * each time). Wired into {@code ScalarEvolution.onCommonSetup}.
     */
    public static synchronized void registerBuiltins() {
        register(LINUX, LinuxFirmware.INSTANCE);
        register(OPENSBI_ONLY, OpenSbiFirmware.INSTANCE);
        register(OPEN_FIRMWARE, OpenFirmware.INSTANCE);
    }

    /**
     * Test-only: clear all registrations. Unit tests that register custom
     * firmwares must reset state between test methods so ordering doesn't
     * leak side effects.
     */
    public static synchronized void clearForTests() {
        ENTRIES.clear();
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, path);
    }
}
