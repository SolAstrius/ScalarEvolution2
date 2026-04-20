/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import java.util.concurrent.CompletableFuture;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class DataGenerators {
    private DataGenerators() {}

    public static void onGatherData(GatherDataEvent event) {
        DataGenerator gen = event.getGenerator();
        PackOutput output = gen.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        gen.addProvider(event.includeClient(),
                new ScevBlockStateProvider(output, existingFileHelper));
        gen.addProvider(event.includeClient(),
                new ScevItemModelProvider(output, existingFileHelper));
        gen.addProvider(event.includeClient(),
                new ScevLangProvider(output));
        gen.addProvider(event.includeServer(),
                new ScevRecipeProvider(output, lookup));
        gen.addProvider(event.includeServer(),
                new ScevGameTestProvider(output, lookup));
        gen.addProvider(event.includeServer(),
                new ScevStructureProvider(output));
    }
}
