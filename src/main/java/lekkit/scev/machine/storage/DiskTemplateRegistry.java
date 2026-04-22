/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.storage;

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
 * Static registry of {@link ScevDiskTemplate} entries keyed by
 * {@link ResourceLocation}. Companion to {@link lekkit.scev.machine.firmware.FirmwareRegistry}.
 *
 * <p>No built-in entries yet — shipping a real Linux rootfs as a separate
 * ext4 image requires a Buildroot rebuild. The scaffolding is in place so
 * that follow-up work can register templates without touching this file's
 * consumers.
 *
 * <p>Same thread-safety contract as {@code FirmwareRegistry}: synchronized
 * on the class lock; registration at init, lookup at runtime.
 */
public final class DiskTemplateRegistry {
    private static final Logger LOG = LogUtils.getLogger();

    private static final Map<ResourceLocation, ScevDiskTemplate> ENTRIES = new LinkedHashMap<>();

    /**
     * Buildroot 2026.02 Linux rootfs. Default template for
     * {@link lekkit.scev.items.PreloadedNvmeItem}.
     *
     * <p>Currently a scaffold asset (see
     * {@link BuildrootDiskTemplate}); replaced with a real ext4 filesystem
     * in a follow-up.
     */
    public static final ResourceLocation BUILDROOT = rl("buildroot");

    /**
     * Alpine Linux 3.23 live image produced by the scev-alpine build
     * pipeline. Bootable in-place via the OPEN_FIRMWARE flash chip; see
     * {@link AlpineDiskTemplate} for the full layout.
     */
    public static final ResourceLocation ALPINE = rl("alpine");

    private DiskTemplateRegistry() {}

    /**
     * Register (or re-register) a template under {@code id}. Duplicates log
     * a warning and keep the first registration — same semantics as
     * {@code FirmwareRegistry}.
     */
    public static synchronized void register(ResourceLocation id, ScevDiskTemplate template) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(template, "template");
        if (template.assetName() == null || template.assetName().isEmpty()) {
            throw new IllegalArgumentException("template " + id + " has no assetName");
        }
        if (template.sizeMb() <= 0) {
            throw new IllegalArgumentException("template " + id + " size must be positive, got "
                    + template.sizeMb());
        }
        ScevDiskTemplate prior = ENTRIES.putIfAbsent(id, template);
        if (prior != null) {
            LOG.warn("Duplicate disk template registration for {}: keeping {}, ignoring {}",
                    id, prior.getClass().getSimpleName(), template.getClass().getSimpleName());
        } else {
            LOG.debug("Registered disk template {} -> {}", id, template.getClass().getSimpleName());
        }
    }

    /**
     * Retrieve the template registered under {@code id}, or {@code null}.
     * Callers handle {@code null} as "template missing / mod removed":
     * fall back to a blank image.
     */
    public static synchronized @Nullable ScevDiskTemplate get(@Nullable ResourceLocation id) {
        if (id == null) return null;
        return ENTRIES.get(id);
    }

    /** Does this id resolve to a registered template? */
    public static synchronized boolean contains(@Nullable ResourceLocation id) {
        return id != null && ENTRIES.containsKey(id);
    }

    /** All registered template ids, in registration order. Defensive copy. */
    public static synchronized Collection<ResourceLocation> ids() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(ENTRIES).keySet());
    }

    /** Current registration count. */
    public static synchronized int size() {
        return ENTRIES.size();
    }

    /**
     * Install the built-in disk templates. Idempotent. Wired into
     * {@link ScalarEvolution#onCommonSetup}.
     */
    public static synchronized void registerBuiltins() {
        register(BUILDROOT, BuildrootDiskTemplate.INSTANCE);
        register(ALPINE,    AlpineDiskTemplate.INSTANCE);
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, path);
    }

    /** Test-only: clear all registrations between test methods. */
    public static synchronized void clearForTests() {
        ENTRIES.clear();
    }
}
