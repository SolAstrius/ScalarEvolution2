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
 * ext2 filesystem-format invariants for the shipped Buildroot rootfs. The
 * superblock magic / label / UUID checks would catch a corrupted asset or
 * a wrong-format file in the jar — both are user-visible regressions that
 * the generic {@code DiskTemplateInvariantTest} can't detect.
 *
 * Generic shape (assetName non-empty, sizeMb > 0, displayName non-empty,
 * asset bundled on classpath) lives in the invariant test.
 */
class BuildrootDiskTemplateTest {

    @BeforeAll
    static void bootstrap() { Bootstrap.bootStrap(); }

    /**
     * Reads the first 1160 bytes of the classpath asset — enough to cover
     * the ext2 boot sector, superblock, and volume-label field.
     */
    private static byte[] superblockHead() throws IOException {
        try (InputStream in = BuildrootDiskTemplateTest.class.getResourceAsStream(
                FirmwareAssets.CLASSPATH_PREFIX + BuildrootDiskTemplate.ASSET_NAME)) {
            assertNotNull(in, "classpath stream for " + BuildrootDiskTemplate.ASSET_NAME);
            byte[] head = in.readNBytes(1160);
            assertEquals(1160, head.length, "asset is shorter than one ext2 superblock");
            return head;
        }
    }

    @Test
    @DisplayName("Superblock magic 0xEF53 at offset 1080 (asset is actually ext2)")
    void superblockMagic() throws IOException {
        // ext2/3/4 layout: 1024-byte boot sector, then superblock at 1024.
        // s_magic is a little-endian u16 at superblock offset 56 → byte
        // offset 1080. If this check fails, the shipped file is either
        // corrupt or not an ext-family filesystem at all.
        byte[] head = superblockHead();
        int magic = (head[1080] & 0xFF) | ((head[1081] & 0xFF) << 8);
        assertEquals(0xEF53, magic,
                "ext2 magic missing at offset 1080; got 0x" + Integer.toHexString(magic));
    }

    @Test
    @DisplayName("Volume label at offset 1144 matches FILESYSTEM_LABEL")
    void volumeLabel() throws IOException {
        // s_volume_name is 16 bytes, NUL-padded, at superblock offset 120.
        byte[] head = superblockHead();
        int len = 0;
        while (len < 16 && head[1144 + len] != 0) len++;
        String label = new String(head, 1144, len);
        assertEquals(BuildrootDiskTemplate.FILESYSTEM_LABEL, label,
                "ext2 volume label drifted from constant — rebuild with -L " +
                        BuildrootDiskTemplate.FILESYSTEM_LABEL);
    }

    @Test
    @DisplayName("Superblock UUID at offset 1128 matches FILESYSTEM_UUID")
    void superblockUuid() throws IOException {
        // s_uuid is 16 bytes at superblock offset 104 → byte offset 1128.
        byte[] head = superblockHead();
        java.util.UUID expected = java.util.UUID.fromString(BuildrootDiskTemplate.FILESYSTEM_UUID);
        long msb = expected.getMostSignificantBits();
        long lsb = expected.getLeastSignificantBits();
        byte[] expectedBytes = new byte[16];
        for (int i = 0; i < 8; i++) expectedBytes[i]     = (byte) (msb >> (56 - 8 * i));
        for (int i = 0; i < 8; i++) expectedBytes[8 + i] = (byte) (lsb >> (56 - 8 * i));
        byte[] actualUuid = new byte[16];
        System.arraycopy(head, 1128, actualUuid, 0, 16);
        assertArrayEquals(expectedBytes, actualUuid,
                "ext2 UUID drifted from constant — rebuild with tune2fs -U " +
                        BuildrootDiskTemplate.FILESYSTEM_UUID);
    }
}
