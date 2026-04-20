/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Bootrom-only firmware: just OpenSBI ({@code fw_jump.bin}). Hands off to
 * S-mode at {@code 0x80200000}; without something at that address (a kernel
 * payload, a bootable disk, etc) it traps on an illegal instruction.
 *
 * <p>Not used by default. Intended as a building block for future flows
 * that supply the kernel separately — e.g. a
 * {@link lekkit.scev.machine.MachineSpec.KernelSpec} with a user-authored
 * kernel, or (eventually) U-Boot booting from an NVMe partition.
 *
 * <p>Low RAM floor (64 MiB) because OpenSBI itself is tiny and without a
 * kernel there's no Buildroot initramfs to unpack.
 */
public final class OpenSbiFirmware implements ScevFirmware {
    public static final OpenSbiFirmware INSTANCE = new OpenSbiFirmware();

    /** Classpath asset name for the OpenSBI M-mode firmware. */
    public static final String BOOTROM_ASSET = "fw_jump.bin";

    private static final List<Payload> PAYLOADS =
            List.of(new Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET));

    private OpenSbiFirmware() {}

    @Override
    public List<Payload> payloads() { return PAYLOADS; }

    @Override
    public Component displayName() { return Component.literal("OpenSBI"); }
}
