/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import lekkit.scev.machine.storage.BuildrootDiskTemplate;
import lekkit.scev.server.FirmwareAssets;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins down the shipped {@link BuildrootDiskTemplate} — the 16 MiB ext2
 * Linux rootfs that ships with {@code PreloadedNvmeItem}. Asserts the
 * metadata the template declares AND checks that the classpath asset it
 * names actually exists, is the right size, and is a genuine ext2
 * filesystem (superblock magic + volume label).
 *
 * <p>If this test fails, either the template constants drifted from the
 * asset or the asset was accidentally dropped / replaced with a different
 * filesystem. Both are user-visible regressions — the preloaded NVMe
 * would start containing the wrong bytes.
 */
class BuildrootDiskTemplateTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    @Test
    @DisplayName("INSTANCE is a reusable singleton")
    void singleton() {
        assertSame(BuildrootDiskTemplate.INSTANCE, BuildrootDiskTemplate.INSTANCE);
    }

    @Test
    @DisplayName("assetName is linux_rootfs.ext2")
    void assetName() {
        assertEquals("linux_rootfs.ext2", BuildrootDiskTemplate.INSTANCE.assetName());
        assertEquals(BuildrootDiskTemplate.ASSET_NAME,
                BuildrootDiskTemplate.INSTANCE.assetName());
    }

    @Test
    @DisplayName("Declared size is the NvmeItem default (2048 MiB)")
    void size() {
        assertEquals(2048, BuildrootDiskTemplate.INSTANCE.sizeMb(),
                "sizeMb mirrors what a blank NvmeItem declares; bump both together "
                        + "if the preloaded item should be larger than a blank one.");
    }

    @Test
    @DisplayName("displayName is a non-empty component")
    void displayName() {
        assertNotNull(BuildrootDiskTemplate.INSTANCE.displayName());
        assertFalse(BuildrootDiskTemplate.INSTANCE.displayName().getString().isEmpty());
    }

    @Test
    @DisplayName("Classpath asset is bundled in the jar")
    void assetBundled() {
        // Probe via the same mechanism StorageManager uses at boot.
        assertTrue(FirmwareAssets.isBundled(BuildrootDiskTemplate.ASSET_NAME),
                BuildrootDiskTemplate.ASSET_NAME + " is missing from "
                        + "src/main/resources/assets/scev/firmware/ — the preloaded NVMe "
                        + "would fall back to blank and stop carrying the Buildroot rootfs. "
                        + "Rebuild the asset using the Docker recipe documented in "
                        + "docs/FIRMWARE_REGISTRY.md.");
    }

    @Test
    @DisplayName("Asset is a real ext2 filesystem (superblock magic 0xEF53 at offset 1080)")
    void assetIsRealExt2() throws IOException {
        // ext2/3/4 layout:
        //   0      — boot sector (unused by ext2 itself, kept for MBR)
        //   1024   — superblock
        //   1024+56= 1080 — s_magic (0xEF53, little-endian u16)
        //   1024+120=1144 — s_volume_name (16 bytes, NUL-padded)
        //
        // If this check fails, either the asset is corrupt or someone
        // shipped a non-ext2 file by mistake.
        byte[] head;
        try (InputStream in = BuildrootDiskTemplateTest.class.getResourceAsStream(
                FirmwareAssets.CLASSPATH_PREFIX + BuildrootDiskTemplate.ASSET_NAME)) {
            assertNotNull(in, "classpath stream for " + BuildrootDiskTemplate.ASSET_NAME + " is null");
            head = in.readNBytes(1160);
        }
        assertEquals(1160, head.length,
                "Asset is suspiciously short — expected at least 1160 bytes to read superblock");

        // Magic: little-endian 0xEF53 at offset 1080
        int magicLo = head[1080] & 0xFF;
        int magicHi = head[1081] & 0xFF;
        int magic = magicLo | (magicHi << 8);
        assertEquals(0xEF53, magic,
                "ext2 superblock magic missing at offset 1080 — asset isn't an ext2 filesystem. "
                        + "Got 0x" + Integer.toHexString(magic) + ". Rebuild the asset via the "
                        + "genext2fs Docker recipe.");

        // Volume label: "SCEV_ROOTFS\0\0\0\0\0"
        byte[] label = new byte[16];
        System.arraycopy(head, 1144, label, 0, 16);
        int labelLen = 0;
        while (labelLen < 16 && label[labelLen] != 0) labelLen++;
        String labelStr = new String(label, 0, labelLen);
        assertEquals(BuildrootDiskTemplate.FILESYSTEM_LABEL, labelStr,
                "ext2 volume label at offset 1144 doesn't match BuildrootDiskTemplate.FILESYSTEM_LABEL. "
                        + "Either the asset was rebuilt without -L SCEV_ROOTFS or the constant drifted.");
    }

    @Test
    @DisplayName("Asset has the deterministic UUID set at build time")
    void assetHasDeterministicUuid() throws IOException {
        // s_uuid is a 16-byte field at offset 1024+104 = 1128 in the superblock.
        byte[] head;
        try (InputStream in = BuildrootDiskTemplateTest.class.getResourceAsStream(
                FirmwareAssets.CLASSPATH_PREFIX + BuildrootDiskTemplate.ASSET_NAME)) {
            assertNotNull(in);
            head = in.readNBytes(1160);
        }
        byte[] uuid = new byte[16];
        System.arraycopy(head, 1128, uuid, 0, 16);

        // FILESYSTEM_UUID is in canonical dashed form. Parse it back to bytes
        // for comparison against the raw superblock bytes.
        java.util.UUID expected = java.util.UUID.fromString(BuildrootDiskTemplate.FILESYSTEM_UUID);
        long msb = expected.getMostSignificantBits();
        long lsb = expected.getLeastSignificantBits();
        byte[] expectedBytes = new byte[16];
        for (int i = 0; i < 8; i++) expectedBytes[i]     = (byte) (msb >> (56 - 8 * i));
        for (int i = 0; i < 8; i++) expectedBytes[8 + i] = (byte) (lsb >> (56 - 8 * i));

        assertArrayEquals(expectedBytes, uuid,
                "ext2 superblock UUID at offset 1128 doesn't match BuildrootDiskTemplate.FILESYSTEM_UUID. "
                        + "Either the asset was rebuilt without tune2fs -U or the constant drifted.");
    }
}
