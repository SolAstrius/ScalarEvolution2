/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

import java.util.UUID
import lekkit.scev.items.FirmwareBlob
import net.minecraft.resources.ResourceLocation

/**
 * Pure value object describing the hardware a machine should be built with.
 *
 * Produced by [MachineSpecParser] from a motherboard's component inventory
 * and consumed by [MachineBackend.initialize]. Pure data so the parser is
 * testable in isolation (no backend required) and specs can be logged or
 * persisted for debugging.
 *
 * Defaults live in [Builder] so callers can skip the less-common fields.
 */
data class MachineSpec(
    @get:JvmName("uuid")        val uuid: UUID,
    @get:JvmName("memMb")       val memMb: Long,
    @get:JvmName("smp")         val smp: Int,
    @get:JvmName("isa")         val isa: String,
    @get:JvmName("firmware")    val firmware: FirmwareSpec? = null,
    @get:JvmName("kernel")      val kernel: KernelSpec? = null,
    @get:JvmName("display")     val display: DisplaySpec? = null,
    @get:JvmName("hasNic")      val hasNic: Boolean = false,
    @get:JvmName("hasGpio")     val hasGpio: Boolean = false,
    @get:JvmName("hasSound")    val hasSound: Boolean = false,
    @get:JvmName("nvmeDrives")  val nvmeDrives: List<DiskSpec> = emptyList(),
    @get:JvmName("cmdline")     val cmdline: String,
    @get:JvmName("bootromMode") val bootromMode: BootromMode = BootromMode.FIRMWARE_ELSE_DEMO,
) {
    init {
        require(memMb > 0) { "memMb must be positive, got $memMb" }
        require(smp >= 1) { "smp must be >= 1, got $smp" }
    }

    fun hasDisplay(): Boolean = display != null
    fun hasFirmware(): Boolean = firmware != null
    fun hasKernel(): Boolean = kernel != null

    /**
     * A firmware/bootrom payload. Three precedence-ordered sources:
     *
     *   1. **Raw bytes** ([rawBytes]) — literal instruction stream. Backend
     *      writes a temp file and feeds it to `rvvm_load_firmware`. The
     *      player-authored path; wins over every other field.
     *   2. **Registry id** ([firmwareId]) — points at an entry in
     *      [lekkit.scev.machine.firmware.FirmwareRegistry]; backend resolves
     *      the entry and loads its payloads (bootrom + optional kernel) in
     *      declaration order. How typed flash chips emit their firmware spec.
     *   3. **Direct asset name** ([origin], legacy) — classpath resource
     *      under `/assets/scev/firmware/`. Loaded as the bootrom only. For
     *      tests + hand-rolled overrides.
     *
     * If none is set, no firmware is loaded (backend falls back to [BootromMode]).
     *
     * @param uuid       Per-chip persistent UUID — filename for the per-chip image.
     * @param sizeMb     Declared flash chip size.
     * @param origin     Direct asset name, or null.
     * @param firmwareId Registry id, or null.
     * @param rawBytes   Literal firmware bytes, or null. Overrides both other sources.
     */
    data class FirmwareSpec @JvmOverloads constructor(
        @get:JvmName("uuid")       val uuid: UUID,
        @get:JvmName("sizeMb")     val sizeMb: Long,
        @get:JvmName("origin")     val origin: String? = null,
        @get:JvmName("firmwareId") val firmwareId: ResourceLocation? = null,
        @get:JvmName("rawBytes")   val rawBytes: FirmwareBlob? = null,
    ) {
        fun hasRegistryRef(): Boolean = firmwareId != null
        fun hasRawBytes(): Boolean = rawBytes != null && !rawBytes.isEmpty()
    }

    /**
     * S-mode kernel payload passed to RVVM's `rvvm_load_kernel`.
     *
     * When attached, backend loads [origin] (resolved via [lekkit.scev.server.FirmwareAssets])
     * at `mem_base + 0x200000` (RV64) or `mem_base + 0x400000` (RV32).
     * Pairs with an OpenSBI-only firmware that hands control from M-mode to
     * S-mode at the kernel entry point.
     */
    data class KernelSpec(
        @get:JvmName("origin")  val origin: String,
        @get:JvmName("cmdline") val cmdline: String? = null,
    )

    /** A virtual display (framebuffer) attached to the machine. */
    data class DisplaySpec(
        @get:JvmName("width")  val width: Int,
        @get:JvmName("height") val height: Int,
    )

    /**
     * NVMe drive. Same two-way naming as [FirmwareSpec]: registry-referenced
     * template (preferred) or direct classpath asset.
     *
     *   - **Registry id** ([templateId]) → [lekkit.scev.machine.storage.DiskTemplateRegistry]
     *     entry; backend reads `assetName` + `sizeMb` from the template and
     *     uses those when seeding the per-UUID image on first power-on.
     *   - **Direct origin** ([origin]) → classpath resource directly. Used
     *     by blank `NvmeItem`s and tests pinning a specific asset. If
     *     missing, `StorageManager.initImage` falls back to a blank sparse
     *     file.
     *
     * If [templateId] is set, the backend prefers it; [origin] is ignored.
     */
    data class DiskSpec @JvmOverloads constructor(
        @get:JvmName("uuid")       val uuid: UUID,
        @get:JvmName("sizeMb")     val sizeMb: Long,
        @get:JvmName("origin")     val origin: String? = null,
        @get:JvmName("templateId") val templateId: ResourceLocation? = null,
    ) {
        fun hasTemplateRef(): Boolean = templateId != null
    }

    /**
     * What code runs at CPU reset.
     *
     *   - [FIRMWARE_ELSE_DEMO] — production default. Load the firmware blob
     *     from [firmware] via `rvvm_load_firmware`. If no firmware is
     *     attached, fall back to the [DemoBootrom] so CPU still has
     *     something to execute.
     *   - [DEMO_ONLY] — ignore firmware, always use DemoBootrom. Tests that
     *     assert "CPU ran 4 instructions" semantics.
     *   - [NONE] — load nothing. CPU traps on first fetch unless something
     *     else (MTDFlash? pre-seeded RAM?) provides code. Escape hatch.
     */
    enum class BootromMode { FIRMWARE_ELSE_DEMO, DEMO_ONLY, NONE }

    /**
     * Fluent builder, kept for Java-side callers. Kotlin callers can also
     * just construct the data class with named args.
     */
    class Builder internal constructor(private val uuid: UUID) {
        private var memMb: Long = 64
        private var smp: Int = 1
        private var isa: String = "rv64"
        private var firmware: FirmwareSpec? = null
        private var kernel: KernelSpec? = null
        private var display: DisplaySpec? = null
        private var hasNic: Boolean = false
        private var hasGpio: Boolean = false
        private var hasSound: Boolean = false
        private val nvmeDrives = mutableListOf<DiskSpec>()
        /**
         * Default cmdline is empty. Pre-abstraction value was
         * `"root=/dev/nvme0n1 rw"`, benign only because Linux ignored
         * `root=` when an initramfs was the rootfs. With the
         * [lekkit.scev.machine.storage.ScevDiskTemplate.hasRootFilesystem] ×
         * [lekkit.scev.machine.firmware.ScevFirmware.wantsNvmeRoot]
         * abstraction, `root=` is now injected by [MachineSpecParser] only
         * when both sides opt in. Direct callers (tests, power-user NBT)
         * set whatever cmdline they want via [cmdline].
         */
        private var cmdline: String = ""
        private var bootromMode: BootromMode = BootromMode.FIRMWARE_ELSE_DEMO

        fun memMb(v: Long): Builder       = apply { memMb = v }
        fun smp(v: Int): Builder          = apply { smp = v }
        fun isa(v: String): Builder       = apply { isa = v }
        fun firmware(v: FirmwareSpec?): Builder = apply { firmware = v }
        fun kernel(v: KernelSpec?): Builder     = apply { kernel = v }
        fun display(v: DisplaySpec?): Builder   = apply { display = v }
        fun defaultDisplay(): Builder           = apply { display = DEFAULT_DISPLAY }
        fun hasNic(v: Boolean): Builder         = apply { hasNic = v }
        fun hasGpio(v: Boolean): Builder        = apply { hasGpio = v }
        fun hasSound(v: Boolean): Builder       = apply { hasSound = v }
        fun nvme(v: DiskSpec): Builder          = apply { nvmeDrives += v }
        fun cmdline(v: String): Builder         = apply { cmdline = v }
        fun bootromMode(v: BootromMode): Builder = apply { bootromMode = v }

        fun build(): MachineSpec = MachineSpec(
            uuid, memMb, smp, isa, firmware, kernel, display,
            hasNic, hasGpio, hasSound, nvmeDrives.toList(), cmdline, bootromMode
        )
    }

    companion object {
        /** Default display resolution for cases with a VGA card / tinkerpad. */
        @JvmField val DEFAULT_DISPLAY = DisplaySpec(640, 480)

        /** Start a builder pre-populated with sensible defaults for a minimum viable machine. */
        @JvmStatic fun builder(uuid: UUID): Builder = Builder(uuid)
    }
}
