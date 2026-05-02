/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.storage

import lekkit.scev.core.registry.ScevTypedRegistry
import lekkit.scev.main.ScalarEvolution
import net.minecraft.resources.ResourceLocation

/**
 * Static registry of [ScevDiskTemplate] entries keyed by
 * [ResourceLocation]. Companion to
 * [lekkit.scev.machine.firmware.FirmwareRegistry].
 */
object DiskTemplateRegistry : ScevTypedRegistry<ScevDiskTemplate>() {

    /**
     * Buildroot 2026.02 Linux rootfs. Default template for
     * [lekkit.scev.items.PreloadedNvmeItem].
     */
    @JvmField val BUILDROOT: ResourceLocation = rl("buildroot")

    /**
     * Alpine Linux 3.23 live image produced by the scev-alpine build
     * pipeline. Bootable in-place via the OPEN_FIRMWARE flash chip.
     */
    @JvmField val ALPINE: ResourceLocation = rl("alpine")

    override val kind: String = "disk template"

    override fun validate(id: ResourceLocation, value: ScevDiskTemplate) {
        require(value.assetName().isNotEmpty()) { "template $id has no assetName" }
        require(value.sizeMb() > 0) { "template $id size must be positive, got ${value.sizeMb()}" }
    }

    /* Java-callable shims — see the parallel comment in FirmwareRegistry. */
    @JvmStatic fun get(id: ResourceLocation?): ScevDiskTemplate? = lookup(id)
    @JvmStatic fun contains(id: ResourceLocation?): Boolean = has(id)

    /**
     * Install the built-in disk templates. Idempotent. Wired into
     * [ScalarEvolution.onCommonSetup].
     */
    @JvmStatic
    fun registerBuiltins() {
        register(BUILDROOT, BuildrootDiskTemplate)
        register(ALPINE,    AlpineDiskTemplate)
    }

    private fun rl(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, path)
}
