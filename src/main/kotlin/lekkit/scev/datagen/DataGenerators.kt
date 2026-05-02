/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen

import net.neoforged.neoforge.data.event.GatherDataEvent

object DataGenerators {

    @JvmStatic
    fun onGatherData(event: GatherDataEvent) {
        val gen = event.generator
        val output = gen.packOutput
        val helper = event.existingFileHelper
        val lookup = event.lookupProvider

        gen.addProvider(event.includeClient(), ScevBlockStateProvider(output, helper))
        gen.addProvider(event.includeClient(), ScevItemModelProvider(output, helper))
        // en_us.json is hand-maintained at
        // src/main/resources/assets/scev/lang/en_us.json — no LanguageProvider
        // here. The provider was 178 LOC of `add("key", "value")` boilerplate
        // wrapping vanilla's auto-key derivation; the JSON is shorter, version-
        // control-friendlier, and avoids the runData round-trip whenever a
        // string changes.
        gen.addProvider(event.includeServer(), ScevRecipeProvider(output, lookup))
        gen.addProvider(event.includeServer(), ScevGameTestProvider(output, lookup))
        gen.addProvider(event.includeServer(), ScevStructureProvider(output))
    }
}
