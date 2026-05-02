/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware

import lekkit.scev.core.registry.ScevTypedRegistry
import lekkit.scev.main.ScalarEvolution
import net.minecraft.resources.ResourceLocation

/**
 * Static registry of [ScevFirmware] entries keyed by [ResourceLocation].
 *
 * Call [registerBuiltins] once during `FMLCommonSetupEvent` — this
 * installs the four first-party firmwares. Other mods can register
 * additional entries from their own common-setup hook; duplicate IDs
 * log a warning and keep the first registration.
 *
 * [get] returns the firmware or `null`. Callers must handle `null` as
 * "firmware missing / misspelled ID / mod removed between world saves":
 * in `RvvmMachineBackend` we log and fall through to the demo-bootrom
 * path rather than crash.
 */
object FirmwareRegistry : ScevTypedRegistry<ScevFirmware>() {

    /** OpenSBI + Linux kernel. The default flash-chip firmware. */
    @JvmField val LINUX: ResourceLocation = rl("linux")

    /** OpenSBI only (`fw_jump.bin`). */
    @JvmField val OPENSBI_ONLY: ResourceLocation = rl("opensbi_only")

    /** OpenSBI + U-Boot (`fw_payload.bin`). */
    @JvmField val OPEN_FIRMWARE: ResourceLocation = rl("open_firmware")

    /** Bare-metal RV32IM blinky. */
    @JvmField val BLINKY: ResourceLocation = rl("blinky")

    override val kind: String = "firmware"

    override fun validate(id: ResourceLocation, value: ScevFirmware) {
        require(value.payloads().isNotEmpty()) { "firmware $id has no payloads" }
    }

    /* Java-callable shims for the two methods our Java code calls by name
     * (`FirmwareRegistry.get(...)` / `.contains(...)`). The rest of the
     * inherited surface (register, size, ids, clearForTests) is reachable
     * via `FirmwareRegistry.INSTANCE.x()` from Java; the Kotlin call site
     * is `FirmwareRegistry.x(...)` either way. */
    @JvmStatic fun get(id: ResourceLocation?): ScevFirmware? = lookup(id)
    @JvmStatic fun contains(id: ResourceLocation?): Boolean = has(id)

    /**
     * Install the four built-in firmwares. Idempotent — calling twice
     * leaves the existing registrations in place (logging a dedup
     * warning each time). Wired into `ScalarEvolution.onCommonSetup`.
     */
    @JvmStatic
    fun registerBuiltins() {
        register(LINUX,         LinuxFirmware)
        register(OPENSBI_ONLY,  OpenSbiFirmware)
        register(OPEN_FIRMWARE, OpenFirmware)
        register(BLINKY,        BareMetalBlinkyFirmware)
    }

    private fun rl(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(ScalarEvolution.MODID, path)
}
