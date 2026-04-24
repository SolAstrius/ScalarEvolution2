/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ScevConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue JIT;
    public static final ModConfigSpec.IntValue JIT_CACHE_MB;
    public static final ModConfigSpec.BooleanValue FAT_MODELS;

    // Disk-image GC settings. See lekkit.scev.server.gc for what each knob
    // controls. Event-driven GC is always on; the sweep is opt-in.
    public static final ModConfigSpec.IntValue GC_CREATION_GRACE_MINUTES;
    public static final ModConfigSpec.BooleanValue GC_SWEEP_ENABLED;
    public static final ModConfigSpec.IntValue GC_SWEEP_INTERVAL_HOURS;
    public static final ModConfigSpec.IntValue GC_SWEEP_RETENTION_DAYS;

    static {
        var b = new ModConfigSpec.Builder();

        b.comment("Runtime settings for the RISC-V virtual machine").push("runtime");
        JIT = b.comment("Enable JIT (Disable only if you have issues)")
                .define("jit", true);
        JIT_CACHE_MB = b.comment("Per-core JIT cache amount (MB)")
                .defineInRange("jit_cache_mb", 16, 1, 64);
        b.pop();

        b.comment("Visual settings").push("visual");
        FAT_MODELS = b.comment("Always draw fat 4-directional full-block models")
                .define("fat_models", false);
        b.pop();

        b.comment(
                "Disk-image garbage collector.",
                "Event-driven cleanup (when items are destroyed) runs automatically.",
                "The periodic sweep is opt-in — enable on long-lived servers where",
                "images may accumulate from silent destructions (/clear, /setblock,",
                "mod-based deletions that don't fire ItemExpireEvent)."
        ).push("gc");
        GC_CREATION_GRACE_MINUTES = b.comment(
                "Minimum age (wall-clock minutes) before any GC path can delete an image.",
                "Protects against races where an image was just created but the item",
                "hasn't yet been seen by a scanner. Purge bypasses this.")
                .defineInRange("creation_grace_minutes", 60, 1, 1440);
        GC_SWEEP_ENABLED = b.comment(
                "Enable periodic sweep. Default false — event-driven cleanup covers",
                "the common cases. Enable on servers where the image folder keeps",
                "growing despite nothing being destroyed in-game.")
                .define("sweep_enabled", false);
        GC_SWEEP_INTERVAL_HOURS = b.comment(
                "How often automatic sweep fires, in wall-clock hours.")
                .defineInRange("sweep_interval_hours", 24, 1, 168);
        GC_SWEEP_RETENTION_DAYS = b.comment(
                "How long a UUID must be unseen before sweep deletes its image,",
                "in wall-clock days. Counter resets on any scanner observation.",
                "Shorter = more aggressive; longer = more forgiving of rarely-visited chests.")
                .defineInRange("sweep_retention_days", 30, 1, 365);
        b.pop();

        SPEC = b.build();
    }

    private ScevConfig() {}
}
