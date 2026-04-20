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

        SPEC = b.build();
    }

    private ScevConfig() {}
}
