/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import lekkit.scev.main.ScalarEvolution
import net.minecraft.core.HolderLookup
import net.minecraft.data.CachedOutput
import net.minecraft.data.DataProvider
import net.minecraft.data.PackOutput

/**
 * Emits JSON `test_instance` files under `data/scev/test_instance/`.
 *
 * In 1.21, GameTests are data-driven: each test is a JSON document
 * pointing at a registered test function. The actual test logic lives
 * in `lekkit.scev.test.ScevGameTests` (under src/main/java so it's
 * visible at runtime).
 */
class ScevGameTestProvider(
    private val output: PackOutput,
    @Suppress("UNUSED_PARAMETER") registries: CompletableFuture<HolderLookup.Provider>,
) : DataProvider {

    override fun run(writer: CachedOutput): CompletableFuture<*> {
        val root = output.getOutputFolder(PackOutput.Target.DATA_PACK)
            .resolve(ScalarEvolution.MODID)
            .resolve("test_instance")

        return CompletableFuture.allOf(
            DataProvider.saveStable(writer, functionTest("place_workstation"), root.resolve("place_workstation.json")),
            DataProvider.saveStable(writer, functionTest("place_terminal"),       root.resolve("place_terminal.json")),
        )
    }

    override fun getName(): String = "Scalar Evolution test_instance"

    companion object {
        private fun functionTest(testFnName: String): JsonElement {
            val o = JsonObject()
            o.addProperty("type", "minecraft:function")
            o.addProperty("function", "${ScalarEvolution.MODID}:$testFnName")
            o.addProperty("environment", "minecraft:default")
            o.addProperty("structure", "minecraft:empty")
            o.addProperty("max_ticks", 100)
            o.addProperty("setup_ticks", 0)
            o.addProperty("required", true)
            o.addProperty("rotation", "none")
            o.addProperty("manual_only", false)
            o.addProperty("max_attempts", 1)
            o.addProperty("required_successes", 1)
            o.addProperty("sky_access", false)
            return o
        }
    }
}
