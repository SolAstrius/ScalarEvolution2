/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Static asset validation. Runs against the {@code src/generated/resources/}
 * tree produced by {@code ./gradlew runData}, catching any placeholder or
 * missing-file bug that would otherwise only manifest visually.
 */
class BlockModelAssetTest {

    private static final Path GENERATED_ASSETS = projectRoot().resolve("src/generated/resources/assets/scev");
    private static final Path MAIN_ASSETS      = projectRoot().resolve("src/main/resources/assets/scev");

    private static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    /**
     * Blocks whose visuals are driven by an OBJ model (not a flat JSON cube).
     * If any of these slip back to a cube_all parent, something regressed.
     */
    private static final List<String> OBJ_BLOCKS = List.of(
            "workstation", "powermark", "tinkerpad", "crt_monitor",
            "terminal", "keyboard", "keyboard_mouse");

    @Test
    @DisplayName("Every modded block has a blockstate file in generated/")
    void eachBlockHasBlockstate() {
        for (String block : OBJ_BLOCKS) {
            Path p = GENERATED_ASSETS.resolve("blockstates").resolve(block + ".json");
            assertTrue(Files.exists(p), "Missing blockstate file: " + p
                    + " (did you forget to run `./gradlew runData`?)");
        }
    }

    @Test
    @DisplayName("Every modded block has a block model file in generated/")
    void eachBlockHasModel() {
        for (String block : OBJ_BLOCKS) {
            Path p = GENERATED_ASSETS.resolve("models/block").resolve(block + ".json");
            assertTrue(Files.exists(p), "Missing block model file: " + p);
        }
    }

    @Test
    @DisplayName("No modded block uses the cube_all placeholder parent")
    void noCubeAllPlaceholder() throws IOException {
        for (String block : OBJ_BLOCKS) {
            Path model = GENERATED_ASSETS.resolve("models/block").resolve(block + ".json");
            if (!Files.exists(model)) continue; // caught by the previous test
            String body = Files.readString(model, StandardCharsets.UTF_8);
            assertFalse(body.contains("\"minecraft:block/cube_all\""),
                    "Block " + block + " still uses minecraft:block/cube_all placeholder. "
                            + "Should reference an OBJ model via the neoforge:obj loader.");
        }
    }

    @Test
    @DisplayName("Every modded block model loads via the neoforge:obj loader")
    void usesObjLoader() throws IOException {
        for (String block : OBJ_BLOCKS) {
            Path model = GENERATED_ASSETS.resolve("models/block").resolve(block + ".json");
            if (!Files.exists(model)) continue;
            String body = Files.readString(model, StandardCharsets.UTF_8);
            assertTrue(body.contains("\"neoforge:obj\""),
                    "Block " + block + " should use the neoforge:obj loader");
        }
    }

    @Test
    @DisplayName("Every OBJ model referenced from JSON actually exists")
    void objFilesExist() {
        for (String block : OBJ_BLOCKS) {
            Path obj = MAIN_ASSETS.resolve("models/block").resolve(block + ".obj");
            assertTrue(Files.exists(obj),
                    "Missing OBJ file " + obj + " (should be under assets/scev/models/block/)");
        }
    }

    /**
     * Per-block vertex envelope. Block space is nominally [0, 1] in each axis, but
     * some meshes intentionally extend outside — {@code keyboard_mouse}'s mouse
     * pokes out to the right of the keyboard (x > 1). The envelopes below encode
     * the expected physical extent of each block's geometry and catch regressions
     * like "the model is off by a translation" (the exact bug this test was added
     * to guard against).
     */
    private record AxisRange(double lo, double hi) {}
    private static final java.util.Map<String, AxisRange[]> EXPECTED_RANGES = java.util.Map.of(
            "workstation",    new AxisRange[] { new AxisRange(0, 1), new AxisRange(0, 1), new AxisRange(0, 1) },
            "powermark",      new AxisRange[] { new AxisRange(0, 1), new AxisRange(0, 1), new AxisRange(0, 1) },
            "tinkerpad",      new AxisRange[] { new AxisRange(0, 1), new AxisRange(0, 1), new AxisRange(0, 1) },
            "crt_monitor",    new AxisRange[] { new AxisRange(0, 1), new AxisRange(0, 1), new AxisRange(0, 1) },
            "terminal",          new AxisRange[] { new AxisRange(0, 1), new AxisRange(0, 1), new AxisRange(0, 1) },
            "keyboard",       new AxisRange[] { new AxisRange(0, 1), new AxisRange(0, 1), new AxisRange(0, 1) },
            // keyboard_mouse: mouse pokes out to the right past x=1 on purpose.
            "keyboard_mouse", new AxisRange[] { new AxisRange(0, 1.4), new AxisRange(0, 1), new AxisRange(0, 1) }
    );

    @Test
    @DisplayName("OBJ vertices lie in expected block-space envelope")
    void objVerticesInBlockSpace() throws IOException {
        for (String block : OBJ_BLOCKS) {
            Path obj = MAIN_ASSETS.resolve("models/block").resolve(block + ".obj");
            if (!Files.exists(obj)) continue;

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (String line : Files.readAllLines(obj, StandardCharsets.UTF_8)) {
                if (!line.startsWith("v ")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                double x = Double.parseDouble(parts[1]);
                double y = Double.parseDouble(parts[2]);
                double z = Double.parseDouble(parts[3]);
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
            }

            AxisRange[] expected = EXPECTED_RANGES.get(block);
            assertTrue(minX >= expected[0].lo - 0.001 && maxX <= expected[0].hi + 0.001,
                    block + " OBJ x range [" + minX + ", " + maxX + "] outside allowed [" + expected[0].lo + ", " + expected[0].hi + "]");
            assertTrue(minY >= expected[1].lo - 0.001 && maxY <= expected[1].hi + 0.001,
                    block + " OBJ y range [" + minY + ", " + maxY + "] outside allowed [" + expected[1].lo + ", " + expected[1].hi + "]");
            assertTrue(minZ >= expected[2].lo - 0.001 && maxZ <= expected[2].hi + 0.001,
                    block + " OBJ z range [" + minZ + ", " + maxZ + "] outside allowed [" + expected[2].lo + ", " + expected[2].hi + "]");
        }
    }
}
