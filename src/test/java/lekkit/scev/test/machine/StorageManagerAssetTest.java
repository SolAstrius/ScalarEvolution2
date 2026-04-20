/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import lekkit.scev.server.FirmwareAssets;
import lekkit.scev.server.StorageManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration: {@link StorageManager#initImage} + {@link StorageManager#copyImage}
 * must pull firmware templates from the classpath-bundled jar resources, not
 * just from the on-disk {@code ./scev/assets/} directory.
 *
 * <p>Without this wiring, a freshly installed mod would never be able to
 * materialize the firmware a flash chip references (origin="fw_payload.bin"),
 * and the dark-screen fallback (DemoBootrom) would be all the user ever sees.
 *
 * <p>We deliberately exercise the real classpath + real filesystem so a
 * regression in either layer fails loudly.
 */
class StorageManagerAssetTest {
    private static final String BUNDLED = FirmwareAssets.DEFAULT_FIRMWARE; // "fw_payload.bin"

    @Test
    @DisplayName("initImage with a bundled-firmware origin copies the jar resource to ./scev/images/")
    void initFromBundled() throws IOException {
        UUID u = UUID.randomUUID();
        Path imagePath = Paths.get(StorageManager.imagePath(u));
        try {
            // Clean slate. forgetExtracted leaves the bundled resource alone;
            // only deletes the on-disk extraction under ./scev/assets/.
            FirmwareAssets.forgetExtracted(BUNDLED);
            Files.deleteIfExists(imagePath);

            assertTrue(StorageManager.initImage(u, 8, BUNDLED),
                    "initImage must succeed when origin is a bundled classpath asset");
            assertTrue(Files.isRegularFile(imagePath),
                    "Image file should be present after initImage");

            // Size should equal the bundled firmware, not 8 MiB — copyImage is
            // a raw file copy, not a template-into-sparse fill.
            long expectedSize;
            try (InputStream in = FirmwareAssets.class
                    .getResourceAsStream(FirmwareAssets.CLASSPATH_PREFIX + BUNDLED)) {
                assertNotNull(in);
                expectedSize = in.readAllBytes().length;
            }
            assertEquals(expectedSize, Files.size(imagePath),
                    "Image size should equal the bundled resource size (raw copy).");
        } finally {
            Files.deleteIfExists(imagePath);
            FirmwareAssets.forgetExtracted(BUNDLED);
        }
    }

    @Test
    @DisplayName("initImage is idempotent — second call with same UUID doesn't re-copy")
    void initIdempotent() throws IOException {
        UUID u = UUID.randomUUID();
        Path imagePath = Paths.get(StorageManager.imagePath(u));
        try {
            FirmwareAssets.forgetExtracted(BUNDLED);
            Files.deleteIfExists(imagePath);

            // First call populates the image.
            assertTrue(StorageManager.initImage(u, 8, BUNDLED));
            long firstSize = Files.size(imagePath);
            long firstMtime = Files.getLastModifiedTime(imagePath).toMillis();

            // Second call must be no-op (image already exists).
            assertTrue(StorageManager.initImage(u, 8, BUNDLED));
            assertEquals(firstSize, Files.size(imagePath));
            assertEquals(firstMtime, Files.getLastModifiedTime(imagePath).toMillis(),
                    "initImage must not re-copy an existing image (mtime drifted)");
        } finally {
            Files.deleteIfExists(imagePath);
            FirmwareAssets.forgetExtracted(BUNDLED);
        }
    }

    @Test
    @DisplayName("initImage with unknown origin falls back to a blank sparse image")
    void initUnknownFallbackToBlank() throws IOException {
        UUID u = UUID.randomUUID();
        Path imagePath = Paths.get(StorageManager.imagePath(u));
        try {
            Files.deleteIfExists(imagePath);
            assertTrue(StorageManager.initImage(u, 1, "definitely-not-shipped-xyz.bin"),
                    "initImage must still succeed via createImage fallback");
            assertTrue(Files.isRegularFile(imagePath));
            assertEquals(1 << 20, Files.size(imagePath),
                    "Fallback image should be exactly imageMb * 1 MiB (sparse).");
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    @DisplayName("initImage with null origin creates a blank sparse image of the requested size")
    void initNullOriginBlank() throws IOException {
        UUID u = UUID.randomUUID();
        Path imagePath = Paths.get(StorageManager.imagePath(u));
        try {
            Files.deleteIfExists(imagePath);
            assertTrue(StorageManager.initImage(u, 2, null),
                    "initImage(null origin) must create a blank image of the declared size");
            assertEquals(2 << 20, Files.size(imagePath));
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    @DisplayName("copyImage returns true iff a copy landed (bundled resource)")
    void copyBundledSucceeds() throws IOException {
        UUID u = UUID.randomUUID();
        Path imagePath = Paths.get(StorageManager.imagePath(u));
        try {
            Files.deleteIfExists(imagePath);
            FirmwareAssets.forgetExtracted(BUNDLED);
            assertTrue(StorageManager.copyImage(u, BUNDLED));
            assertTrue(Files.isRegularFile(imagePath));
        } finally {
            Files.deleteIfExists(imagePath);
            FirmwareAssets.forgetExtracted(BUNDLED);
        }
    }

    @Test
    @DisplayName("copyImage returns false when origin is missing")
    void copyMissingFails() throws IOException {
        UUID u = UUID.randomUUID();
        Path imagePath = Paths.get(StorageManager.imagePath(u));
        try {
            Files.deleteIfExists(imagePath);
            assertFalse(StorageManager.copyImage(u, "definitely-missing-xyz-123.bin"));
            assertFalse(Files.isRegularFile(imagePath));
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }
}
