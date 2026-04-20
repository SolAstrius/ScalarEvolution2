/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import lekkit.scev.machine.MachineSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers every combination of device-attach rules in the backend. Each test
 * asserts the spec flag translates to a concrete device (or null) on the
 * backend exactly once.
 *
 * <p>The fake backend doesn't actually run RVVM but it implements the same
 * contract: if spec says {@code hasNic=true}, the backend accepts it and
 * (conceptually) attaches a NIC. What matters for our tests is that the
 * spec's flags flow through unchanged.
 */
class DeviceAttachmentTest {

    private static MachineSpec specWith(java.util.function.Consumer<MachineSpec.Builder> mod) {
        MachineSpec.Builder b = MachineSpec.builder(UUID.randomUUID()).memMb(64);
        mod.accept(b);
        return b.build();
    }

    @Test
    @DisplayName("No display: framebuffer() is null")
    void noDisplay() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> {}));
        assertNull(b.framebuffer());
    }

    @Test
    @DisplayName("defaultDisplay: framebuffer() is 640x480")
    void defaultDisplay() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(MachineSpec.Builder::defaultDisplay));
        assertNotNull(b.framebuffer());
        assertEquals(640, b.framebuffer().width());
        assertEquals(480, b.framebuffer().height());
    }

    @Test
    @DisplayName("custom display resolution")
    void customDisplay() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x.display(new MachineSpec.DisplaySpec(1024, 768))));
        assertEquals(1024, b.framebuffer().width());
        assertEquals(768, b.framebuffer().height());
    }

    @Test
    @DisplayName("no GPIO: gpio() is null")
    void noGpio() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> {}));
        assertNull(b.gpio());
    }

    @Test
    @DisplayName("hasGpio: gpio() is present and returns masked pins")
    void gpioPresentAndMasks() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x.hasGpio(true)));
        assertNotNull(b.gpio());
        b.gpioRaw().readValue = 0x7F; // 7 bits, but readPins masks to 6
        assertEquals(0x3F, b.gpio().readPins());
    }

    @Test
    @DisplayName("Firmware spec is retrievable from the backend's spec")
    void firmwareSpecRetained() {
        UUID fwUuid = UUID.randomUUID();
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x.firmware(new MachineSpec.FirmwareSpec(fwUuid, 8, "fw.bin"))));
        assertTrue(b.spec().hasFirmware());
        assertEquals(fwUuid, b.spec().firmware().uuid());
        assertEquals(8, b.spec().firmware().sizeMb());
        assertEquals("fw.bin", b.spec().firmware().origin());
    }

    @Test
    @DisplayName("Kernel spec flows through and hasKernel() reflects it")
    void kernelSpecRetained() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x.kernel(
                new MachineSpec.KernelSpec("Image", "console=tty0"))));
        assertTrue(b.spec().hasKernel());
        assertEquals("Image", b.spec().kernel().origin());
        assertEquals("console=tty0", b.spec().kernel().cmdline());
    }

    @Test
    @DisplayName("No kernel: hasKernel() is false, kernel() returns null")
    void noKernel() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> {}));
        assertFalse(b.spec().hasKernel());
        assertNull(b.spec().kernel());
    }

    @Test
    @DisplayName("Firmware + kernel set together (Linux boot path)")
    void firmwareAndKernelTogether() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x
                .firmware(new MachineSpec.FirmwareSpec(UUID.randomUUID(), 8, "fw_jump.bin"))
                .kernel(new MachineSpec.KernelSpec("Image", "console=tty0"))));
        assertTrue(b.spec().hasFirmware());
        assertTrue(b.spec().hasKernel());
        assertEquals("fw_jump.bin", b.spec().firmware().origin());
        assertEquals("Image", b.spec().kernel().origin());
    }

    @Test
    @DisplayName("NVMe drives: multiple disk specs retained in order")
    void nvmeDrivesRetained() {
        UUID a = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x
                .nvme(new MachineSpec.DiskSpec(a, 1024, "a.ext2"))
                .nvme(new MachineSpec.DiskSpec(c, 2048, "c.ext2"))));
        assertEquals(2, b.spec().nvmeDrives().size());
        assertEquals(a, b.spec().nvmeDrives().get(0).uuid());
        assertEquals(c, b.spec().nvmeDrives().get(1).uuid());
        assertEquals(2048, b.spec().nvmeDrives().get(1).sizeMb());
    }

    @Test
    @DisplayName("hasNic flag is retained; no NIC device is exposed (attachment only)")
    void nicFlagRetained() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x.hasNic(true)));
        assertTrue(b.spec().hasNic());
        // (There's no getNic() accessor — the backend either has networking or not.)
    }

    @Test
    @DisplayName("All flags simultaneously: GPIO + NIC + firmware + display + 2 NVMe")
    void everythingAtOnce() {
        FakeMachineBackend b = new FakeMachineBackend();
        MachineSpec spec = specWith(x -> x
                .defaultDisplay()
                .hasGpio(true)
                .hasNic(true)
                .firmware(new MachineSpec.FirmwareSpec(UUID.randomUUID(), 8, "fw"))
                .nvme(new MachineSpec.DiskSpec(UUID.randomUUID(), 1024, "a"))
                .nvme(new MachineSpec.DiskSpec(UUID.randomUUID(), 2048, "b")));
        b.initialize(spec);
        assertNotNull(b.framebuffer());
        assertNotNull(b.gpio());
        assertTrue(b.spec().hasNic());
        assertTrue(b.spec().hasFirmware());
        assertEquals(2, b.spec().nvmeDrives().size());
    }

    @Test
    @DisplayName("Memory/SMP/ISA flow through unchanged")
    void specPassthrough() {
        FakeMachineBackend b = new FakeMachineBackend();
        b.initialize(specWith(x -> x.memMb(512).smp(4).isa("rv32imac")));
        assertEquals(512, b.spec().memMb());
        assertEquals(4, b.spec().smp());
        assertEquals("rv32imac", b.spec().isa());
    }
}
