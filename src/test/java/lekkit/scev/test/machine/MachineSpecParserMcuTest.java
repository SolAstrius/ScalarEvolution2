/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.scev.items.FirmwareBlob;
import lekkit.scev.items.FlashFirmware;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MachineSpecParser;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import lekkit.scev.main.ScevDataComponents;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Coverage of {@link MachineSpecParser#fromMcu}. Parallel to
 * {@link MachineSpecParserTest} but focused on the MCU shape: SoC carries
 * CPU+RAM+ISA, flash (if any) carries firmware, everything else is implicit.
 *
 * <p>Invariants pinned here:
 * <ul>
 *   <li>Missing SoC → null spec (can't boot without a CPU).</li>
 *   <li>SoC's ISA / hartCount flow through verbatim.</li>
 *   <li>On-die RAM clamps up to firmware floor (blinky: 1, linux: 256).</li>
 *   <li>hasGpio is always true — the SoC bonds the redstone pins.</li>
 *   <li>No display, no PCI, no NVMe from the MCU path.</li>
 *   <li>Flash's firmware component chain is respected (bytes > kind > default).</li>
 * </ul>
 */
class MachineSpecParserMcuTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap();
        BuiltInRegistries.ITEM.getClass();
        FirmwareRegistry.registerBuiltins();
    }

    @Test
    @DisplayName("Empty SoC stack -> null spec (can't boot an MCU without a SoC)")
    void noSocNullSpec() {
        assertNull(MachineSpecParser.fromMcu(UUID.randomUUID(), ItemStack.EMPTY, ItemStack.EMPTY));
    }

    @Test
    @DisplayName("Non-SoC item -> null spec (e.g. a flash chip in the SoC slot)")
    void wrongItemInSocSlotNullSpec() {
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        assertNull(MachineSpecParser.fromMcu(UUID.randomUUID(), flash, ItemStack.EMPTY));
    }

    @Test
    @DisplayName("SoC1 + Blinky flash: rv32im, 1 hart, mem clamped to blinky floor (1 MiB)")
    void socOneWithBlinky() {
        ItemStack soc = new ItemStack(ScevRegistry.SOC1.get());
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.BLINKY);

        UUID uuid = UUID.randomUUID();
        MachineSpec spec = MachineSpecParser.fromMcu(uuid, soc, flash);

        assertNotNull(spec);
        assertEquals(uuid, spec.uuid());
        assertEquals("rv32im", spec.isa(),
                "SoC1 is a Tier-1 microcontroller chip — rv32im carries through");
        assertEquals(1, spec.smp(), "SoC1 has 1 hart");
        // SoC1 declares 4 KiB on-die RAM. The minimum-memory clamp in
        // fromMcu is max(MIN_RAM_MB, firmware.minRamMb). MIN_RAM_MB is 64,
        // which wins here. This is honest — players see 4 KiB on the tooltip
        // but the VM gets 64 MiB so it actually has working memory.
        assertTrue(spec.memMb() >= MachineSpecParser.MIN_RAM_MB,
                "memMb must clamp up to MIN_RAM_MB (64 MiB) at minimum");

        // Firmware routing
        assertTrue(spec.hasFirmware());
        assertEquals(FirmwareRegistry.BLINKY, spec.firmware().firmwareId());

        // Implicit peripherals
        assertTrue(spec.hasGpio(), "MCU must always expose GPIO — the SoC bonds the pins");
        assertFalse(spec.hasDisplay(), "MCU has no display");
        assertFalse(spec.hasNic(), "MCU has no NIC");
        assertTrue(spec.nvmeDrives().isEmpty(), "MCU has no NVMe drives");
        assertFalse(spec.hasKernel(), "Blinky has no kernel payload (bare-metal)");
    }

    @Test
    @DisplayName("SoC3 + no flash: rv64imac, 2 harts, no firmware attached")
    void socThreeNoFlash() {
        ItemStack soc = new ItemStack(ScevRegistry.SOC3.get());

        MachineSpec spec = MachineSpecParser.fromMcu(UUID.randomUUID(), soc, ItemStack.EMPTY);
        assertNotNull(spec);
        assertEquals("rv64imac", spec.isa());
        assertEquals(2, spec.smp());
        assertFalse(spec.hasFirmware(),
                "Without a flash chip, no FirmwareSpec is emitted — CPU hits the demo "
                        + "bootrom fallback or traps. Either way, not our parser's problem.");
        assertTrue(spec.hasGpio(), "GPIO is implicit regardless of flash");
    }

    @Test
    @DisplayName("SoC2 + Linux flash: mem clamps up to Linux's 256 MiB floor")
    void socTwoWithLinuxClampsMemory() {
        // Tier-2 SoC declares 256 KiB on-die RAM. Linux firmware's declared
        // minRamMb is 256 — way higher. The clamp must prefer the firmware's
        // floor so Linux can actually boot instead of OOMing.
        ItemStack soc = new ItemStack(ScevRegistry.SOC2.get());
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.LINUX);

        MachineSpec spec = MachineSpecParser.fromMcu(UUID.randomUUID(), soc, flash);
        assertEquals(256L, spec.memMb(),
                "Linux firmware requires 256 MiB — clamp must bump SoC's declared "
                        + "256 KiB up to match, otherwise the kernel panics during init");
    }

    @Test
    @DisplayName("SoC1 + Custom flash bytes: spec carries rawBytes, no firmwareId")
    void customBytesPreempts() {
        ItemStack soc = new ItemStack(ScevRegistry.SOC1.get());
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        byte[] payload = {0x13, 0, 0, 0}; // one nop — just has to be non-empty
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.BLINKY);
        flash.set(ScevDataComponents.FIRMWARE_BYTES.get(), new FirmwareBlob(payload));

        MachineSpec spec = MachineSpecParser.fromMcu(UUID.randomUUID(), soc, flash);

        MachineSpec.FirmwareSpec fw = spec.firmware();
        assertTrue(fw.hasRawBytes(),
                "Raw bytes on the flash chip must take precedence over the typed kind "
                        + "in the MCU path — same precedence as the motherboard path");
        assertNull(fw.firmwareId(), "When rawBytes wins, firmwareId must be null");
    }

    @Test
    @DisplayName("SoC1 + Blank flash: FirmwareSpec emitted but firmwareId is null")
    void blankFlashEmitsEmptyFirmwareSpec() {
        ItemStack soc = new ItemStack(ScevRegistry.SOC1.get());
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.BLANK);

        MachineSpec spec = MachineSpecParser.fromMcu(UUID.randomUUID(), soc, flash);
        assertTrue(spec.hasFirmware(),
                "The flash chip itself is still a physical chip with a UUID/sizeMb — "
                        + "emit FirmwareSpec without a firmwareId to route");
        assertNull(spec.firmware().firmwareId());
    }
}
