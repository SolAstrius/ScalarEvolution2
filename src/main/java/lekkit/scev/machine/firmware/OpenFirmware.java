/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.firmware;

import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * OpenSBI + U-Boot ({@code fw_payload.bin}). Boots to a U-Boot shell on
 * UART with a 3 s autoboot countdown. Useful for manual boot commands
 * (disk scanning, TFTP, NVMe enumeration) — the "power-user firmware".
 *
 * <p>Named after the <a href="https://www.openfirmware.info/">OpenFirmware</a>
 * convention (Sun/Apple's IEEE 1275 bootloader) as a nod to the general
 * concept, not the specific implementation. Flash can carry different
 * "boot personalities" depending on which firmware the chip is programmed
 * with.
 *
 * <p>Not the default; see {@link FirmwareRegistry#LINUX} for the
 * Linux-booting default. This firmware becomes the default if the
 * player explicitly binds their flash chip to
 * {@link FirmwareRegistry#OPEN_FIRMWARE}.
 */
public final class OpenFirmware implements ScevFirmware {
    public static final OpenFirmware INSTANCE = new OpenFirmware();

    /** Classpath asset name for the combined OpenSBI + U-Boot payload. */
    public static final String BOOTROM_ASSET = "fw_payload.bin";

    private static final List<Payload> PAYLOADS =
            List.of(new Payload(Payload.Kind.BOOTROM, BOOTROM_ASSET));

    private OpenFirmware() {}

    @Override
    public List<Payload> payloads() { return PAYLOADS; }

    @Override
    public Component displayName() { return Component.literal("OpenSBI + U-Boot"); }
}
