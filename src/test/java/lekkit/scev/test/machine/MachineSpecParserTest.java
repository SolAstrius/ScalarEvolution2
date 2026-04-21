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
import lekkit.scev.items.MotherboardInventory;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MachineSpecParser;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import lekkit.scev.machine.firmware.LinuxFirmware;
import lekkit.scev.machine.storage.BuildrootDiskTemplate;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.main.ScevDataComponents;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive coverage of {@link MachineSpecParser#fromMotherboard}.
 *
 * <p>These tests validate every decision the parser makes — what causes it
 * to return null, what the RAM floor is, how CPU levels map to SMP, which
 * slots map to which components. The parser is the single point where
 * "what's installed" becomes "what gets booted", so bugs here silently
 * manifest as missing features (no GPIO, no display, wrong memory size).
 */
class MachineSpecParserTest {

    @BeforeAll
    static void bootstrap() {
        // Minecraft needs its built-in registries bootstrapped before any
        // DataComponent or ItemStack is constructed. In a unit-test context
        // the game isn't otherwise booted; Bootstrap.bootStrap is idempotent
        // and safe to call repeatedly.
        Bootstrap.bootStrap();
        // Trigger the class init of BuiltInRegistries so static fields resolve.
        BuiltInRegistries.ITEM.getClass();
        // The parser now resolves ScevFirmware entries to compute the RAM
        // floor (firmware.minRamMb). In unit tests we run outside FML
        // setup, so no one has called registerBuiltins() — do it here.
        // Idempotent; safe to call alongside registrations from sibling
        // test classes (putIfAbsent semantics).
        FirmwareRegistry.registerBuiltins();
        DiskTemplateRegistry.registerBuiltins();
    }

    @Test
    @DisplayName("Empty ItemStack -> null spec")
    void emptyStackNull() {
        assertNull(MachineSpecParser.fromMotherboard(UUID.randomUUID(), ItemStack.EMPTY, false));
    }

    @Test
    @DisplayName("Non-motherboard stack -> null spec")
    void nonMotherboardNull() {
        assertNull(MachineSpecParser.fromMotherboard(
                UUID.randomUUID(), new ItemStack(ScevRegistry.CPU1.get()), false));
    }

    @Test
    @DisplayName("Motherboard alone -> MIN_RAM_MB memory floor, smp 1, no display")
    void emptyMotherboardFloor() {
        UUID uuid = UUID.randomUUID();
        MachineSpec spec = MachineSpecParser.fromMotherboard(
                uuid, new ItemStack(ScevRegistry.MOTHERBOARD1.get()), false);
        assertNotNull(spec);
        assertEquals(uuid, spec.uuid());
        assertEquals(MachineSpecParser.MIN_RAM_MB, spec.memMb());
        assertEquals(1, spec.smp());
        assertFalse(spec.hasDisplay());
        assertFalse(spec.hasGpio());
        assertFalse(spec.hasNic());
        assertFalse(spec.hasFirmware());
        assertTrue(spec.nvmeDrives().isEmpty());
    }

    @Test
    @DisplayName("CPU tier -> SMP count, 1/2/4 progression")
    void cpuLevelMapsToSmp() {
        for (int level = 1; level <= 3; level++) {
            var cpu = switch (level) {
                case 1 -> ScevRegistry.CPU1.get();
                case 2 -> ScevRegistry.CPU2.get();
                default -> ScevRegistry.CPU3.get();
            };
            int expectedSmp = switch (level) {
                case 1 -> 1;
                case 2 -> 2;
                default -> 4;
            };
            ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD3.get());
            new MotherboardInventory(() -> mbStack).setItem(MotherboardItem.SLOT_CPU, new ItemStack(cpu));
            MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
            assertNotNull(spec);
            assertEquals(expectedSmp, spec.smp(), "CPU level " + level);
        }
    }

    @Test
    @DisplayName("RAM sticks sum; level-1 board caps at slots 2-3")
    void ramSumAndLevelGating() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        // Level-1 motherboard: slots 2, 3 enabled; slots 4, 5 disabled.
        mb.setItem(MotherboardItem.SLOT_RAM_START,     new ItemStack(ScevRegistry.RAM_SODIMM2.get())); // 16 MiB
        mb.setItem(MotherboardItem.SLOT_RAM_START + 1, new ItemStack(ScevRegistry.RAM_SODIMM2.get())); // 16 MiB
        mb.setItem(MotherboardItem.SLOT_RAM_START + 2, new ItemStack(ScevRegistry.RAM_SODIMM4.get())); // 64 MiB (disabled)
        mb.setItem(MotherboardItem.SLOT_RAM_START + 3, new ItemStack(ScevRegistry.RAM_SODIMM4.get())); // 64 MiB (disabled)

        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        // Only slots 2 and 3 should be counted -> 32 MiB. No flash chip, so the
        // regular MIN_RAM_MB floor (not MIN_LINUX_RAM_MB) applies.
        // Since 32 < MIN_RAM_MB, we get the floor back.
        assertEquals(MachineSpecParser.MIN_RAM_MB, spec.memMb());
    }

    @Test
    @DisplayName("Flash chip installed -> RAM floor comes from LINUX firmware (Linux OOM regression)")
    void flashInstalledUsesLinuxRamFloor() {
        // Regression test for the "Kernel panic — system is deadlocked on memory
        // during pty_init" bug: with only MIN_RAM_MB (64) of RAM, the shipped
        // Linux 6.18 kernel + 26 MiB Buildroot initramfs runs out of free pages
        // and panics before reaching userspace. The parser must bump the floor
        // to LinuxFirmware.MIN_RAM_MB when a flash chip is present.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        // Just a flash chip — no RAM sticks installed.
        mb.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));

        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertNotNull(spec);
        assertTrue(spec.hasFirmware(), "flash chip should attach firmware");
        assertEquals(LinuxFirmware.MIN_RAM_MB, spec.memMb(),
                "Flash chip drives the LINUX Linux-boot firmware; RAM floor must be "
                        + "LinuxFirmware.MIN_RAM_MB (" + LinuxFirmware.MIN_RAM_MB + " MiB) "
                        + "or the kernel panics on pty_init. If you lowered it, verify the "
                        + "linux_kernel_boots_and_draws_fbcon GameTest still reaches 'still "
                        + "running after 20s' — a kernel that panicked is not running.");
        // Parser's legacy constant must still match the firmware's floor so
        // any code referencing MachineSpecParser.MIN_LINUX_RAM_MB gets the
        // right value.
        assertEquals(MachineSpecParser.MIN_LINUX_RAM_MB, LinuxFirmware.MIN_RAM_MB,
                "Parser's legacy MIN_LINUX_RAM_MB constant must equal LinuxFirmware.MIN_RAM_MB");
    }

    @Test
    @DisplayName("Flash + small RAM installed -> still bumped to MIN_LINUX_RAM_MB")
    void flashWithSmallRamBumpsToLinuxFloor() {
        // Real in-game scenario: user builds MOTHERBOARD1 + CPU1 + FLASH_CHIP
        // with a single RAM_SODIMM1 (8 MiB). 8 + flash-chip floor = 256 MiB.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        mb.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        mb.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get())); // 8 MiB

        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(MachineSpecParser.MIN_LINUX_RAM_MB, spec.memMb(),
                "8 MiB installed + flash chip must clamp to Linux floor");
    }

    @Test
    @DisplayName("Flash + RAM above Linux floor -> actual RAM passes through")
    void flashWithEnoughRamPassesThrough() {
        // User installs 4 x RAM_SODIMM4 (64 MiB each) = 256 MiB. Exactly at the
        // floor — no-op. Adding even one more would push above the floor.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD3.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        mb.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        for (int i = 0; i < 4; i++) {
            mb.setItem(MotherboardItem.SLOT_RAM_START + i, new ItemStack(ScevRegistry.RAM_SODIMM4.get()));
        }
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(256, spec.memMb(), "4 x 64 MiB = 256 MiB -> exactly Linux floor");
    }

    @Test
    @DisplayName("No flash chip -> MIN_RAM_MB floor (not Linux floor)")
    void noFlashKeepsDemoFloor() {
        // Demo-bootrom path: no flash, no kernel, no need for Linux RAM budget.
        // The spec should hold the low MIN_RAM_MB floor so tests/demo machines
        // aren't forced to allocate more than they need.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(MachineSpecParser.MIN_RAM_MB, spec.memMb());
        assertTrue(MachineSpecParser.MIN_RAM_MB < MachineSpecParser.MIN_LINUX_RAM_MB,
                "Demo floor must be strictly below Linux floor — otherwise the two-floor "
                        + "split is meaningless. If you raised MIN_RAM_MB, confirm you didn't "
                        + "clobber the split with MIN_LINUX_RAM_MB.");
    }

    @Test
    @DisplayName("Enough RAM to exceed floor passes through unchanged")
    void ramAboveFloor() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD3.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        mb.setItem(MotherboardItem.SLOT_RAM_START,     new ItemStack(ScevRegistry.RAM_SODIMM4.get())); // 64
        mb.setItem(MotherboardItem.SLOT_RAM_START + 1, new ItemStack(ScevRegistry.RAM_SODIMM4.get())); // 64
        mb.setItem(MotherboardItem.SLOT_RAM_START + 2, new ItemStack(ScevRegistry.RAM_SODIMM4.get())); // 64
        mb.setItem(MotherboardItem.SLOT_RAM_START + 3, new ItemStack(ScevRegistry.RAM_SODIMM4.get())); // 64

        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(256, spec.memMb());
    }

    @Test
    @DisplayName("VGA PCI card -> hasDisplay")
    void vgaCardAttachesDisplay() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack)
                .setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.VGA_CARD.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertTrue(spec.hasDisplay());
        assertEquals(640, spec.display().width());
        assertEquals(480, spec.display().height());
    }

    @Test
    @DisplayName("forceDisplay=true -> display even without VGA card")
    void forceDisplayWorks() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, true);
        assertTrue(spec.hasDisplay(), "Tinkerpad-style forceDisplay should attach a built-in screen");
    }

    @Test
    @DisplayName("GPIO card -> hasGpio; RTL8169 -> hasNic")
    void pciCardKindDispatch() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD3.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        mb.setItem(MotherboardItem.SLOT_PCI_START,     new ItemStack(ScevRegistry.GPIO_CARD.get()));
        mb.setItem(MotherboardItem.SLOT_PCI_START + 1, new ItemStack(ScevRegistry.RTL8169.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertTrue(spec.hasGpio());
        assertTrue(spec.hasNic());
        assertFalse(spec.hasDisplay());
    }

    @Test
    @DisplayName("Flash slot -> FirmwareSpec referencing the LINUX registry id")
    void flashPopulatesFirmwareSpec() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack)
                .setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertTrue(spec.hasFirmware());
        MachineSpec.FirmwareSpec fw = spec.firmware();
        assertNotNull(fw);
        assertEquals(8, fw.sizeMb());
        // Post-registry: parser emits a registry reference, not a direct asset
        // name. The LINUX firmware entry handles both the bootrom (fw_jump.bin)
        // and the kernel (Image) as its two payloads; see LinuxFirmwareTest.
        assertTrue(fw.hasRegistryRef(), "Flash chip must produce a registry-referenced firmware");
        assertEquals(FirmwareRegistry.LINUX, fw.firmwareId(),
                "Default flash-chip firmware is LINUX (OpenSBI + Linux kernel)");
        assertNull(fw.origin(),
                "Registry-driven FirmwareSpec doesn't need a direct origin — "
                        + "the firmware's payloads list provides asset names");
        assertEquals(MachineSpecParser.DEFAULT_FIRMWARE_ID, fw.firmwareId(),
                "DEFAULT_FIRMWARE_ID must match the emitted id — bump both together");
    }

    @Test
    @DisplayName("Flash slot -> no separate KernelSpec (kernel is part of the firmware payload list)")
    void flashDoesNotPopulateKernelSpec() {
        // Post-registry: the LINUX firmware carries the kernel as its second
        // payload. The parser used to emit a parallel KernelSpec as a
        // coupling hack; that path is gone. Tests / power users can still
        // attach a KernelSpec directly via MachineSpec.Builder.kernel(...)
        // when layering a custom kernel on top of a bootrom-only firmware.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack)
                .setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertFalse(spec.hasKernel(),
                "Parser no longer emits a parallel KernelSpec — the LINUX firmware's "
                        + "KERNEL payload handles kernel loading internally. If a test "
                        + "expects spec.hasKernel() it's checking the old coupling; "
                        + "inspect spec.firmware().firmwareId() instead.");
    }

    @Test
    @DisplayName("No flash chip -> no firmware")
    void noFlashNoFirmware() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertFalse(spec.hasFirmware());
        assertFalse(spec.hasKernel(),
                "Without a flash chip there's no firmware and hence no kernel");
    }

    @Test
    @DisplayName("Two NVMe drives in both slots -> both in spec.nvmeDrives")
    void nvmeDrivesCollected() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD3.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        mb.setItem(MotherboardItem.SLOT_NVME_START,     new ItemStack(ScevRegistry.NVME.get()));
        mb.setItem(MotherboardItem.SLOT_NVME_START + 1, new ItemStack(ScevRegistry.NVME.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(2, spec.nvmeDrives().size());
        for (var d : spec.nvmeDrives()) assertEquals(2048, d.sizeMb());
    }

    @Test
    @DisplayName("Blank NvmeItem -> DiskSpec without templateId (direct-origin path)")
    void blankNvmeNoTemplate() {
        // A blank NvmeItem routes through the direct-origin path: origin is
        // "rootfs.ext2" (classpath lookup can fall through to blank if the
        // asset doesn't exist), templateId stays null.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack)
                .setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(1, spec.nvmeDrives().size());
        MachineSpec.DiskSpec d = spec.nvmeDrives().get(0);
        assertFalse(d.hasTemplateRef(),
                "Blank NvmeItem must NOT carry a template reference — otherwise the "
                        + "backend would try to seed from a non-existent template id.");
        assertEquals("rootfs.ext2", d.origin());
    }

    @Test
    @DisplayName("Preloaded NVMe -> DiskSpec with templateId=scev:buildroot, origin from template")
    void preloadedNvmeCarriesTemplateId() {
        // This is the new item behavior. Parser sees a PreloadedNvmeItem in
        // an NVMe slot and emits DiskSpec with templateId set. The backend
        // resolves the template and seeds the per-UUID image from
        // template.assetName() — no hardcoded "rootfs.ext2" string involved.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack)
                .setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME_PRELOADED.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(1, spec.nvmeDrives().size());
        MachineSpec.DiskSpec d = spec.nvmeDrives().get(0);
        assertTrue(d.hasTemplateRef(),
                "Preloaded NVMe must emit a DiskSpec with templateId set — this is what "
                        + "drives the backend to seed from DiskTemplateRegistry.");
        assertEquals(DiskTemplateRegistry.BUILDROOT, d.templateId(),
                "Default template must be DiskTemplateRegistry.BUILDROOT (the shipped ext2 rootfs).");
        assertEquals(BuildrootDiskTemplate.ASSET_NAME, d.origin(),
                "origin is populated from the resolved template for legacy code paths that "
                        + "still read origin as a string — consistent with getOrigin() on the item.");
        assertEquals(BuildrootDiskTemplate.SIZE_MB, d.sizeMb(),
                "sizeMb mirrors the template's declared capacity, not the blank-NVMe default.");
    }

    @Test
    @DisplayName("Mix: blank NVMe + preloaded NVMe in adjacent slots -> both emitted with correct shapes")
    void mixedNvmeSlots() {
        // Level-3 motherboard enables both NVMe slots.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD3.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        mb.setItem(MotherboardItem.SLOT_NVME_START,     new ItemStack(ScevRegistry.NVME.get()));
        mb.setItem(MotherboardItem.SLOT_NVME_START + 1, new ItemStack(ScevRegistry.NVME_PRELOADED.get()));
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(2, spec.nvmeDrives().size());

        MachineSpec.DiskSpec blank    = spec.nvmeDrives().get(0);
        MachineSpec.DiskSpec preloaded = spec.nvmeDrives().get(1);
        assertFalse(blank.hasTemplateRef());
        assertTrue(preloaded.hasTemplateRef());
        assertEquals(DiskTemplateRegistry.BUILDROOT, preloaded.templateId());
        // UUIDs must be distinct — each ItemStack owns its own persistent image.
        assertNotEquals(blank.uuid(), preloaded.uuid());
    }

    @Test
    @DisplayName("Level-1 board disables NVMe slot 7 -> only 1 NVMe emitted")
    void nvmeLevelGating() {
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory mb = new MotherboardInventory(() -> mbStack);
        mb.setItem(MotherboardItem.SLOT_NVME_START,     new ItemStack(ScevRegistry.NVME.get())); // enabled
        mb.setItem(MotherboardItem.SLOT_NVME_START + 1, new ItemStack(ScevRegistry.NVME.get())); // DISABLED
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);
        assertEquals(1, spec.nvmeDrives().size());
    }

    // -- Firmware data-component precedence --------------------------------
    //
    // Flash chip carries up to three data components that decide firmware.
    // Resolved in this order: bytes > id-override > kind > default(LINUX).
    // A bug in precedence would silently boot the wrong firmware, which is
    // the worst kind of failure (no stack trace, just "why doesn't my
    // blinky blink").

    @Test
    @DisplayName("FIRMWARE_KIND=BLINKY on flash -> spec firmwareId references BLINKY registry entry")
    void flashKindBlinky() {
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.BLINKY);

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack).setItem(MotherboardItem.SLOT_FLASH, flash);
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);

        MachineSpec.FirmwareSpec fw = spec.firmware();
        assertEquals(FirmwareRegistry.BLINKY, fw.firmwareId(),
                "FIRMWARE_KIND=BLINKY must route to the BLINKY registry id");
        assertNull(fw.rawBytes(), "kind-only path must not emit rawBytes");
    }

    @Test
    @DisplayName("FIRMWARE_KIND=BLANK on flash -> firmwareId stays null (explicit no-firmware)")
    void flashKindBlank() {
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.BLANK);

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack).setItem(MotherboardItem.SLOT_FLASH, flash);
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);

        // Flash IS still present (the chip exists in the slot) but carries
        // the explicit BLANK kind — backend will boot demo bootrom fallback.
        assertTrue(spec.hasFirmware(),
                "BLANK flash still emits a FirmwareSpec (the UUID + sizeMb metadata), "
                        + "just with no firmwareId to resolve");
        assertNull(spec.firmware().firmwareId(),
                "BLANK kind must not resolve to any firmware");
    }

    @Test
    @DisplayName("No FIRMWARE_KIND component -> parser falls back to LINUX (legacy-world compat)")
    void flashNoComponentFallsBackToLinux() {
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        // deliberately no data components set

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack).setItem(MotherboardItem.SLOT_FLASH, flash);
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);

        assertEquals(FirmwareRegistry.LINUX, spec.firmware().firmwareId(),
                "Pre-component worlds had flash chips with no NBT; those must keep "
                        + "booting LINUX after the data-component migration");
    }

    @Test
    @DisplayName("FIRMWARE_ID_OVERRIDE wins over FIRMWARE_KIND (third-party escape hatch)")
    void idOverrideWinsOverKind() {
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.LINUX);
        flash.set(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get(),
                ResourceLocation.fromNamespaceAndPath("othermod", "crazy_bios"));

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack).setItem(MotherboardItem.SLOT_FLASH, flash);
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);

        assertEquals(ResourceLocation.fromNamespaceAndPath("othermod", "crazy_bios"),
                spec.firmware().firmwareId(),
                "A mod-set id override must take precedence over the typed enum so "
                        + "integration mods can re-target chips without touching our enum");
    }

    @Test
    @DisplayName("FIRMWARE_BYTES wins over everything (player-authored custom flash)")
    void rawBytesWinsOverAll() {
        byte[] payload = {0x13, 0, 0, 0};  // addi x0, x0, 0 — a nop for RV
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.LINUX);
        flash.set(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get(),
                ResourceLocation.fromNamespaceAndPath("othermod", "crazy_bios"));
        flash.set(ScevDataComponents.FIRMWARE_BYTES.get(), new FirmwareBlob(payload));

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack).setItem(MotherboardItem.SLOT_FLASH, flash);
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);

        MachineSpec.FirmwareSpec fw = spec.firmware();
        assertTrue(fw.hasRawBytes(),
                "Raw bytes is the single source of truth for custom firmware; "
                        + "precedence must override both kind and override");
        assertNull(fw.firmwareId(),
                "When rawBytes wins, the id path must not also be emitted — otherwise "
                        + "the backend has to untangle which one is 'real'");
    }

    @Test
    @DisplayName("Empty FIRMWARE_BYTES (zero-length) falls through to kind/default, not wins")
    void emptyRawBytesDoesNotWin() {
        // Guard against a chip that has an empty bytes blob (e.g. someone
        // started flashing and cancelled) being treated as "use these bytes
        // = nothing" instead of "no custom content". The predicate is
        // hasRawBytes() = non-null AND non-empty.
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_BYTES.get(), new FirmwareBlob(new byte[0]));
        flash.set(ScevDataComponents.FIRMWARE_KIND.get(), FlashFirmware.BLINKY);

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        new MotherboardInventory(() -> mbStack).setItem(MotherboardItem.SLOT_FLASH, flash);
        MachineSpec spec = MachineSpecParser.fromMotherboard(UUID.randomUUID(), mbStack, false);

        assertEquals(FirmwareRegistry.BLINKY, spec.firmware().firmwareId(),
                "Empty byte blob must not suppress the typed kind — it's 'I intended "
                        + "to flash but didn't' not 'use my zero bytes as firmware'");
    }
}
