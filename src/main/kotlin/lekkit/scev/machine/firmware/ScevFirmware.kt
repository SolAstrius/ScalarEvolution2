/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware

import net.minecraft.network.chat.Component

/**
 * A named manifest describing what code runs at CPU reset.
 *
 * A firmware is the unit of "boot personality" for a machine: a small
 * bundle of one or more classpath-resource payloads that, together,
 * turn a freshly reset CPU into a running OS. Payloads map to RVVM's
 * separate `loadBootrom` + `loadKernel` APIs via the [Payload] list.
 *
 * Implementations must be **stateless and deterministic**: the same
 * firmware instance must return the same payloads / RAM floor /
 * cmdline forever, because [FirmwareRegistry] caches instances by id
 * and multiple machines may share one. Use object/static, not mutable
 * fields.
 */
interface ScevFirmware {

    /**
     * The ordered list of payloads that make up this firmware. Applied
     * in order by [lekkit.scev.machine.rvvm.RvvmMachineBackend]; later
     * payloads overwrite earlier ones at overlapping addresses.
     *
     * Must be non-null and non-empty. The first payload is typically
     * [Payload.Kind.BOOTROM] (M-mode firmware at the reset vector).
     */
    fun payloads(): List<Payload>

    /**
     * Minimum RAM (in MiB) for this firmware to boot sensibly. The
     * parser clamps [lekkit.scev.machine.MachineSpec.memMb] up to at
     * least this value when the firmware is attached.
     *
     * Defaults to 64 — enough for tiny firmwares and bootrom-only
     * flows. A Linux-capable firmware (kernel + initramfs) should
     * declare a much higher floor (e.g. 256). See [LinuxFirmware].
     */
    fun minRamMb(): Long = 64

    /**
     * Extra kernel cmdline fragment to append to the machine's cmdline
     * at boot. `null` or empty means "no contribution" — the machine
     * keeps its default. Firmwares that load a kernel payload
     * typically contribute console routing here (e.g.
     * `"console=tty0 console=ttyS0,115200 earlycon=sbi"`).
     */
    fun cmdlineAppend(): String? = null

    /**
     * Does this firmware want [lekkit.scev.machine.MachineSpecParser]
     * to inject `root=<dev> rw rootwait` into the kernel cmdline when
     * a rootfs-declaring disk is also installed?
     *
     * Firmwares that load their own kernel directly ([LinuxFirmware])
     * typically return `true`: the kernel reads the cmdline and (via
     * its init / initramfs pivot script) mounts the declared root
     * device as `/`. Firmwares that delegate boot to the disk's own
     * bootloader ([OpenFirmware]/U-Boot) return `false` — the
     * bootloader assembles the cmdline itself from `extlinux.conf` on
     * disk.
     *
     * Default is `false`. Firmwares without an attached rootfs
     * (bare-metal blobs, bootrom-only flows) have no reason to opt in.
     */
    fun wantsNvmeRoot(): Boolean = false

    /** Human-readable name for tooltips, logs, and error messages. */
    fun displayName(): Component

    /**
     * A single payload: one classpath asset loaded into guest RAM at a
     * specific logical address via one of RVVM's two load APIs.
     *
     * @param kind  Which RVVM load API to use (bootrom vs kernel).
     * @param asset Name of the classpath resource under
     *              `/assets/scev/firmware/`. Resolved at boot via
     *              [lekkit.scev.server.FirmwareAssets.ensureExtracted].
     */
    data class Payload(
        @get:JvmName("kind") val kind: Kind,
        @get:JvmName("asset") val asset: String,
    ) {
        init {
            require(asset.isNotEmpty()) { "asset must be a non-empty classpath name" }
        }

        /** Which RVVM load API handles this payload. */
        enum class Kind {
            /**
             * M-mode firmware at `mem_base`. Loaded via
             * `rvvm_load_firmware`. CPU reset PC lands here.
             */
            BOOTROM,
            /**
             * S-mode kernel payload at `mem_base + 0x200000` (RV64)
             * or `mem_base + 0x400000` (RV32). Loaded via
             * `rvvm_load_kernel`. Paired with a BOOTROM that hands
             * off to this address (OpenSBI's `fw_jump.bin` does this
             * by default).
             */
            KERNEL,
        }
    }
}
