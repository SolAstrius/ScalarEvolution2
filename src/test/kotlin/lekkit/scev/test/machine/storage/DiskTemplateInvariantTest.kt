/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.storage

import java.util.stream.Stream
import lekkit.scev.machine.storage.DiskTemplateRegistry
import lekkit.scev.machine.storage.ScevDiskTemplate
import lekkit.scev.server.FirmwareAssets
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Invariants that must hold for every entry in [DiskTemplateRegistry].
 * Parameterized so new templates — built-in or mod-added — get these
 * checks automatically. Per-template tests stay only for file-format
 * invariants that can't be generalised (e.g. ext2 superblock layout).
 */
class DiskTemplateInvariantTest {

    @ParameterizedTest(name = "[{0}] assetName is non-empty")
    @MethodSource("registeredTemplates")
    fun assetNameNonEmpty(id: String, tmpl: ScevDiskTemplate) {
        assertFalse(
            tmpl.assetName().isEmpty(),
            "$id declared an empty assetName — StorageManager.copyImage would no-op silently",
        )
    }

    @ParameterizedTest(name = "[{0}] sizeMb > 0")
    @MethodSource("registeredTemplates")
    fun sizePositive(id: String, tmpl: ScevDiskTemplate) {
        assertTrue(
            tmpl.sizeMb() > 0,
            "$id declared sizeMb=${tmpl.sizeMb()}; a zero-byte image would fail createImage",
        )
    }

    @ParameterizedTest(name = "[{0}] declared asset is bundled on the classpath")
    @MethodSource("registeredTemplates")
    fun assetBundled(id: String, tmpl: ScevDiskTemplate) {
        assertTrue(
            FirmwareAssets.isBundled(tmpl.assetName()),
            "$id references '${tmpl.assetName()}' which is missing from " +
                "src/main/resources/assets/scev/firmware/. PreloadedNvmeItems bound " +
                "to this template would silently fall back to blank images.",
        )
    }

    @ParameterizedTest(name = "[{0}] displayName is non-empty")
    @MethodSource("registeredTemplates")
    fun displayNameNonEmpty(id: String, tmpl: ScevDiskTemplate) {
        val name = tmpl.displayName()
        assertNotNull(name, "$id displayName is null")
        assertFalse(name.string.isEmpty(), "$id displayName renders blank in tooltips and logs")
    }

    companion object {
        @JvmStatic
        fun registeredTemplates(): Stream<Arguments> {
            Bootstrap.bootStrap()
            if (DiskTemplateRegistry.size() == 0) DiskTemplateRegistry.registerBuiltins()
            return DiskTemplateRegistry.ids().stream().map { id ->
                Arguments.of(id.toString(), DiskTemplateRegistry.get(id))
            }
        }
    }
}
