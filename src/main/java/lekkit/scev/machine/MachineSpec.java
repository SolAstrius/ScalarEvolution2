/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Pure value object describing the hardware a machine should be built with.
 *
 * <p>A {@code MachineSpec} is produced by parsing a motherboard's component
 * inventory ({@link MachineSpecParser}) and is consumed by
 * {@link MachineBackend#initialize(MachineSpec)}. Keeping it as a pure record
 * makes the parser testable in isolation (no backend required) and lets us
 * log or persist specs for debugging.
 *
 * <p>Defaults are embedded in the builder so callers can skip the less-common
 * fields.
 */
public record MachineSpec(
        UUID uuid,
        long memMb,
        int smp,
        String isa,
        @Nullable FirmwareSpec firmware,
        @Nullable KernelSpec kernel,
        @Nullable DisplaySpec display,
        boolean hasNic,
        boolean hasGpio,
        boolean hasSound,
        List<DiskSpec> nvmeDrives,
        String cmdline,
        BootromMode bootromMode) {

    /**
     * A firmware/bootrom payload.
     *
     * <p>Three ways to source the firmware, resolved in precedence order by
     * the backend:
     *
     * <ol>
     *   <li><b>Raw bytes</b> — {@link #rawBytes} carries the literal
     *       instruction stream. The backend writes a temp file and feeds it
     *       to {@code rvvm_load_firmware}. This is the player-authored path
     *       (custom programs flashed onto a chip, future in-game Programmer
     *       block). Wins over every other field.</li>
     *   <li><b>Registry id</b> — {@link #firmwareId} points at an entry in
     *       {@link lekkit.scev.machine.firmware.FirmwareRegistry}. The backend
     *       resolves the entry and loads all its payloads (bootrom + optional
     *       kernel) in declaration order. This is how typed flash chips emit
     *       their firmware spec (including the built-in LINUX / BLINKY
     *       shortcut via {@link lekkit.scev.items.FlashFirmware}).</li>
     *   <li><b>Direct asset name</b> (legacy / power user) — {@link #origin}
     *       is the name of a classpath resource under
     *       {@code /assets/scev/firmware/}. The backend loads this as the
     *       bootrom (only). Tests and hand-rolled overrides live here.</li>
     * </ol>
     *
     * <p>If none is set, no firmware is loaded (the backend falls back to
     * {@link BootromMode}).
     *
     * @param uuid        Per-chip persistent UUID — used as the filename for
     *                    the per-chip image under {@code ./scev/images/}.
     * @param sizeMb      Declared flash chip size.
     * @param origin      Direct asset name, or {@code null}.
     * @param firmwareId  Registry id of a {@link lekkit.scev.machine.firmware.ScevFirmware},
     *                    or {@code null}.
     * @param rawBytes    Literal firmware bytes, or {@code null}. When set,
     *                    overrides both other sources.
     */
    public record FirmwareSpec(
            UUID uuid,
            long sizeMb,
            @Nullable String origin,
            @Nullable ResourceLocation firmwareId,
            @Nullable lekkit.scev.items.FirmwareBlob rawBytes) {

        /**
         * Backwards-compatible constructor with direct-asset-name only.
         * Equivalent to {@code new FirmwareSpec(uuid, sizeMb, origin, null, null)}.
         */
        public FirmwareSpec(UUID uuid, long sizeMb, @Nullable String origin) {
            this(uuid, sizeMb, origin, null, null);
        }

        /**
         * Backwards-compatible constructor with direct-asset + registry id.
         * No raw-bytes attached.
         */
        public FirmwareSpec(UUID uuid, long sizeMb, @Nullable String origin,
                            @Nullable ResourceLocation firmwareId) {
            this(uuid, sizeMb, origin, firmwareId, null);
        }

        /** Does this spec reference a registered firmware? */
        public boolean hasRegistryRef() { return firmwareId != null; }

        /** Does this spec carry literal bytes (the custom-flash case)? */
        public boolean hasRawBytes() { return rawBytes != null && !rawBytes.isEmpty(); }
    }

    /**
     * An S-mode kernel payload passed to RVVM's {@code rvvm_load_kernel}.
     *
     * <p>When attached, the backend loads {@code origin} (resolved via
     * {@link lekkit.scev.server.FirmwareAssets}) at {@code mem_base + 0x200000}
     * (RV64) or {@code mem_base + 0x400000} (RV32). It pairs with an
     * OpenSBI-only firmware (e.g. {@code fw_jump.bin}) that passes control
     * from M-mode to S-mode at the kernel entry point.
     *
     * @param origin  Asset name under {@code /assets/scev/firmware/}. The
     *                usual value is {@code "Image"} (raw Linux kernel).
     * @param cmdline Extra kernel cmdline appended to whatever the firmware /
     *                DTB already set up. Example:
     *                {@code "console=tty0 console=ttyS0,115200 earlycon=sbi"}.
     *                Safe to be {@code null} or empty — no-op then.
     */
    public record KernelSpec(String origin, @Nullable String cmdline) {}

    /** A virtual display (framebuffer) attached to the machine. */
    public record DisplaySpec(int width, int height) {}

    /**
     * An NVMe drive.
     *
     * <p>Same two-way naming as {@link FirmwareSpec}: either a registry-
     * referenced template (preferred) or a direct classpath asset name.
     *
     * <ol>
     *   <li><b>Registry id</b> — {@link #templateId} points at an entry in
     *       {@link lekkit.scev.machine.storage.DiskTemplateRegistry}. The
     *       backend resolves the entry, reads the template's
     *       {@code assetName()} + {@code sizeMb()}, and uses those when
     *       seeding the per-UUID disk image on first power-on. This is
     *       how preloaded NVMe items ship with content ("this disk
     *       contains a Buildroot rootfs").</li>
     *   <li><b>Direct origin</b> — {@link #origin} names a classpath
     *       resource directly. Used by blank {@code NvmeItem}s (no
     *       template) and by tests that want to pin a specific asset.
     *       If the asset is missing, {@code StorageManager.initImage}
     *       falls back to a blank sparse file.</li>
     * </ol>
     *
     * <p>If {@code templateId} is set, the backend prefers it; {@code origin}
     * is ignored. Mirrors the {@link FirmwareSpec} semantics.
     *
     * @param uuid       Per-disk persistent UUID.
     * @param sizeMb     Declared disk size.
     * @param origin     Direct asset name, or {@code null}.
     * @param templateId Registry id of a {@link lekkit.scev.machine.storage.ScevDiskTemplate},
     *                   or {@code null}.
     */
    public record DiskSpec(
            UUID uuid,
            long sizeMb,
            @Nullable String origin,
            @Nullable ResourceLocation templateId) {

        /**
         * Backwards-compatible constructor without a template id. Equivalent
         * to {@code new DiskSpec(uuid, sizeMb, origin, null)}.
         */
        public DiskSpec(UUID uuid, long sizeMb, @Nullable String origin) {
            this(uuid, sizeMb, origin, null);
        }

        /** Does this spec reference a registered disk template? */
        public boolean hasTemplateRef() { return templateId != null; }
    }

    /**
     * What code runs at CPU reset. Three distinct scenarios:
     *
     * <ul>
     *   <li>{@link #FIRMWARE_ELSE_DEMO} — <b>production default.</b> Load the
     *       firmware blob referenced by {@link #firmware()} (typically
     *       {@code fw_payload.bin} from the bundled classpath) via
     *       {@code rvvm_load_firmware}. If no firmware is attached, fall back
     *       to the {@link lekkit.scev.machine.DemoBootrom} so CPU still has
     *       something to execute (avoids the dark-screen regression).</li>
     *   <li>{@link #DEMO_ONLY} — ignore any firmware and always use the
     *       DemoBootrom. Used by tests that assert "CPU ran 4 instructions"
     *       semantics ({@code demo_bootrom_executes} GameTest).</li>
     *   <li>{@link #NONE} — load nothing. CPU will trap on its first fetch
     *       unless something else (MTDFlash? pre-seeded RAM?) provides code.
     *       Escape hatch for advanced configurations.</li>
     * </ul>
     */
    public enum BootromMode {
        FIRMWARE_ELSE_DEMO,
        DEMO_ONLY,
        NONE,
    }

    /** Default display resolution used for cases with a VGA card / tinkerpad. */
    public static final DisplaySpec DEFAULT_DISPLAY = new DisplaySpec(640, 480);

    public MachineSpec {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(isa, "isa");
        Objects.requireNonNull(cmdline, "cmdline");
        Objects.requireNonNull(bootromMode, "bootromMode");
        if (memMb <= 0) throw new IllegalArgumentException("memMb must be positive, got " + memMb);
        if (smp < 1) throw new IllegalArgumentException("smp must be >= 1, got " + smp);
        nvmeDrives = nvmeDrives == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(nvmeDrives));
    }

    public boolean hasDisplay() { return display != null; }
    public boolean hasFirmware() { return firmware != null; }
    public boolean hasKernel() { return kernel != null; }

    /** Start a builder pre-populated with sensible defaults for a minimum viable machine. */
    public static Builder builder(UUID uuid) { return new Builder(uuid); }

    /** Fluent builder for human-readable construction in tests and code paths that set a handful of fields. */
    public static final class Builder {
        private final UUID uuid;
        private long memMb = 64;
        private int smp = 1;
        private String isa = "rv64";
        private @Nullable FirmwareSpec firmware;
        private @Nullable KernelSpec kernel;
        private @Nullable DisplaySpec display;
        private boolean hasNic;
        private boolean hasGpio;
        private boolean hasSound;
        private final List<DiskSpec> nvmeDrives = new ArrayList<>();
        private String cmdline = "root=/dev/nvme0n1 rw";
        private BootromMode bootromMode = BootromMode.FIRMWARE_ELSE_DEMO;

        private Builder(UUID uuid) { this.uuid = uuid; }

        public Builder memMb(long v) { this.memMb = v; return this; }
        public Builder smp(int v) { this.smp = v; return this; }
        public Builder isa(String v) { this.isa = v; return this; }
        public Builder firmware(@Nullable FirmwareSpec v) { this.firmware = v; return this; }
        public Builder kernel(@Nullable KernelSpec v) { this.kernel = v; return this; }
        public Builder display(@Nullable DisplaySpec v) { this.display = v; return this; }
        public Builder defaultDisplay() { this.display = DEFAULT_DISPLAY; return this; }
        public Builder hasNic(boolean v) { this.hasNic = v; return this; }
        public Builder hasGpio(boolean v) { this.hasGpio = v; return this; }
        public Builder hasSound(boolean v) { this.hasSound = v; return this; }
        public Builder nvme(DiskSpec v) { this.nvmeDrives.add(v); return this; }
        public Builder cmdline(String v) { this.cmdline = v; return this; }
        public Builder bootromMode(BootromMode v) { this.bootromMode = v; return this; }

        public MachineSpec build() {
            return new MachineSpec(uuid, memMb, smp, isa, firmware, kernel, display,
                    hasNic, hasGpio, hasSound, nvmeDrives, cmdline, bootromMode);
        }
    }
}
