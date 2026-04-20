/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;
import lekkit.scev.main.ScalarEvolution;
import net.minecraft.SharedConstants;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * Writes an empty 3x3x3 {@code StructureTemplate} NBT file so the GameTest framework
 * has a placeholder it can load for every "empty"-structure test.
 */
public class ScevStructureProvider implements DataProvider {
    private final PackOutput output;

    public ScevStructureProvider(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput writer) {
        CompoundTag tag = buildEmpty3x3x3();

        byte[] bytes;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(baos))) {
                NbtIo.write(tag, dos);
            }
            bytes = baos.toByteArray();
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        Path path = output.getOutputFolder(PackOutput.Target.DATA_PACK)
                .resolve(ScalarEvolution.MODID)
                .resolve("structure")
                .resolve("empty.nbt");

        HashCode hash = Hashing.sha1().hashBytes(bytes);
        return CompletableFuture.runAsync(() -> {
            try {
                writer.writeIfNeeded(path, bytes, hash);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static CompoundTag buildEmpty3x3x3() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        ListTag size = new ListTag();
        size.add(IntTag.valueOf(3));
        size.add(IntTag.valueOf(3));
        size.add(IntTag.valueOf(3));
        tag.put("size", size);

        // No blocks (the structure block fills with air on load).
        tag.put("blocks", new ListTag());

        // Palette with only air.
        ListTag palette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        tag.put("palette", palette);

        tag.put("entities", new ListTag());
        return tag;
    }

    @Override
    public String getName() {
        return "Scalar Evolution structure (empty)";
    }
}
