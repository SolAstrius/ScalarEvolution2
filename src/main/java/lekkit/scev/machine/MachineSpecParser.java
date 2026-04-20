/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

import java.util.UUID;
import lekkit.scev.items.CpuItem;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.GpioItem;
import lekkit.scev.items.MotherboardInventory;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.items.NvmeItem;
import lekkit.scev.items.PciCardItem;
import lekkit.scev.items.PreloadedNvmeItem;
import lekkit.scev.items.RamItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import lekkit.scev.machine.firmware.ScevFirmware;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Pure function that derives a {@link MachineSpec} from a motherboard
 * {@link ItemStack} (with its 14-slot inventory) plus a few per-case overrides.
 *
 * <p>Kept out of the block entity so we can unit-test the mapping:
 * <ul>
 *   <li>motherboard level -> enabled slot mask</li>
 *   <li>RAM slots -> total memMb with a minimum floor so the CPU can actually boot</li>
 *   <li>CPU level -> hart count (smp)</li>
 *   <li>PCI card kinds -> hasNic / hasGpio / hasVga</li>
 *   <li>flash slot item -> FirmwareSpec (registry-referenced)</li>
 *   <li>NVMe slots -> DiskSpec list</li>
 * </ul>
 *
 * <p>"Hard floors" (minimum memory, fallback ISA) live here so tests can pin
 * them down. The parser returns {@code null} when the motherboard is missing
 * — higher layers decide whether that should abort the boot or fall through.
 */
public final class MachineSpecParser {
    /**
     * Minimum memory the machine gets even with zero RAM sticks and no
     * firmware installed.
     *
     * <p>This is the "diagnostic" floor: enough RAM to let the bundled
     * {@link DemoBootrom} execute (needs bytes at the reset vector) and
     * enough so the framebuffer DMA buffer and a few device pages don't
     * fail to allocate. When a firmware <i>is</i> installed, the actual
     * floor becomes {@code max(MIN_RAM_MB, firmware.minRamMb())}.
     */
    public static final long MIN_RAM_MB = 64;

    /**
     * Minimum memory for the Linux-boot path.
     *
     * <p>Historical constant — {@link lekkit.scev.machine.firmware.LinuxFirmware#MIN_RAM_MB}
     * is the source of truth now, but this constant stays for tests and
     * external code that want to reference "the Linux floor" without
     * pulling in a specific firmware class.
     *
     * <p>Our shipped kernel is Linux 6.18 with a ~26 MiB Buildroot initramfs
     * embedded via {@code CONFIG_INITRAMFS_SOURCE}. Below ~128 MiB total RAM,
     * {@code pty_init} runs out of free pages trying to register
     * {@code /dev/pts} sysfs nodes and the kernel panics with {@code "Out of
     * memory and no killable processes"}. We pick 256 MiB (double the
     * observed-working 128 MiB) for comfortable headroom — extra virtual RAM
     * is essentially free under RVVM's mmap-backed allocation on the host.
     */
    public static final long MIN_LINUX_RAM_MB = 256;

    /** Default ISA string if no other signal supplies one. */
    public static final String DEFAULT_ISA = "rv64";

    /** Default kernel cmdline. */
    public static final String DEFAULT_CMDLINE = "root=/dev/nvme0n1 rw";

    /**
     * Default M-mode firmware registry id for a freshly installed flash chip.
     *
     * <p>Points at {@link FirmwareRegistry#LINUX} — OpenSBI + Linux kernel.
     * The payload list handled by {@code RvvmMachineBackend} declares both
     * the {@code fw_jump.bin} BOOTROM and the {@code Image} KERNEL; the
     * firmware also contributes the fbcon-routing cmdline and the 256 MiB
     * RAM floor. If a future feature (per-chip NBT override) attaches a
     * different firmware id to the ItemStack, the parser will use that
     * instead.
     */
    public static final ResourceLocation DEFAULT_FIRMWARE_ID = FirmwareRegistry.LINUX;

    /**
     * Legacy asset name kept for tests + docs that reference "the default
     * firmware file". The production code path no longer uses this — the
     * parser emits a registry reference via {@link #DEFAULT_FIRMWARE_ID},
     * and {@link LinuxFirmware} names the asset internally. Value is still
     * {@code "fw_jump.bin"} because that's what the Linux firmware's BOOTROM resolves to.
     *
     * @deprecated Read {@code LinuxFirmware.BOOTROM_ASSET} for the same value
     *     with clearer provenance. Kept for compatibility with existing
     *     tests and the {@code real_firmware_boots_from_flash_chip} GameTest
     *     whose byte-level assertion still needs the concrete filename.
     */
    @Deprecated
    public static final String DEFAULT_FIRMWARE = "fw_jump.bin";

    /**
     * Legacy kernel asset name kept for tests + power-user KernelSpec flows.
     * Production LINUX firmware loads this internally via its KERNEL payload.
     *
     * @deprecated Read {@code LinuxFirmware.KERNEL_ASSET}. Direct
     *     {@link MachineSpec.KernelSpec} use is supported but no longer the
     *     default boot path for a flash chip.
     */
    @Deprecated
    public static final String DEFAULT_KERNEL = "Image";

    /**
     * Legacy default kernel cmdline — equal to
     * {@link lekkit.scev.machine.firmware.LinuxFirmware#CMDLINE}. Kept for
     * tests that inspect the cmdline string directly.
     *
     * @deprecated Read {@code LinuxFirmware.CMDLINE}.
     */
    @Deprecated
    public static final String DEFAULT_KERNEL_CMDLINE = "console=tty0 console=ttyS0,115200 earlycon=sbi";

    private MachineSpecParser() {}

    /**
     * Build a {@link MachineSpec} from a motherboard stack.
     *
     * @param machineUuid   Stable machine UUID (persisted on the case BE).
     * @param mbStack       The motherboard ItemStack (may be empty/non-motherboard -> returns null).
     * @param forceDisplay  If true, always include a default display even without a VGA card
     *                      (laptops / tinkerpads ship with a built-in screen).
     * @return A spec, or {@code null} if there's no motherboard to boot from.
     */
    public static @Nullable MachineSpec fromMotherboard(UUID machineUuid, ItemStack mbStack, boolean forceDisplay) {
        if (mbStack == null || mbStack.isEmpty() || !(mbStack.getItem() instanceof MotherboardItem mb)) {
            return null;
        }

        MotherboardInventory mbInv = new MotherboardInventory(() -> mbStack);
        NonNullList<ItemStack> comps = mbInv.snapshot();

        // -- Flash detection + firmware resolution ---------------------------
        // A flash chip references a firmware by registry id. Today every
        // FlashItem uses the default id (LINUX); a future NBT-tagged chip
        // will carry its own reference. The firmware decides the RAM floor,
        // cmdline, and which RVVM load APIs get called with which assets.
        //
        // We resolve the firmware entry upfront so the memory clamp below
        // can ask the firmware for its minimum.
        ItemStack flashStack = comps.get(MotherboardItem.SLOT_FLASH);
        boolean hasFlashChip = flashStack.getItem() instanceof FlashItem;
        ResourceLocation firmwareId = hasFlashChip ? DEFAULT_FIRMWARE_ID : null;
        ScevFirmware firmware = FirmwareRegistry.get(firmwareId);
        long firmwareFloor = firmware != null ? firmware.minRamMb() : 0L;

        // -- Memory -----------------------------------------------------------
        long totalMb = 0;
        for (int i = MotherboardItem.SLOT_RAM_START; i <= MotherboardItem.SLOT_RAM_END; i++) {
            if (!mb.isSlotEnabled(i)) continue;
            ItemStack ram = comps.get(i);
            if (ram.getItem() instanceof RamItem ri) totalMb += ri.getMegabytes();
        }
        // Two-floor clamp:
        //   (1) Every machine gets at least MIN_RAM_MB so the VM can
        //       construct itself and the DemoBootrom has bytes to execute.
        //   (2) If a firmware is attached, bump up to the firmware's own
        //       minimum — e.g. LINUX needs 256 MiB for Linux kernel +
        //       initramfs. Firmwares that want no special floor declare
        //       minRamMb = 64 (or inherit the default from ScevFirmware).
        long floor = Math.max(MIN_RAM_MB, firmwareFloor);
        if (totalMb < floor) totalMb = floor;

        // -- CPU --------------------------------------------------------------
        int smp = 1;
        ItemStack cpuStack = comps.get(MotherboardItem.SLOT_CPU);
        if (cpuStack.getItem() instanceof CpuItem ci) smp = Math.max(1, ci.getLevel());

        MachineSpec.Builder builder = MachineSpec.builder(machineUuid)
                .memMb(totalMb)
                .smp(smp)
                .isa(DEFAULT_ISA)
                .cmdline(DEFAULT_CMDLINE);

        // -- Firmware flash (slot 1) -----------------------------------------
        // The parser emits a registry-referenced FirmwareSpec; the backend
        // resolves the id and loads the firmware's payloads (bootrom and
        // optional kernel) in order. This replaces the prior direct
        // "fw_jump.bin + Image" pairing with something mods can extend.
        //
        // Note: we intentionally do NOT also emit a KernelSpec. The LINUX
        // firmware loads its kernel via its KERNEL payload — the parser
        // used to emit a parallel KernelSpec as a coupling hack, and tests
        // relied on it. See ScevGameTests#linux_kernel_boots_and_draws_fbcon
        // for the new assertion shape.
        if (hasFlashChip && flashStack.getItem() instanceof FlashItem flashItem) {
            UUID flashUuid = flashItem.ensureUuid(flashStack);
            builder.firmware(new MachineSpec.FirmwareSpec(
                    flashUuid, flashItem.getSizeMb(),
                    /* origin */ null,
                    /* firmwareId */ firmwareId));
        }

        // -- NVMe drives (slots 6..7) ----------------------------------------
        // Every NvmeItem emits a DiskSpec. If the item is a
        // PreloadedNvmeItem, attach the template registry id so the backend
        // seeds the per-UUID image from DiskTemplateRegistry. Otherwise the
        // spec carries only the raw origin and StorageManager's classpath
        // lookup / blank-fallback kicks in as before.
        for (int i = MotherboardItem.SLOT_NVME_START; i <= MotherboardItem.SLOT_NVME_END; i++) {
            if (!mb.isSlotEnabled(i)) continue;
            ItemStack s = comps.get(i);
            if (!(s.getItem() instanceof NvmeItem)) continue;
            if (!(s.getItem() instanceof StorageItem storageItem)) continue;
            UUID diskUuid = storageItem.ensureUuid(s);
            ResourceLocation templateId = null;
            if (s.getItem() instanceof PreloadedNvmeItem preloaded) {
                templateId = preloaded.getDefaultTemplateId();
            }
            builder.nvme(new MachineSpec.DiskSpec(
                    diskUuid, storageItem.getSizeMb(),
                    /* origin */ storageItem.getOrigin(),
                    /* templateId */ templateId));
        }

        // -- PCI cards (slots 8..13) -----------------------------------------
        boolean hasVga = false;
        for (int i = MotherboardItem.SLOT_PCI_START; i <= MotherboardItem.SLOT_PCI_END; i++) {
            if (!mb.isSlotEnabled(i)) continue;
            ItemStack s = comps.get(i);
            if (s.getItem() instanceof GpioItem) {
                builder.hasGpio(true);
            } else if (s.getItem() instanceof PciCardItem card) {
                switch (card.getKind()) {
                    case NET -> builder.hasNic(true);
                    case VGA -> hasVga = true;
                    case GPIO -> builder.hasGpio(true);
                    case SOUND -> { /* not yet implemented */ }
                }
            }
        }

        if (hasVga || forceDisplay) {
            builder.defaultDisplay();
        }

        return builder.build();
    }
}
