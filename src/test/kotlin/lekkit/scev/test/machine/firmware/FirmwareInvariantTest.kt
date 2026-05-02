/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.firmware

import java.util.stream.Stream
import lekkit.scev.machine.firmware.FirmwareRegistry
import lekkit.scev.machine.firmware.ScevFirmware
import lekkit.scev.server.FirmwareAssets
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Invariants that must hold for *every* firmware registered in
 * [FirmwareRegistry]. Parameterized so that adding a new built-in (or a
 * third-party mod dropping one in at runtime) automatically gets these
 * checks — no per-class boilerplate test file needed.
 *
 * Per-firmware tests still exist, but only for invariants that are
 * genuinely unique to that firmware (cmdline content, specific asset size,
 * multi-payload ordering).
 */
class FirmwareInvariantTest {

    @ParameterizedTest(name = "[{0}] payloads() is non-empty")
    @MethodSource("registeredFirmwares")
    fun payloadsNonEmpty(id: String, fw: ScevFirmware) {
        assertFalse(fw.payloads().isEmpty(), "$id declared zero payloads")
    }

    @ParameterizedTest(name = "[{0}] first payload is a BOOTROM")
    @MethodSource("registeredFirmwares")
    fun firstPayloadIsBootrom(id: String, fw: ScevFirmware) {
        val first = fw.payloads().first()
        // RVVM's reset vector lands at mem_base; a KERNEL payload at
        // mem_base+0x200000 traps without something at the reset address
        // first. Every well-formed firmware therefore starts with a
        // BOOTROM — enforcing it here means FirmwareRegistry can boot any
        // registered firmware without the "load in order" contract needing
        // restatement per class.
        assertTrue(
            first.kind == ScevFirmware.Payload.Kind.BOOTROM,
            "$id first payload is ${first.kind} — expected BOOTROM so CPU reset has code to fetch",
        )
    }

    @ParameterizedTest(name = "[{0}] every payload asset is bundled on the classpath")
    @MethodSource("registeredFirmwares")
    fun allPayloadAssetsBundled(id: String, fw: ScevFirmware) {
        fw.payloads().forEach { payload ->
            assertFalse(payload.asset.isEmpty(), "$id has an empty asset name")
            assertTrue(
                FirmwareAssets.isBundled(payload.asset),
                "$id references asset '${payload.asset}' which is missing from " +
                    "src/main/resources/assets/scev/firmware/. Either the asset was " +
                    "removed/renamed without updating the firmware, or the mod jar " +
                    "is missing a build step.",
            )
        }
    }

    @ParameterizedTest(name = "[{0}] displayName is non-empty")
    @MethodSource("registeredFirmwares")
    fun displayNameNonEmpty(id: String, fw: ScevFirmware) {
        val name = fw.displayName()
        assertNotNull(name, "$id displayName is null")
        assertFalse(name.string.isEmpty(), "$id displayName is empty — tooltips / logs would be blank")
    }

    @ParameterizedTest(name = "[{0}] minRamMb > 0")
    @MethodSource("registeredFirmwares")
    fun minRamPositive(id: String, fw: ScevFirmware) {
        assertTrue(fw.minRamMb() > 0, "$id declared minRamMb=${fw.minRamMb()}; must be positive")
    }

    companion object {
        @JvmStatic
        fun registeredFirmwares(): Stream<Arguments> {
            Bootstrap.bootStrap()
            if (FirmwareRegistry.size() == 0) FirmwareRegistry.registerBuiltins()
            return FirmwareRegistry.ids().stream().map { id ->
                Arguments.of(id.toString(), FirmwareRegistry.get(id))
            }
        }
    }
}
