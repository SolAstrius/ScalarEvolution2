/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Default "flash chip installed" firmware: OpenSBI 1.6 ({@code fw_jump.bin})
 * + a real RV64 Linux 6.18 kernel ({@code Image}).
 *
 * <p>The two-payload list reproduces exactly what the pre-registry
 * {@code MachineSpecParser} hard-coded:
 *
 * <ol>
 *   <li>{@code fw_jump.bin} as BOOTROM at 0x80000000 — OpenSBI initializes
 *       SBI / PMP / traps, then {@code mret}s to S-mode at 0x80200000.</li>
 *   <li>{@code Image} as KERNEL at 0x80200000 — Linux takes over, detects
 *       RVVM's {@code simple-framebuffer} DTB node, brings up fbcon, and
 *       renders kernel log + {@code buildroot login:} onto the workstation
 *       screen.</li>
 * </ol>
 *
 * <p>The 256 MiB {@link #minRamMb} floor is why the parser bumps memory on
 * flash-chip install: below ~128 MiB the shipped kernel + 26 MiB Buildroot
 * initramfs OOM on {@code pty_init}. 256 is double the observed-working
 * minimum for comfortable headroom (see docs/FIRMWARE.md).
 *
 * <p>The {@link #cmdlineAppend} routes the kernel console to both the
 * framebuffer ({@code tty0}) and the serial port ({@code ttyS0}) with
 * early SBI-console output before {@code simple-framebuffer} is ready.
 *
 * <p>Stateless singleton — access via {@link #INSTANCE}. Do not instantiate.
 */
public final class LinuxFirmware implements ScevFirmware {
    public static final LinuxFirmware INSTANCE = new LinuxFirmware();

    /** Classpath asset name for the OpenSBI M-mode firmware. */
    public static final String BOOTROM_ASSET = "fw_jump.bin";

    /** Classpath asset name for the RV64 Linux kernel Image. */
    public static final String KERNEL_ASSET = "Image";

    /**
     * Kernel cmdline appended on boot.
     *
     * <p><b>Console ordering matters.</b> Linux uses the <i>last</i>
     * {@code console=} parameter as {@code /dev/console}, which is where
     * BusyBox's default {@code getty -L console ...} inittab entry spawns
     * the login prompt. We list {@code ttyS0} first (so kernel messages
     * still flow to the serial port for debugging) and {@code tty0}
     * <i>last</i> so the login prompt lands on the framebuffer — the actual
     * Tinkerpad screen the player is looking at.
     *
     * <p>History: the earlier ordering ({@code tty0} first, {@code ttyS0}
     * last) made {@code /dev/console} = ttyS0. Kernel boot logs were visible
     * on the screen because fbcon mirrors all {@code console=} entries, but
     * {@code buildroot login:} only showed up on the serial side — the
     * player saw boot stop partway through userspace init with no prompt.
     * Verified against BusyBox 1.37 inittab default
     * ({@code ::respawn:/sbin/getty -L console 0 vt100}).
     *
     * <p>{@code earlycon=sbi} enables SBI console output before
     * {@code simple-framebuffer} has attached, so the very first kernel
     * messages still appear on the screen rather than disappearing into a
     * black frame.
     */
    public static final String CMDLINE = "console=ttyS0,115200 console=tty0 earlycon=sbi";

    /**
     * Minimum RAM for the shipped Buildroot 2026.02 kernel + 26 MiB
     * initramfs. Below ~128 MiB the kernel panics on {@code pty_init};
     * 256 is double the observed-working minimum.
     */
    public static final long MIN_RAM_MB = 256;

    private static final List<Payload> PAYLOADS = List.of(
            new Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET),
            new Payload(Payload.Kind.KERNEL, KERNEL_ASSET));

    private LinuxFirmware() {}

    @Override
    public List<Payload> payloads() { return PAYLOADS; }

    @Override
    public long minRamMb() { return MIN_RAM_MB; }

    @Override
    public String cmdlineAppend() { return CMDLINE; }

    @Override
    public Component displayName() { return Component.literal("Linux"); }
}
