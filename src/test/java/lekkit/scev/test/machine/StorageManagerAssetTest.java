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

    /**
     * Returns the on-disk allocation of {@code p} in KiB via {@code du -k}.
     * Needed because Java's unix:blocks attribute isn't wired into the JDK
     * attribute view on every platform. Failing to exec du is reported as
     * a JUnit test failure so the sparseness claim can't silently regress
     * to "we can't tell".
     */
    private static long duKb(Path p) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("du", "-k", p.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        byte[] out = proc.getInputStream().readAllBytes();
        try {
            int rc = proc.waitFor();
            assertEquals(0, rc, "du -k exited " + rc + ": " + new String(out));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("du -k interrupted");
        }
        // du prints "<blocks>\t<path>\n"; first whitespace-delimited token is
        // the KiB count on both macOS and GNU du when called with -k.
        String line = new String(out).trim();
        String token = line.split("\\s+", 2)[0];
        return Long.parseLong(token);
    }

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

            // Post-refactor: initImage sparse-extends the file to `imageMb`
            // after the template copy so the per-UUID image always matches
            // the declared capacity (even when the template asset ships
            // smaller). The first `imageMb * 1 MiB` bytes are the template
            // content + sparse zeros; the grown file is 8 MiB on the host.
            assertEquals(8L << 20, Files.size(imagePath),
                    "Image must be sparse-extended to imageMb (8 MiB) so the per-UUID disk "
                            + "advertises its declared capacity regardless of template size. If you "
                            + "changed initImage back to a raw-copy contract, rebuild the "
                            + "BuildrootDiskTemplate expectations too — the /init pivot script "
                            + "calls resize2fs on the assumption the device is the full advertised "
                            + "size.");

            // The template bytes still live at the start of the file —
            // readNBytes reads up to N; compare the first templateSize bytes
            // against the classpath bundle to prove copyImage fidelity.
            long templateSize;
            try (InputStream in = FirmwareAssets.class
                    .getResourceAsStream(FirmwareAssets.CLASSPATH_PREFIX + BUNDLED)) {
                assertNotNull(in);
                templateSize = in.readAllBytes().length;
            }
            byte[] bundled;
            try (InputStream in = FirmwareAssets.class
                    .getResourceAsStream(FirmwareAssets.CLASSPATH_PREFIX + BUNDLED)) {
                bundled = in.readAllBytes();
            }
            byte[] onDiskPrefix = new byte[bundled.length];
            try (InputStream in = Files.newInputStream(imagePath)) {
                int n = in.read(onDiskPrefix);
                assertEquals(bundled.length, n, "Short read of per-UUID image prefix");
            }
            assertArrayEquals(bundled, onDiskPrefix,
                    "First " + templateSize + " bytes of the per-UUID image must be byte-for-byte "
                            + "the bundled template — the grow-after-copy step must not touch the "
                            + "template payload region.");
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

    /**
     * The grow-and-copy path has to preserve <em>sparseness</em>, not just
     * file length. A 1 GiB ext4 image is typically 60–80 MiB of non-zero
     * content surrounded by holes; if the extraction or the per-UUID copy
     * densified those holes we'd spend 1 GiB of host disk per NVMe item.
     *
     * <p>This test copies a 1 MiB-of-content + sparse-tail source through
     * {@link StorageManager#initImage}, then asserts the destination's real
     * disk allocation is well below the logical 1 GiB cap.
     *
     * <p><b>Why 1 GiB and not something smaller:</b> APFS (the macOS dev
     * filesystem) pre-allocates densely for files below ~64 MiB regardless
     * of the {@link java.nio.file.StandardOpenOption#SPARSE} hint — a perf
     * heuristic that defeats the test at smaller scales but doesn't apply
     * to real-world disk images (always 1 GiB declared via
     * {@code NvmeItem.SIZE_MB}). Sizing the test to match production keeps
     * it honest about user-facing behavior.
     *
     * <p>Threshold of "less than 256 MiB actual" is generous: we expect
     * around 1–4 MiB. The slack covers ext4 metadata if a future version
     * of the test loops back through a real filesystem image, and APFS's
     * own copy-on-write metadata for the file itself.
     */
    @Test
    @DisplayName("copyImage preserves sparseness — 1 MiB content in 1 GiB declared << 256 MiB on disk")
    void copyImagePreservesSparseness() throws IOException {
        UUID u = UUID.randomUUID();
        // Source: 1 MiB content followed by 1 GiB-1MiB of zeros. Above
        // APFS's ~64 MiB sparse-vs-dense threshold so the host filesystem
        // honors the sparse hint.
        Path source = Paths.get("scev", "assets", "sparse-copy-test.bin");
        Files.createDirectories(source.getParent());
        Files.deleteIfExists(source);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                source,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.SPARSE)) {
            byte[] content = new byte[1 << 20];
            for (int i = 0; i < content.length; i++) content[i] = (byte) (i & 0xFF);
            ch.write(java.nio.ByteBuffer.wrap(content));
            ch.position((1024L << 20) - 1); // 1 GiB
            ch.write(java.nio.ByteBuffer.wrap(new byte[] { 0 }));
        }

        Path imagePath = Paths.get(StorageManager.imagePath(u));
        try {
            assertTrue(StorageManager.initImage(u, 1024, "sparse-copy-test.bin"),
                    "initImage must succeed with the 1 GiB sparse source");

            assertEquals(1024L << 20, Files.size(imagePath),
                    "Logical size: 1 GiB (template + grow-after-copy enforces declared cap).");

            long actualKb = duKb(imagePath);
            assertTrue(actualKb < 256 * 1024,
                    "Per-UUID image should use << 256 MiB of real disk (source is "
                            + "1 MiB content + sparse tail). If this fails, sparseCopy regressed "
                            + "— probably reverted to Files.copy which densifies holes, costing "
                            + "users a full 1 GiB per preloaded NVMe item. Actual real disk "
                            + "usage: " + (actualKb / 1024) + " MiB, expected << 256 MiB.");
        } finally {
            Files.deleteIfExists(imagePath);
            Files.deleteIfExists(source);
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
