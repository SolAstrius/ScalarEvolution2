/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import lekkit.scev.items.CpuItem;
import lekkit.scev.items.FirmwareBlob;
import lekkit.scev.items.FlashFirmware;
import lekkit.scev.items.FlashItem;
import lekkit.scev.items.GpioItem;
import lekkit.scev.items.MotherboardInventory;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.items.NvmeItem;
import lekkit.scev.items.PciCardItem;
import lekkit.scev.items.PreloadedNvmeItem;
import lekkit.scev.items.RamItem;
import lekkit.scev.items.SocItem;
import lekkit.scev.items.StorageItem;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import lekkit.scev.machine.firmware.ScevFirmware;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.machine.storage.ScevDiskTemplate;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

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
    private static final Logger LOG = LogUtils.getLogger();

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

    /**
     * Default kernel cmdline before firmware- and disk-driven fragments are
     * appended.
     *
     * <p>Empty — the pre-abstraction value was {@code "root=/dev/nvme0n1 rw"},
     * which only booted correctly because Linux ignores {@code root=} when
     * the initramfs is the rootfs. With the
     * {@link lekkit.scev.machine.storage.ScevDiskTemplate#hasRootFilesystem()}
     * × {@link ScevFirmware#wantsNvmeRoot()} abstraction, the {@code root=}
     * fragment is injected only when a rootfs-declaring disk is actually
     * attached to a firmware that wants to honor it. See
     * {@link #buildRootCmdlineFragment} for the assembly rule.
     *
     * <p>Firmware-contributed fragments (e.g. {@link lekkit.scev.machine.firmware.LinuxFirmware}'s
     * console routing) are still appended on top, via
     * {@link lekkit.scev.machine.rvvm.RvvmMachineBackend}'s
     * {@code loadRegistryFirmware} path.
     */
    public static final String DEFAULT_CMDLINE = "";

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
        ItemStack flashStack = comps.get(MotherboardItem.SLOT_FLASH);
        boolean hasFlashChip = flashStack.getItem() instanceof FlashItem;
        FlashFirmwareResolution resolved = hasFlashChip
                ? resolveFlashFirmware(flashStack)
                : FlashFirmwareResolution.NONE;
        FirmwareBlob rawBytes = resolved.rawBytes();
        ResourceLocation firmwareId = resolved.firmwareId();
        long firmwareFloor = resolved.floor();

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
        if (cpuStack.getItem() instanceof CpuItem ci) smp = ci.getHartCount();

        // Cmdline is assembled in two passes: start with the base default,
        // then (after the NVMe walk) append `root=<dev> rw rootwait` iff
        // firmware and disk metadata both opt into it. Final cmdline is
        // set via builder.cmdline(...) at the end.
        String cmdline = DEFAULT_CMDLINE;
        // Device path of the first rootfs-declaring NVMe found below. Null
        // means no rootfs disk is installed, so no `root=` injection.
        String rootfsDevice = null;

        MachineSpec.Builder builder = MachineSpec.builder(machineUuid)
                .memMb(totalMb)
                .smp(smp)
                .isa(DEFAULT_ISA);

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
            // Persist the UUID back into the motherboard's stored stack
            // (flashStack is a snapshot-copy, same lifecycle as NVMe below).
            mbInv.setItem(MotherboardItem.SLOT_FLASH, flashStack);
            builder.firmware(new MachineSpec.FirmwareSpec(
                    flashUuid, flashItem.getSizeMb(),
                    /* origin */ null,
                    /* firmwareId */ firmwareId,
                    /* rawBytes */ rawBytes));
        }

        // -- NVMe drives (slots 6..7) ----------------------------------------
        // Every NvmeItem emits a DiskSpec. If the item is a
        // PreloadedNvmeItem, attach the template registry id so the backend
        // seeds the per-UUID image from DiskTemplateRegistry. Otherwise the
        // spec carries only the raw origin and StorageManager's classpath
        // lookup / blank-fallback kicks in as before.
        //
        // The loop also remembers the first rootfs-declaring disk's
        // rootDevice() so the cmdline-assembly step below can inject
        // `root=<dev>` when the installed firmware opts in via
        // ScevFirmware.wantsNvmeRoot(). First-match-wins on slot order —
        // multi-disk rootfs selection would need an explicit "boot drive"
        // marker and isn't modelled yet.
        for (int i = MotherboardItem.SLOT_NVME_START; i <= MotherboardItem.SLOT_NVME_END; i++) {
            if (!mb.isSlotEnabled(i)) continue;
            ItemStack s = comps.get(i);
            if (!(s.getItem() instanceof NvmeItem)) continue;
            if (!(s.getItem() instanceof StorageItem storageItem)) continue;
            UUID diskUuid = storageItem.ensureUuid(s);
            // ensureUuid mutated the stack `s` in place, but `s` is a copy
            // from mbInv.snapshot() — the motherboard's data component
            // still holds the unmutated stack. Write back so the UUID is
            // persisted on the item (and surfaces in its tooltip).
            mbInv.setItem(i, s);
            ResourceLocation templateId = null;
            if (s.getItem() instanceof PreloadedNvmeItem preloaded) {
                // Stack-aware: picks up DISK_TEMPLATE component overrides
                // (each creative-tab variant sets this to a different id).
                templateId = preloaded.getTemplateId(s);
            }
            builder.nvme(new MachineSpec.DiskSpec(
                    diskUuid, storageItem.getSizeMb(),
                    /* origin */ storageItem.getOrigin(),
                    /* templateId */ templateId));

            // Resolve the template and note the first rootfs-declaring
            // disk for the cmdline injection. Skip if the template id is
            // null (blank NvmeItem — no metadata) or unresolved (mod
            // removed between saves). Second+ rootfs disks log and lose.
            if (templateId != null) {
                ScevDiskTemplate template = DiskTemplateRegistry.get(templateId);
                if (template != null && template.hasRootFilesystem()) {
                    if (rootfsDevice == null) {
                        rootfsDevice = template.rootDevice();
                    } else {
                        LOG.debug("Multiple rootfs-declaring disks installed for machine {}; "
                                + "keeping {} (first slot wins), ignoring {} in slot {}",
                                machineUuid, rootfsDevice, template.rootDevice(), i);
                    }
                }
            }
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
                    case SOUND -> builder.hasSound(true);
                }
            }
        }

        if (hasVga || forceDisplay) {
            builder.defaultDisplay();
        }

        // -- Cmdline assembly ------------------------------------------------
        // The `root=<dev> rw rootwait` fragment is injected only when BOTH
        // sides of the abstraction opt in:
        //
        //   * A NVMe is installed whose template declared
        //     hasRootFilesystem() = true (recorded above as
        //     rootfsDevice).
        //   * The resolved flash firmware declared wantsNvmeRoot() = true.
        //
        // Either missing -> no injection; the kernel falls back to whatever
        // boot path the firmware itself chose (embedded initramfs for
        // LinuxFirmware, disk-scanning extlinux for OpenFirmware).
        //
        // The resulting cmdline is the base (DEFAULT_CMDLINE, currently
        // empty) plus the optional root= fragment. Firmware-contributed
        // console routing (LinuxFirmware's `console=tty0 ...`) is appended
        // later in RvvmMachineBackend via firmware.cmdlineAppend().
        if (rootfsDevice != null && firmwareId != null) {
            ScevFirmware fw = FirmwareRegistry.get(firmwareId);
            if (fw != null && fw.wantsNvmeRoot()) {
                String rootFragment = "root=" + rootfsDevice + " rw rootwait";
                cmdline = cmdline.isEmpty() ? rootFragment : cmdline + " " + rootFragment;
            }
        }
        builder.cmdline(cmdline);

        return builder.build();
    }

    /**
     * Build a {@link MachineSpec} from an MCU board's two installed items.
     *
     * <p>Analogous to {@link #fromMotherboard} but collapses the entire
     * "motherboard + components" model into a pair: a {@link SocItem} that
     * carries the CPU/RAM/ISA specification, and an optional flash chip
     * carrying the firmware. The MCU has implicit GPIO (the SoC exposes
     * redstone pins directly) and no PCI/NVMe/display — a focused, tiny
     * machine for bare-metal firmware.
     *
     * @param machineUuid  Stable machine UUID (persisted on the BE).
     * @param socStack     SoC item — must be a {@link SocItem}, else null returned.
     * @param flashStack   Flash item or empty. No flash = no firmware (CPU
     *                     traps on first fetch; only interesting for tests).
     * @return A spec, or {@code null} if no SoC is installed.
     */
    public static @Nullable MachineSpec fromMcu(UUID machineUuid, ItemStack socStack, ItemStack flashStack) {
        if (socStack == null || socStack.isEmpty() || !(socStack.getItem() instanceof SocItem soc)) {
            return null;
        }

        // -- Firmware resolution (same precedence as motherboard path) -------
        boolean hasFlashChip = flashStack != null && flashStack.getItem() instanceof FlashItem;
        FlashFirmwareResolution resolved = hasFlashChip
                ? resolveFlashFirmware(flashStack)
                : FlashFirmwareResolution.NONE;

        // -- Memory clamp ----------------------------------------------------
        // SoC declares on-die RAM in KiB (4 / 256 / 32768). Convert to MiB,
        // then clamp up to both the absolute minimum and the firmware's
        // declared floor. This is what lets a Tier-1 SoC (4 KiB on-die) boot
        // Blinky (needs 1 MiB) without the player hand-waving memory.
        long socMb = Math.max(1, (long) soc.getEmbeddedRamKib() / 1024);
        long floor = Math.max(MIN_RAM_MB, resolved.floor());
        long memMb = Math.max(socMb, floor);

        MachineSpec.Builder builder = MachineSpec.builder(machineUuid)
                .memMb(memMb)
                .smp(soc.getHartCount())
                .isa(soc.getIsa())
                .cmdline(DEFAULT_CMDLINE)
                // MCU has implicit GPIO — no separate PCI card slot, the
                // SoC itself bonds the redstone pins.
                .hasGpio(true);

        if (hasFlashChip && flashStack.getItem() instanceof FlashItem flashItem) {
            UUID flashUuid = flashItem.ensureUuid(flashStack);
            builder.firmware(new MachineSpec.FirmwareSpec(
                    flashUuid, flashItem.getSizeMb(),
                    /* origin */ null,
                    /* firmwareId */ resolved.firmwareId(),
                    /* rawBytes */ resolved.rawBytes()));
        }

        return builder.build();
    }

    /**
     * Firmware precedence chain for flash chips, shared by the motherboard
     * and MCU paths:
     *
     * <ol>
     *   <li>{@code FIRMWARE_BYTES} — raw player-authored payload. Bypasses
     *       registry. Returned via {@link FlashFirmwareResolution#rawBytes}.</li>
     *   <li>{@code FIRMWARE_ID_OVERRIDE} — arbitrary {@link ResourceLocation}
     *       for third-party firmwares not in the typed {@link FlashFirmware}
     *       enum.</li>
     *   <li>{@code FIRMWARE_KIND} — typed built-in enum. {@code BLANK}
     *       resolves to a null id (explicit no-firmware).</li>
     *   <li>No components set → {@link #DEFAULT_FIRMWARE_ID} (LINUX), so
     *       pre-component worlds keep booting unchanged.</li>
     * </ol>
     *
     * <p>The returned {@code floor} is the firmware's {@code minRamMb} if we
     * landed on a registry entry, else 0 — callers clamp memory against it.
     */
    private static FlashFirmwareResolution resolveFlashFirmware(ItemStack flashStack) {
        FirmwareBlob stackedBytes = flashStack.get(ScevDataComponents.FIRMWARE_BYTES.get());
        if (stackedBytes != null && !stackedBytes.isEmpty()) {
            // rawBytes wins unconditionally — the player-authored path.
            return new FlashFirmwareResolution(stackedBytes, null, 0L);
        }

        ResourceLocation id;
        ResourceLocation override = flashStack.get(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get());
        if (override != null) {
            id = override;
        } else {
            FlashFirmware kind = flashStack.get(ScevDataComponents.FIRMWARE_KIND.get());
            if (kind != null) {
                id = kind.id();                 // null when kind == BLANK
            } else {
                id = DEFAULT_FIRMWARE_ID;       // legacy world: LINUX
            }
        }
        ScevFirmware fw = FirmwareRegistry.get(id);
        long floor = fw != null ? fw.minRamMb() : 0L;
        return new FlashFirmwareResolution(null, id, floor);
    }

    /**
     * Outcome of {@link #resolveFlashFirmware}. Exactly one of
     * {@code rawBytes} and {@code firmwareId} is set (or both null for
     * the "no flash chip at all" shortcut).
     */
    private record FlashFirmwareResolution(
            @Nullable FirmwareBlob rawBytes,
            @Nullable ResourceLocation firmwareId,
            long floor) {
        static final FlashFirmwareResolution NONE = new FlashFirmwareResolution(null, null, 0L);
    }
}
