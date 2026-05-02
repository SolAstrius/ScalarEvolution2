/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

import com.mojang.logging.LogUtils
import java.util.UUID
import lekkit.scev.items.CpuItem
import lekkit.scev.items.FirmwareBlob
import lekkit.scev.items.FlashFirmware
import lekkit.scev.items.FlashItem
import lekkit.scev.items.GpioItem
import lekkit.scev.items.MotherboardInventory
import lekkit.scev.items.MotherboardItem
import lekkit.scev.items.NvmeItem
import lekkit.scev.items.PciCardItem
import lekkit.scev.items.PreloadedNvmeItem
import lekkit.scev.items.RamItem
import lekkit.scev.items.SocItem
import lekkit.scev.items.StorageItem
import lekkit.scev.machine.firmware.FirmwareRegistry
import lekkit.scev.machine.storage.DiskTemplateRegistry
import lekkit.scev.main.ScevDataComponents
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * Pure function that derives a [MachineSpec] from a motherboard [ItemStack]
 * (with its 14-slot inventory) plus a few per-case overrides.
 *
 * Kept out of the block entity so we can unit-test the mapping:
 *   - motherboard level → enabled slot mask
 *   - RAM slots → total memMb with a minimum floor
 *   - CPU level → hart count (smp)
 *   - PCI card kinds → hasNic / hasGpio / hasVga
 *   - flash slot item → FirmwareSpec (registry-referenced)
 *   - NVMe slots → DiskSpec list
 *
 * "Hard floors" (minimum memory, fallback ISA) live here so tests can pin
 * them down. Returns `null` when the motherboard is missing — higher layers
 * decide whether that should abort the boot or fall through.
 */
object MachineSpecParser {
    private val LOG = LogUtils.getLogger()

    /**
     * Minimum memory the machine gets even with zero RAM sticks and no
     * firmware installed. The diagnostic floor: enough RAM for the bundled
     * [DemoBootrom] to execute and for the framebuffer DMA + a few device
     * pages to allocate. When firmware is installed, the actual floor
     * becomes `max(MIN_RAM_MB, firmware.minRamMb())`.
     */
    @JvmField val MIN_RAM_MB: Long = 64

    /**
     * Minimum memory for the Linux-boot path. Historical constant —
     * [lekkit.scev.machine.firmware.LinuxFirmware.MIN_RAM_MB] is the source
     * of truth now, but this stays for tests/external code that want to
     * reference "the Linux floor" without pulling in a specific firmware.
     *
     * Linux 6.18 with the ~26 MiB Buildroot initramfs panics under ~128 MiB
     * (`pty_init` runs out of pages registering `/dev/pts`). 256 MiB is
     * double the observed-working 128 MiB — extra virtual RAM is essentially
     * free under RVVM's mmap-backed allocation.
     */
    @JvmField val MIN_LINUX_RAM_MB: Long = 256

    /** Default ISA string if no other signal supplies one. */
    @JvmField val DEFAULT_ISA: String = "rv64"

    /**
     * Default kernel cmdline before firmware- and disk-driven fragments.
     *
     * Empty — the pre-abstraction value `"root=/dev/nvme0n1 rw"` only booted
     * because Linux ignored `root=` when an initramfs was the rootfs. With
     * the [lekkit.scev.machine.storage.ScevDiskTemplate.hasRootFilesystem] ×
     * [lekkit.scev.machine.firmware.ScevFirmware.wantsNvmeRoot] abstraction,
     * `root=` is injected only when a rootfs-declaring disk is attached to
     * a firmware that wants it. Firmware-contributed fragments
     * (LinuxFirmware's console routing, etc.) are appended later in
     * [lekkit.scev.machine.rvvm.RvvmMachineBackend].
     */
    @JvmField val DEFAULT_CMDLINE: String = ""

    /**
     * Default M-mode firmware registry id for a freshly installed flash.
     * Points at [FirmwareRegistry.LINUX] — OpenSBI + Linux kernel.
     */
    @JvmField val DEFAULT_FIRMWARE_ID: ResourceLocation = FirmwareRegistry.LINUX

    /**
     * Legacy asset name kept for tests + docs. The production path now emits
     * a registry reference via [DEFAULT_FIRMWARE_ID]; LinuxFirmware names the
     * asset internally. Value is `"fw_jump.bin"` because that's what the
     * Linux firmware's BOOTROM resolves to.
     */
    @Deprecated("Read LinuxFirmware.BOOTROM_ASSET for the same value with clearer provenance.")
    @JvmField val DEFAULT_FIRMWARE: String = "fw_jump.bin"

    /** Legacy kernel asset name. Production LINUX firmware loads this internally. */
    @Deprecated("Read LinuxFirmware.KERNEL_ASSET. Direct KernelSpec is no longer the default boot path.")
    @JvmField val DEFAULT_KERNEL: String = "Image"

    /** Legacy default kernel cmdline — equal to LinuxFirmware.CMDLINE. */
    @Deprecated("Read LinuxFirmware.CMDLINE.")
    @JvmField val DEFAULT_KERNEL_CMDLINE: String = "console=tty0 console=ttyS0,115200 earlycon=sbi"

    /**
     * Build a [MachineSpec] from a motherboard stack.
     *
     * @param machineUuid   Stable machine UUID (persisted on the case BE).
     * @param mbStack       Motherboard ItemStack — empty/non-motherboard returns null.
     * @param forceDisplay  If true, always include a default display (laptops/tinkerpads).
     * @return spec, or null if no motherboard.
     */
    @JvmStatic fun fromMotherboard(machineUuid: UUID, mbStack: ItemStack?, forceDisplay: Boolean): MachineSpec? {
        if (mbStack == null || mbStack.isEmpty) return null
        val mb = mbStack.item as? MotherboardItem ?: return null

        val mbInv = MotherboardInventory({ mbStack })
        val comps = mbInv.snapshot()

        // -- Flash detection + firmware resolution
        val flashStack = comps[MotherboardItem.SLOT_FLASH]
        val hasFlashChip = flashStack.item is FlashItem
        val resolved = if (hasFlashChip) resolveFlashFirmware(flashStack) else FlashFirmwareResolution.NONE
        val rawBytes = resolved.rawBytes
        val firmwareId = resolved.firmwareId

        // -- Memory --
        var totalMb = 0L
        for (i in MotherboardItem.SLOT_RAM_START..MotherboardItem.SLOT_RAM_END) {
            if (!mb.isSlotEnabled(i)) continue
            val ram = comps[i]
            (ram.item as? RamItem)?.let { totalMb += it.getMegabytes() }
        }
        // Two-floor clamp: every machine gets MIN_RAM_MB minimum (DemoBootrom
        // needs bytes at the reset vector); when firmware is attached, bump
        // up to the firmware's own minimum (LINUX needs 256 MiB).
        val floor = maxOf(MIN_RAM_MB, resolved.floor)
        if (totalMb < floor) totalMb = floor

        // -- CPU --
        val cpuStack = comps[MotherboardItem.SLOT_CPU]
        val smp = (cpuStack.item as? CpuItem)?.hartCount ?: 1

        // Cmdline assembled in two passes: base default first, then `root=…`
        // appended after the NVMe walk iff firmware + disk both opt in.
        var cmdline = DEFAULT_CMDLINE
        var rootfsDevice: String? = null

        val builder = MachineSpec.builder(machineUuid)
            .memMb(totalMb)
            .smp(smp)
            .isa(DEFAULT_ISA)

        // -- Firmware flash (slot 1) --
        // Emits a registry-referenced FirmwareSpec; backend resolves the id
        // and loads the firmware's payloads (bootrom + optional kernel) in
        // order. Replaces the prior direct "fw_jump.bin + Image" pairing.
        // Note: we do NOT also emit a KernelSpec — LINUX firmware loads its
        // kernel via its own KERNEL payload.
        if (hasFlashChip) {
            val flashItem = flashStack.item as FlashItem
            val flashUuid = flashItem.ensureUuid(flashStack)
            // Persist the UUID back into the motherboard's stored stack.
            mbInv.setItem(MotherboardItem.SLOT_FLASH, flashStack)
            builder.firmware(MachineSpec.FirmwareSpec(
                flashUuid, flashItem.getSizeMb(),
                /* origin */ null,
                /* firmwareId */ firmwareId,
                /* rawBytes */ rawBytes,
            ))
        }

        // -- NVMe drives (slots 6..7) --
        // Every NvmeItem emits a DiskSpec. Preloaded variants attach a
        // template registry id so the backend can seed the per-UUID image
        // from DiskTemplateRegistry. Otherwise the spec carries only the
        // raw origin and StorageManager's classpath lookup / blank-fallback
        // kicks in.
        //
        // The loop also remembers the first rootfs-declaring disk so the
        // cmdline-assembly step below can inject `root=…` when the installed
        // firmware opts in via wantsNvmeRoot(). First-match-wins on slot
        // order — multi-disk rootfs selection isn't modelled yet.
        for (i in MotherboardItem.SLOT_NVME_START..MotherboardItem.SLOT_NVME_END) {
            if (!mb.isSlotEnabled(i)) continue
            val s = comps[i]
            if (s.item !is NvmeItem) continue
            val storageItem = s.item as? StorageItem ?: continue
            val diskUuid = storageItem.ensureUuid(s)
            // ensureUuid mutated the stack copy in place; write back so the
            // UUID persists on the item (and surfaces in its tooltip).
            mbInv.setItem(i, s)
            val templateId = (s.item as? PreloadedNvmeItem)?.getTemplateId(s)
            builder.nvme(MachineSpec.DiskSpec(
                diskUuid, storageItem.getSizeMb(),
                /* origin */ storageItem.getOrigin(),
                /* templateId */ templateId,
            ))

            // First rootfs-declaring disk wins.
            if (templateId != null) {
                val template = DiskTemplateRegistry.get(templateId)
                if (template != null && template.hasRootFilesystem()) {
                    if (rootfsDevice == null) {
                        rootfsDevice = template.rootDevice()
                    } else {
                        LOG.debug("Multiple rootfs-declaring disks installed for machine {}; " +
                            "keeping {} (first slot wins), ignoring {} in slot {}",
                            machineUuid, rootfsDevice, template.rootDevice(), i)
                    }
                }
            }
        }

        // -- PCI cards (slots 8..13) --
        var hasVga = false
        for (i in MotherboardItem.SLOT_PCI_START..MotherboardItem.SLOT_PCI_END) {
            if (!mb.isSlotEnabled(i)) continue
            val s = comps[i]
            when {
                s.item is GpioItem -> builder.hasGpio(true)
                s.item is PciCardItem -> when ((s.item as PciCardItem).kind) {
                    PciCardItem.Kind.NET -> builder.hasNic(true)
                    PciCardItem.Kind.VGA -> hasVga = true
                    PciCardItem.Kind.GPIO -> builder.hasGpio(true)
                    PciCardItem.Kind.SOUND -> builder.hasSound(true)
                }
            }
        }

        if (hasVga || forceDisplay) builder.defaultDisplay()

        // -- Cmdline assembly --
        // Inject `root=<dev> rw rootwait` only when BOTH sides opt in:
        //   * a NVMe is installed whose template declares hasRootFilesystem
        //   * the resolved flash firmware declares wantsNvmeRoot
        // Either missing → no injection; the kernel falls back to its own
        // boot path (initramfs for LinuxFirmware, extlinux for OpenFirmware).
        if (rootfsDevice != null && firmwareId != null) {
            val fw = FirmwareRegistry.get(firmwareId)
            if (fw != null && fw.wantsNvmeRoot()) {
                val rootFragment = "root=$rootfsDevice rw rootwait"
                cmdline = if (cmdline.isEmpty()) rootFragment else "$cmdline $rootFragment"
            }
        }
        builder.cmdline(cmdline)

        return builder.build()
    }

    /**
     * Build a [MachineSpec] from an MCU board's two installed items.
     *
     * Analogous to [fromMotherboard] but collapses the entire "motherboard +
     * components" model into a pair: a [SocItem] carrying the CPU/RAM/ISA
     * spec and an optional flash chip carrying firmware. The MCU has
     * implicit GPIO (the SoC exposes redstone pins directly) and no
     * PCI/NVMe/display.
     */
    @JvmStatic fun fromMcu(machineUuid: UUID, socStack: ItemStack?, flashStack: ItemStack?): MachineSpec? {
        if (socStack == null || socStack.isEmpty) return null
        val soc = socStack.item as? SocItem ?: return null

        // -- Firmware resolution (same precedence as motherboard path) --
        val hasFlashChip = flashStack != null && flashStack.item is FlashItem
        val resolved = if (hasFlashChip) resolveFlashFirmware(flashStack!!) else FlashFirmwareResolution.NONE

        // -- Memory clamp --
        // SoC declares on-die RAM in KiB (4 / 256 / 32768). Convert to MiB,
        // then clamp up to both the absolute minimum and the firmware's
        // declared floor. Lets a Tier-1 SoC (4 KiB on-die) boot Blinky
        // (needs 1 MiB) without the player hand-waving memory.
        val socMb = maxOf(1L, soc.embeddedRamKib.toLong() / 1024)
        val floor = maxOf(MIN_RAM_MB, resolved.floor)
        val memMb = maxOf(socMb, floor)

        val builder = MachineSpec.builder(machineUuid)
            .memMb(memMb)
            .smp(soc.hartCount)
            .isa(soc.isa)
            .cmdline(DEFAULT_CMDLINE)
            // MCU has implicit GPIO — no separate PCI card slot.
            .hasGpio(true)

        if (hasFlashChip) {
            val flashItem = flashStack!!.item as FlashItem
            val flashUuid = flashItem.ensureUuid(flashStack)
            builder.firmware(MachineSpec.FirmwareSpec(
                flashUuid, flashItem.getSizeMb(),
                /* origin */ null,
                /* firmwareId */ resolved.firmwareId,
                /* rawBytes */ resolved.rawBytes,
            ))
        }

        return builder.build()
    }

    /**
     * Firmware precedence chain for flash chips, shared by motherboard and
     * MCU paths:
     *
     *   1. `FIRMWARE_BYTES` — raw player-authored payload. Bypasses registry.
     *   2. `FIRMWARE_ID_OVERRIDE` — arbitrary [ResourceLocation] for third-
     *      party firmwares not in the typed [FlashFirmware] enum.
     *   3. `FIRMWARE_KIND` — typed built-in enum. `BLANK` resolves to a null
     *      id (explicit no-firmware).
     *   4. No components set → [DEFAULT_FIRMWARE_ID] (LINUX), so pre-component
     *      worlds keep booting unchanged.
     */
    private fun resolveFlashFirmware(flashStack: ItemStack): FlashFirmwareResolution {
        val stackedBytes = flashStack.get(ScevDataComponents.FIRMWARE_BYTES.get())
        if (stackedBytes != null && !stackedBytes.isEmpty()) {
            // rawBytes wins unconditionally — the player-authored path.
            return FlashFirmwareResolution(stackedBytes, null, 0L)
        }

        // Note: when FIRMWARE_KIND is set, kind.id() is the resolution — even
        // if it returns null (FlashFirmware.BLANK). We must NOT fall through
        // to DEFAULT_FIRMWARE_ID in that case, because the player explicitly
        // set BLANK to mean "no firmware." The elvis chain below would
        // incorrectly do that, so the precedence is split into explicit if's.
        val override = flashStack.get(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get())
        val id: ResourceLocation? = when {
            override != null -> override
            else -> {
                val kind = flashStack.get(ScevDataComponents.FIRMWARE_KIND.get())
                if (kind != null) kind.id() else DEFAULT_FIRMWARE_ID
            }
        }
        val fw = id?.let { FirmwareRegistry.get(it) }
        val floor = fw?.minRamMb() ?: 0L
        return FlashFirmwareResolution(null, id, floor)
    }

    /**
     * Outcome of [resolveFlashFirmware]. Exactly one of [rawBytes] /
     * [firmwareId] is set (or both null for "no flash chip").
     */
    private data class FlashFirmwareResolution(
        val rawBytes: FirmwareBlob?,
        val firmwareId: ResourceLocation?,
        val floor: Long,
    ) {
        companion object {
            val NONE = FlashFirmwareResolution(null, null, 0L)
        }
    }
}
