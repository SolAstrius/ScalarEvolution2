/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen

import com.google.common.hash.Hashing
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPOutputStream
import lekkit.scev.main.ScalarEvolution
import net.minecraft.SharedConstants
import net.minecraft.data.CachedOutput
import net.minecraft.data.DataProvider
import net.minecraft.data.PackOutput
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo

/**
 * Writes an empty 3x3x3 `StructureTemplate` NBT file so the GameTest
 * framework has a placeholder it can load for every "empty"-structure
 * test.
 */
class ScevStructureProvider(private val output: PackOutput) : DataProvider {

    override fun run(writer: CachedOutput): CompletableFuture<*> {
        val tag = buildEmpty3x3x3()

        val bytes: ByteArray = try {
            val baos = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(baos)).use { dos -> NbtIo.write(tag, dos) }
            baos.toByteArray()
        } catch (e: IOException) {
            return CompletableFuture.failedFuture<Void>(e)
        }

        val path = output.getOutputFolder(PackOutput.Target.DATA_PACK)
            .resolve(ScalarEvolution.MODID)
            .resolve("structure")
            .resolve("empty.nbt")

        val hash = Hashing.sha1().hashBytes(bytes)
        return CompletableFuture.runAsync {
            try {
                writer.writeIfNeeded(path, bytes, hash)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }

    override fun getName(): String = "Scalar Evolution structure (empty)"

    companion object {
        private fun buildEmpty3x3x3(): CompoundTag {
            val tag = CompoundTag()
            tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion.version)

            val size = ListTag()
            size.add(IntTag.valueOf(3))
            size.add(IntTag.valueOf(3))
            size.add(IntTag.valueOf(3))
            tag.put("size", size)

            // No blocks (the structure block fills with air on load).
            tag.put("blocks", ListTag())

            // Palette with only air.
            val palette = ListTag()
            val air = CompoundTag()
            air.putString("Name", "minecraft:air")
            palette.add(air)
            tag.put("palette", palette)

            tag.put("entities", ListTag())
            return tag
        }
    }
}
