/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.scev.machine.MachineSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link MachineSpec} record: builder defaults,
 * validation, and immutability. Doesn't touch Minecraft — pure JUnit.
 */
class MachineSpecTest {

    @BeforeAll
    static void bootstrap() {
        // ResourceLocation.parse needs built-in registries bootstrapped;
        // used by the firmwareId tests below.
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("Builder defaults produce a minimally viable spec")
    void builderDefaults() {
        UUID uuid = UUID.randomUUID();
        MachineSpec s = MachineSpec.builder(uuid).build();
        assertEquals(uuid, s.uuid());
        assertEquals(64, s.memMb());
        assertEquals(1, s.smp());
        assertEquals("rv64", s.isa());
        assertFalse(s.hasDisplay());
        assertFalse(s.hasFirmware());
        assertFalse(s.hasNic());
        assertFalse(s.hasGpio());
        assertFalse(s.hasSound(),
                "Default spec must not attach a sound card — opt-in via the sound_card PCI slot.");
        assertEquals("", s.cmdline(),
                "Default cmdline is empty — the pre-abstraction "
                        + "\"root=/dev/nvme0n1 rw\" default was benign only because Linux "
                        + "ignored it under an initramfs rootfs. Post-abstraction "
                        + "(ScevDiskTemplate.hasRootFilesystem × ScevFirmware.wantsNvmeRoot), "
                        + "root= is injected by the parser only when both sides opt in.");
        assertTrue(s.nvmeDrives().isEmpty());
        assertFalse(s.hasKernel(),
                "Default spec must not carry a KernelSpec — only the flash-chip path opts in.");
        assertEquals(MachineSpec.BootromMode.FIRMWARE_ELSE_DEMO, s.bootromMode(),
                "Default bootrom mode must prefer real firmware (otherwise out-of-box is a demo loop).");
    }

    @Test
    @DisplayName("Builder.hasSound() flows through to the record")
    void builderHasSoundChain() {
        MachineSpec on = MachineSpec.builder(UUID.randomUUID()).hasSound(true).build();
        assertTrue(on.hasSound(),
                "Builder.hasSound(true) must propagate to the record; the RvvmMachineBackend "
                        + "reads spec.hasSound() to decide whether to attach the SoundHDA device.");

        MachineSpec off = MachineSpec.builder(UUID.randomUUID()).hasSound(false).build();
        assertFalse(off.hasSound());
    }

    @Test
    @DisplayName("Builder chaining sets every field")
    void builderChain() {
        UUID uuid = UUID.randomUUID();
        UUID fwUuid = UUID.randomUUID();
        UUID diskUuid = UUID.randomUUID();
        MachineSpec s = MachineSpec.builder(uuid)
                .memMb(256)
                .smp(2)
                .isa("rv32i")
                .firmware(new MachineSpec.FirmwareSpec(fwUuid, 8, "fw.bin"))
                .display(new MachineSpec.DisplaySpec(800, 600))
                .hasNic(true)
                .hasGpio(true)
                .hasSound(true)
                .nvme(new MachineSpec.DiskSpec(diskUuid, 1024, "disk.img"))
                .cmdline("quiet")
                .build();
        assertEquals(256, s.memMb());
        assertEquals(2, s.smp());
        assertEquals("rv32i", s.isa());
        assertEquals(fwUuid, s.firmware().uuid());
        assertEquals(800, s.display().width());
        assertEquals(600, s.display().height());
        assertTrue(s.hasNic());
        assertTrue(s.hasGpio());
        assertTrue(s.hasSound());
        assertEquals(1, s.nvmeDrives().size());
        assertEquals("quiet", s.cmdline());
    }

    @Test
    @DisplayName("nvmeDrives list is immutable")
    void nvmeDrivesImmutable() {
        MachineSpec s = MachineSpec.builder(UUID.randomUUID())
                .nvme(new MachineSpec.DiskSpec(UUID.randomUUID(), 1, null))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> s.nvmeDrives().add(new MachineSpec.DiskSpec(UUID.randomUUID(), 1, null)));
    }

    @Test
    @DisplayName("Negative memory is rejected")
    void rejectsNegativeMemory() {
        assertThrows(IllegalArgumentException.class,
                () -> MachineSpec.builder(UUID.randomUUID()).memMb(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> MachineSpec.builder(UUID.randomUUID()).memMb(-1).build());
    }

    @Test
    @DisplayName("SMP < 1 is rejected")
    void rejectsZeroSmp() {
        assertThrows(IllegalArgumentException.class,
                () -> MachineSpec.builder(UUID.randomUUID()).smp(0).build());
    }

    @Test
    @DisplayName("defaultDisplay() attaches DEFAULT_DISPLAY (640x480)")
    void defaultDisplayConstant() {
        MachineSpec s = MachineSpec.builder(UUID.randomUUID()).defaultDisplay().build();
        assertTrue(s.hasDisplay());
        assertEquals(MachineSpec.DEFAULT_DISPLAY.width(), s.display().width());
        assertEquals(MachineSpec.DEFAULT_DISPLAY.height(), s.display().height());
    }

    @Test
    @DisplayName("empty nvmeDrives list round-trips unchanged")
    void emptyNvmeRoundTrips() {
        MachineSpec s = new MachineSpec(
                UUID.randomUUID(), 64, 1, "rv64", null, null, null,
                false, false, false, java.util.Collections.emptyList(), "cmdline",
                MachineSpec.BootromMode.FIRMWARE_ELSE_DEMO);
        assertNotNull(s.nvmeDrives());
        assertTrue(s.nvmeDrives().isEmpty());
    }

    @Test
    @DisplayName("Builder.kernel() flows through to the record; hasKernel() reflects it")
    void builderKernelChain() {
        MachineSpec.KernelSpec k = new MachineSpec.KernelSpec("Image", "console=tty0 earlycon=sbi");
        MachineSpec s = MachineSpec.builder(UUID.randomUUID()).kernel(k).build();
        assertTrue(s.hasKernel());
        assertNotNull(s.kernel());
        assertEquals("Image", s.kernel().origin());
        assertEquals("console=tty0 earlycon=sbi", s.kernel().cmdline());
    }

    @Test
    @DisplayName("KernelSpec with null cmdline is allowed (backend treats as no-append)")
    void kernelSpecNullCmdline() {
        MachineSpec s = MachineSpec.builder(UUID.randomUUID())
                .kernel(new MachineSpec.KernelSpec("Image", null))
                .build();
        assertTrue(s.hasKernel());
        assertNull(s.kernel().cmdline());
    }

    @Test
    @DisplayName("Builder.bootromMode() flows through to the record")
    void bootromModeBuilder() {
        MachineSpec demo = MachineSpec.builder(UUID.randomUUID())
                .bootromMode(MachineSpec.BootromMode.DEMO_ONLY)
                .build();
        assertEquals(MachineSpec.BootromMode.DEMO_ONLY, demo.bootromMode());

        MachineSpec none = MachineSpec.builder(UUID.randomUUID())
                .bootromMode(MachineSpec.BootromMode.NONE)
                .build();
        assertEquals(MachineSpec.BootromMode.NONE, none.bootromMode());
    }

    @Test
    @DisplayName("MachineSpec rejects a null bootromMode — never ambiguous")
    void rejectsNullBootromMode() {
        assertThrows(NullPointerException.class,
                () -> new MachineSpec(UUID.randomUUID(), 64, 1, "rv64", null, null, null,
                        false, false, false, null, "cmdline", null));
    }

    @Test
    @DisplayName("FirmwareSpec 3-arg constructor leaves firmwareId null (back-compat)")
    void firmwareSpecBackcompat() {
        // Legacy call sites (tests, NBT power-user paths) still use the
        // 3-arg form. It must keep working unchanged, with firmwareId=null
        // so the backend routes through the direct-origin code path.
        MachineSpec.FirmwareSpec fw = new MachineSpec.FirmwareSpec(
                UUID.randomUUID(), 8, "fw_jump.bin");
        assertEquals("fw_jump.bin", fw.origin());
        assertNull(fw.firmwareId());
        assertFalse(fw.hasRegistryRef());
    }

    @Test
    @DisplayName("FirmwareSpec 4-arg constructor retains firmwareId — hasRegistryRef reports it")
    void firmwareSpecRegistryRef() {
        // Production path: parser emits firmwareId=<registry id>, origin=null.
        // The backend's loadRegistryFirmware branch picks this up and resolves
        // via FirmwareRegistry.
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("scev", "linux");
        MachineSpec.FirmwareSpec fw = new MachineSpec.FirmwareSpec(
                UUID.randomUUID(), 8, null, id);
        assertNull(fw.origin());
        assertEquals(id, fw.firmwareId());
        assertTrue(fw.hasRegistryRef());
    }

    @Test
    @DisplayName("FirmwareSpec can carry both origin and firmwareId (firmwareId wins at load time)")
    void firmwareSpecBothFields() {
        // Not produced by the parser, but representable in the type system.
        // Useful for tests that want to explicitly check the "firmwareId
        // beats origin" preference in the backend.
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("scev", "linux");
        MachineSpec.FirmwareSpec fw = new MachineSpec.FirmwareSpec(
                UUID.randomUUID(), 8, "fw_jump.bin", id);
        assertEquals("fw_jump.bin", fw.origin());
        assertEquals(id, fw.firmwareId());
        assertTrue(fw.hasRegistryRef(),
                "hasRegistryRef() tracks firmwareId specifically — origin doesn't override it");
    }

    @Test
    @DisplayName("DiskSpec 3-arg constructor leaves templateId null (back-compat)")
    void diskSpecBackcompat() {
        MachineSpec.DiskSpec d = new MachineSpec.DiskSpec(
                UUID.randomUUID(), 2048, "rootfs.ext2");
        assertEquals("rootfs.ext2", d.origin());
        assertNull(d.templateId());
        assertFalse(d.hasTemplateRef());
    }

    @Test
    @DisplayName("DiskSpec 4-arg constructor retains templateId — hasTemplateRef reports it")
    void diskSpecTemplateRef() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("scev", "buildroot");
        MachineSpec.DiskSpec d = new MachineSpec.DiskSpec(
                UUID.randomUUID(), 2048, null, id);
        assertNull(d.origin());
        assertEquals(id, d.templateId());
        assertTrue(d.hasTemplateRef());
    }

    @Test
    @DisplayName("DiskSpec can carry both origin and templateId (templateId wins at load time)")
    void diskSpecBothFields() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("scev", "buildroot");
        MachineSpec.DiskSpec d = new MachineSpec.DiskSpec(
                UUID.randomUUID(), 2048, "rootfs.ext2", id);
        assertEquals("rootfs.ext2", d.origin());
        assertEquals(id, d.templateId());
        assertTrue(d.hasTemplateRef());
    }
}
