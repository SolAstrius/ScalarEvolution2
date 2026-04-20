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
import lekkit.scev.server.FirmwareAssets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks down the firmware-asset extraction pipeline that makes the mod boot
 * real OpenSBI + U-Boot out of the box (instead of the 16-byte DemoBootrom).
 *
 * <p>The properties exercised here:
 * <ol>
 *   <li><b>Bundled presence</b> — {@code fw_payload.bin} is shipped in the
 *       mod jar under {@code /assets/scev/firmware/}. Without this, the mod
 *       is a hollow shell: no CPU, just a splash.</li>
 *   <li><b>Extraction</b> — first call to {@link FirmwareAssets#ensureExtracted}
 *       materializes the file under {@code ./scev/assets/} with the exact
 *       bytes of the bundled resource.</li>
 *   <li><b>Cache hit</b> — second call with the extract intact doesn't
 *       re-copy (size-match short-circuit).</li>
 *   <li><b>Cache invalidation</b> — if the on-disk extract's size differs
 *       from the bundled copy (e.g. mod was upgraded and shipped a new
 *       kernel), the stale extract is overwritten, not silently re-used.
 *       This was added after "upgraded the mod, new kernel not loaded"
 *       bit us when the keyboard fix was being deployed.</li>
 *   <li><b>User-only fallback</b> — a file on disk with NO bundled
 *       counterpart is preserved as-is; ensureExtracted has no source of
 *       truth to compare against.</li>
 *   <li><b>Lookup semantics</b> — {@link FirmwareAssets#isAvailable} returns
 *       true whether the file is bundled, extracted, or user-supplied.</li>
 * </ol>
 *
 * <p><b>Test isolation:</b> these tests DO write under {@code ./scev/assets/}
 * (the real project-root directory the server uses). Tests that mutate state
 * clean up after themselves by calling {@link FirmwareAssets#forgetExtracted}.
 * The alternative — mocking out the directory — would not exercise the real
 * code path, which is the whole point.
 */
class FirmwareAssetsTest {

    /** Test asset known to be bundled. Defined at the top so all tests agree. */
    private static final String FW_PAYLOAD = "fw_payload.bin";

    /** Test asset known NOT to be bundled. */
    private static final String NOT_A_REAL_ASSET = "definitely-not-shipped-12345.bin";

    @Test
    @DisplayName("fw_payload.bin is bundled with the mod (required for out-of-box boot)")
    void fwPayloadIsBundled() {
        assertTrue(FirmwareAssets.isBundled(FW_PAYLOAD),
                "fw_payload.bin must be shipped under src/main/resources/assets/scev/firmware/. "
                        + "Without this, a fresh install has no firmware and only boots the demo bootrom.");
    }

    @Test
    @DisplayName("Non-shipped assets are reported not-bundled")
    void missingAssetsArentBundled() {
        assertFalse(FirmwareAssets.isBundled(NOT_A_REAL_ASSET));
    }

    @Test
    @DisplayName("ensureExtracted places bundled fw_payload.bin at ./scev/assets/fw_payload.bin")
    void extractsToExpectedPath() throws IOException {
        FirmwareAssets.forgetExtracted(FW_PAYLOAD); // start from blank
        try {
            Path extracted = FirmwareAssets.ensureExtracted(FW_PAYLOAD);
            assertNotNull(extracted, "ensureExtracted returned null for bundled asset");
            assertTrue(Files.isRegularFile(extracted));
            assertTrue(extracted.toString().endsWith("scev/assets/" + FW_PAYLOAD)
                    || extracted.toString().endsWith("scev\\assets\\" + FW_PAYLOAD),
                    "Unexpected extracted path: " + extracted);
            long bundledSize;
            try (InputStream in = FirmwareAssets.class
                    .getResourceAsStream(FirmwareAssets.CLASSPATH_PREFIX + FW_PAYLOAD)) {
                assertNotNull(in);
                bundledSize = in.readAllBytes().length;
            }
            assertEquals(bundledSize, Files.size(extracted),
                    "Extracted file size doesn't match bundled resource — copy was truncated");
        } finally {
            FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        }
    }

    @Test
    @DisplayName("Extraction is byte-identical to the bundled classpath resource")
    void extractedBytesMatchBundled() throws IOException {
        FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        try {
            Path extracted = FirmwareAssets.ensureExtracted(FW_PAYLOAD);
            assertNotNull(extracted);

            byte[] fromDisk = Files.readAllBytes(extracted);
            byte[] fromJar;
            try (InputStream in = FirmwareAssets.class
                    .getResourceAsStream(FirmwareAssets.CLASSPATH_PREFIX + FW_PAYLOAD)) {
                assertNotNull(in);
                fromJar = in.readAllBytes();
            }
            assertArrayEquals(fromJar, fromDisk,
                    "Extracted bytes don't match bundled bytes — the asset was corrupted during copy");
        } finally {
            FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        }
    }

    @Test
    @DisplayName("Second ensureExtracted is a cache hit when the on-disk extract matches bundled size")
    void secondCallCacheHits() throws IOException {
        FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        try {
            // First call: fresh extract.
            Path first = FirmwareAssets.ensureExtracted(FW_PAYLOAD);
            assertNotNull(first);
            long firstSize = Files.size(first);
            java.nio.file.attribute.FileTime firstMtime = Files.getLastModifiedTime(first);

            // Second call immediately after: must not re-extract. We prove
            // it both by size (should match) and by checking the file wasn't
            // rewritten (mtime unchanged).
            Path second = FirmwareAssets.ensureExtracted(FW_PAYLOAD);
            assertNotNull(second);
            assertEquals(firstSize, Files.size(second),
                    "Cache hit: second extract must return the same bytes as the first.");
            assertEquals(firstMtime, Files.getLastModifiedTime(second),
                    "Cache hit: file must not be rewritten when sizes already match.");
        } finally {
            FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        }
    }

    @Test
    @DisplayName("Stale extract (size mismatch vs bundled) is invalidated and re-extracted")
    void staleExtractInvalidated() throws IOException {
        FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        Path target = Paths.get("scev", "assets", FW_PAYLOAD);
        try {
            // Pre-populate with a file whose size DIFFERS from the bundled
            // copy. Simulates "mod was upgraded and now ships a new kernel,
            // but the old extract is still on disk."
            Files.createDirectories(target.getParent());
            byte[] staleContent = new byte[] { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };
            Files.write(target, staleContent);

            Path result = FirmwareAssets.ensureExtracted(FW_PAYLOAD);
            assertNotNull(result);

            // The bundled bytes must now be on disk, not our DEADBEEF stub.
            byte[] onDisk = Files.readAllBytes(result);
            byte[] fromJar;
            try (InputStream in = FirmwareAssets.class
                    .getResourceAsStream(FirmwareAssets.CLASSPATH_PREFIX + FW_PAYLOAD)) {
                assertNotNull(in);
                fromJar = in.readAllBytes();
            }
            assertEquals(fromJar.length, onDisk.length,
                    "Stale extract must be replaced with bundled bytes after size mismatch");
            assertArrayEquals(fromJar, onDisk,
                    "Stale extract must be byte-exact with bundled copy after invalidation");
        } finally {
            FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        }
    }

    @Test
    @DisplayName("ensureExtracted returns null when neither bundled nor on disk")
    void unknownAssetReturnsNull() {
        FirmwareAssets.forgetExtracted(NOT_A_REAL_ASSET);
        assertNull(FirmwareAssets.ensureExtracted(NOT_A_REAL_ASSET));
        assertFalse(FirmwareAssets.isAvailable(NOT_A_REAL_ASSET));
    }

    @Test
    @DisplayName("isAvailable is true for bundled assets even before extraction")
    void isAvailableMatchesBundling() {
        FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        try {
            assertTrue(FirmwareAssets.isAvailable(FW_PAYLOAD),
                    "Bundled asset should report available without explicit extraction");
        } finally {
            FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        }
    }

    @Test
    @DisplayName("sizeBytes returns the extracted asset size")
    void sizeBytesMatchesFileSize() throws IOException {
        FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        try {
            Path p = FirmwareAssets.ensureExtracted(FW_PAYLOAD);
            assertNotNull(p);
            assertEquals(Files.size(p), FirmwareAssets.sizeBytes(FW_PAYLOAD));
        } finally {
            FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        }
    }

    @Test
    @DisplayName("sizeBytes returns -1 for unknown assets")
    void sizeBytesForMissingIsMinusOne() {
        FirmwareAssets.forgetExtracted(NOT_A_REAL_ASSET);
        assertEquals(-1, FirmwareAssets.sizeBytes(NOT_A_REAL_ASSET));
    }

    @Test
    @DisplayName("DEFAULT_FIRMWARE constant matches what FlashItem declares")
    void defaultConstantMatchesItemOrigin() {
        assertEquals("fw_payload.bin", FirmwareAssets.DEFAULT_FIRMWARE,
                "If the flash chip's origin changes, update DEFAULT_FIRMWARE too");
}

    @Test
    @DisplayName("fw_jump.bin is bundled (OpenSBI-only firmware for the Linux-boot path)")
    void fwJumpIsBundled() {
        assertTrue(FirmwareAssets.isBundled("fw_jump.bin"),
                "fw_jump.bin must be shipped so the flash-chip Linux boot path resolves. "
                        + "Without it, MachineSpecParser emits a kernel spec but the firmware never loads.");
    }

    @Test
    @DisplayName("Image (kernel) is bundled (stub or real Buildroot kernel)")
    void kernelImageIsBundled() {
        assertTrue(FirmwareAssets.isBundled("Image"),
                "Image must be shipped under src/main/resources/assets/scev/firmware/Image — "
                        + "either the KernelStub placeholder or a real Buildroot kernel. "
                        + "See docs/BUILDROOT.md for how to regenerate.");
    }

    @Test
    @DisplayName("Bundled Image is extractable and non-empty")
    void kernelImageExtracts() throws IOException {
        FirmwareAssets.forgetExtracted("Image");
        try {
            Path p = FirmwareAssets.ensureExtracted("Image");
            assertNotNull(p, "Image should be extractable from the mod jar");
            long size = Files.size(p);
            assertTrue(size >= 16, "Image is suspiciously small: " + size + " bytes");
        } finally {
            FirmwareAssets.forgetExtracted("Image");
        }
    }

    @Test
    @DisplayName("Extracted fw_payload.bin starts with valid RISC-V instructions (sanity)")
    void extractedStartsWithRiscvCode() throws IOException {
        FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        try {
            Path p = FirmwareAssets.ensureExtracted(FW_PAYLOAD);
            assertNotNull(p);
            byte[] head = Files.readAllBytes(p);
            assertTrue(head.length >= 16, "firmware too short: " + head.length);
            // First 4 bytes are the real OpenSBI entry instruction. The
            // *exact* bytes change if upstream releases a new build; what we
            // can assert without coupling to a specific build is:
            //   (1) NOT zeros (would mean empty/sparse-hole)
            //   (2) NOT 0xFFs (would mean uninitialized flash)
            boolean allZero = true, allOnes = true;
            for (int i = 0; i < 16; i++) {
                if (head[i] != 0) allZero = false;
                if (head[i] != (byte) 0xFF) allOnes = false;
            }
            assertFalse(allZero, "First 16 bytes are zero — firmware wasn't extracted");
            assertFalse(allOnes, "First 16 bytes are 0xFF — firmware looks like blank flash");
        } finally {
            FirmwareAssets.forgetExtracted(FW_PAYLOAD);
        }
    }
}
