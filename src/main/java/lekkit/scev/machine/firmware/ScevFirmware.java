/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A named manifest describing what code runs at CPU reset.
 *
 * <p>A firmware is the unit of "boot personality" for a machine: a small bundle
 * of one or more classpath-resource payloads that, together, turn a freshly
 * reset CPU into a running OS. Payloads map to RVVM's separate
 * {@code loadBootrom} + {@code loadKernel} APIs via the {@link Payload} list.
 *
 * <h2>Why a registry?</h2>
 *
 * <p>Before this abstraction, {@link lekkit.scev.machine.MachineSpecParser}
 * detected a flash chip and hard-coded the decision to load {@code fw_jump.bin}
 * as the bootrom AND {@code Image} as the kernel. That coupled three concerns
 * — firmware choice, kernel choice, RAM floor — into the single "is a flash
 * chip present" check. With {@code ScevFirmware}:
 *
 * <ul>
 *   <li>The flash chip references a firmware by registry id (default
 *       {@link FirmwareRegistry#LINUX}, which reproduces the pre-registry
 *       behavior: OpenSBI + Linux kernel at 256 MiB RAM floor).</li>
 *   <li>Other firmwares (OpenSBI only, OpenSBI+U-Boot, future Plan9 / NetBSD
 *       ports) can be registered without touching the parser.</li>
 *   <li>Mods can add their own firmwares at {@code FMLCommonSetupEvent}.</li>
 * </ul>
 *
 * <h2>How payloads map to RVVM</h2>
 *
 * <p>Each {@link Payload} declares one classpath asset to load into guest RAM
 * via one of RVVM's two load APIs:
 *
 * <ul>
 *   <li>{@link Payload.Kind#BOOTROM} → {@code rvvm_load_firmware(path)} —
 *       copies the file to {@code mem_base} (0x80000000 by RVVM default).
 *       This is where M-mode firmware lands.</li>
 *   <li>{@link Payload.Kind#KERNEL} → {@code rvvm_load_kernel(path)} —
 *       copies the file to {@code mem_base + 0x200000} (RV64) or
 *       {@code mem_base + 0x400000} (RV32). This is where an S-mode kernel
 *       lands so an OpenSBI-only bootrom can {@code mret} straight into it.</li>
 * </ul>
 *
 * <p>Payloads are applied in declaration order. A firmware that is only a
 * bootrom declares a single BOOTROM payload; Linux-capable firmwares declare
 * both (bootrom first, kernel second).
 *
 * <h2>RAM floor</h2>
 *
 * <p>Different firmwares have wildly different minimum-RAM needs. A trivial
 * 256-byte OpenSBI shim boots on 16 MiB; our Buildroot 2026.02 Linux kernel
 * + 26 MiB embedded initramfs panics on {@code pty_init} below ~128 MiB. The
 * firmware itself declares its floor via {@link #minRamMb()}; the parser
 * clamps up automatically.
 *
 * <h2>Cmdline</h2>
 *
 * <p>If the firmware hands off to a kernel, it may want to control the
 * kernel cmdline (e.g. to pick the right console). {@link #cmdlineAppend()}
 * contributes to {@link lekkit.scev.machine.MachineSpec#cmdline()} at
 * boot — the backend appends this string after the base machine cmdline.
 *
 * <h2>Determinism</h2>
 *
 * <p>Implementations must be <b>stateless and deterministic</b>: the same
 * firmware instance must return the same payloads / RAM floor / cmdline
 * forever, because {@link FirmwareRegistry} caches instances by id and
 * multiple machines may share one. Use static final constants, not mutable
 * fields.
 */
public interface ScevFirmware {

    /**
     * The ordered list of payloads that make up this firmware. Applied in
     * order by {@link lekkit.scev.machine.rvvm.RvvmMachineBackend}; later
     * payloads overwrite earlier ones at overlapping addresses.
     *
     * <p>Must be non-null and non-empty. The first payload is typically
     * {@link Payload.Kind#BOOTROM} (M-mode firmware at the reset vector).
     */
    List<Payload> payloads();

    /**
     * Minimum RAM (in MiB) for this firmware to boot sensibly. The parser
     * clamps {@link lekkit.scev.machine.MachineSpec#memMb()} up to at least
     * this value when the firmware is attached.
     *
     * <p>Defaults to 64 — enough for tiny firmwares and bootrom-only flows.
     * A Linux-capable firmware (kernel + initramfs) should declare a much
     * higher floor (e.g. 256). See {@link LinuxFirmware}.
     */
    default long minRamMb() { return 64; }

    /**
     * Extra kernel cmdline fragment to append to the machine's cmdline at
     * boot. {@code null} or empty means "no contribution" — the machine
     * keeps its default. Firmwares that load a kernel payload typically
     * contribute console routing here (e.g.
     * {@code "console=tty0 console=ttyS0,115200 earlycon=sbi"}).
     */
    default @Nullable String cmdlineAppend() { return null; }

    /** Human-readable name for tooltips, logs, and error messages. */
    Component displayName();

    /**
     * A single payload: one classpath asset loaded into guest RAM at a
     * specific logical address via one of RVVM's two load APIs.
     *
     * @param kind  Which RVVM load API to use (bootrom vs kernel).
     * @param asset Name of the classpath resource under
     *              {@code /assets/scev/firmware/}. Resolved at boot via
     *              {@link lekkit.scev.server.FirmwareAssets#ensureExtracted}.
     */
    record Payload(Kind kind, String asset) {
        public Payload {
            if (kind == null) throw new IllegalArgumentException("kind must not be null");
            if (asset == null || asset.isEmpty()) {
                throw new IllegalArgumentException("asset must be a non-empty classpath name");
            }
        }

        /** Which RVVM load API handles this payload. */
        public enum Kind {
            /**
             * M-mode firmware at {@code mem_base}. Loaded via
             * {@code rvvm_load_firmware}. CPU reset PC lands here.
             */
            BOOTROM,
            /**
             * S-mode kernel payload at {@code mem_base + 0x200000} (RV64) or
             * {@code mem_base + 0x400000} (RV32). Loaded via
             * {@code rvvm_load_kernel}. Paired with a BOOTROM that hands off
             * to this address (OpenSBI's {@code fw_jump.bin} does this by
             * default).
             */
            KERNEL,
        }
    }
}
