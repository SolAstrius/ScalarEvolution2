/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

/**
 * Emits JSON {@code test_instance} files under {@code data/scev/test_instance/}.
 *
 * <p>In 1.21, GameTests are data-driven: each test is a JSON document pointing at a
 * registered test function. The actual test logic lives in
 * {@link lekkit.scev.test.ScevGameTests} (under src/main/java so it's visible at runtime).
 */
public class ScevGameTestProvider implements DataProvider {
    private final PackOutput output;

    public ScevGameTestProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput writer) {
        var root = output.getOutputFolder(PackOutput.Target.DATA_PACK).resolve(ScalarEvolution.MODID).resolve("test_instance");

        return CompletableFuture.allOf(
                DataProvider.saveStable(writer, functionTest("place_workstation"), root.resolve("place_workstation.json")),
                DataProvider.saveStable(writer, functionTest("place_vt100"),       root.resolve("place_vt100.json"))
        );
    }

    private static com.google.gson.JsonElement functionTest(String testFnName) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:function");
        o.addProperty("function", ScalarEvolution.MODID + ":" + testFnName);
        o.addProperty("environment", "minecraft:default");
        o.addProperty("structure", "minecraft:empty");
        o.addProperty("max_ticks", 100);
        o.addProperty("setup_ticks", 0);
        o.addProperty("required", true);
        o.addProperty("rotation", "none");
        o.addProperty("manual_only", false);
        o.addProperty("max_attempts", 1);
        o.addProperty("required_successes", 1);
        o.addProperty("sky_access", false);
        return o;
    }

    @Override
    public String getName() {
        return "Scalar Evolution test_instance";
    }
}
