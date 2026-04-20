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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the OBJ-driven block models will actually produce visible
 * geometry when NeoForge's {@code neoforge:obj} loader bakes them.
 *
 * <p><b>Why this test exists.</b> {@link BlockModelAssetTest} confirms the
 * static shape of the generated JSON + OBJ assets. It does <em>not</em> catch
 * the class of bug where files are well-formed but the loader silently drops
 * the geometry. The concrete regression this test locks down:
 *
 * <p>{@code ObjModel.ModelMesh.addQuads()} returns early if {@code mat == null}
 * (NeoForge sources, {@code ObjModel.java:541-543}). When the preprocessing
 * strips {@code mtllib}/{@code usemtl} — as an earlier port attempt did — every
 * face ends up in a mesh with {@code mat == null}, the loader skips every
 * mesh, and the block renders as invisible geometry in the real client.
 * GameTests still pass because they run headless.
 *
 * <p>This class re-implements just enough of {@code ObjModel.parse}'s
 * material-tracking and mesh-grouping logic to detect that failure mode
 * statically — no Minecraft boot needed, runs in under a second.
 *
 * <p>If any of these fail, <b>the block will not render</b>. Do not relax
 * the assertions to make a regression "go green".
 */
class BlockModelRenderingTest {

    private static final Path MAIN_ASSETS      = projectRoot().resolve("src/main/resources/assets/scev");
    private static final Path GENERATED_ASSETS = projectRoot().resolve("src/generated/resources/assets/scev");

    private static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    /** OBJ-backed blocks whose visual correctness this test class guards. */
    private static final List<String> OBJ_BLOCKS = List.of(
            "workstation", "powermark", "tinkerpad", "crt_monitor",
            "vt100", "keyboard", "keyboard_mouse");

    // ------------------------------------------------------------------------
    // Test: every face in every OBJ has an active `usemtl` when it's declared.
    //
    // This is the single assertion that would have caught the "invisible
    // block" bug. If even one face lacks a material scope, ObjModel will
    // silently drop that mesh's entire face list at bake time.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every face in every OBJ is inside a `usemtl` scope (renderable)")
    void everyFaceHasMaterial() throws IOException {
        for (String block : OBJ_BLOCKS) {
            ParsedObj obj = parseObj(objPath(block));
            int unmateriled = 0;
            long firstBadLine = -1;
            for (Face f : obj.faces) {
                if (f.usemtl == null) {
                    unmateriled++;
                    if (firstBadLine < 0) firstBadLine = f.lineNumber;
                }
            }
            assertEquals(0, unmateriled,
                    "Block " + block + " has " + unmateriled + " face(s) without a `usemtl` scope "
                            + "(first offending face at line " + firstBadLine + "). "
                            + "NeoForge's ObjModel skips every mesh with a null material — "
                            + "the block will render as invisible geometry. "
                            + "Add `usemtl <name>` before the first face declaration.");
            assertFalse(obj.faces.isEmpty(),
                    "Block " + block + " has no face declarations at all — the OBJ is empty.");
        }
    }

    // ------------------------------------------------------------------------
    // Test: every `mtllib` referenced by an OBJ points to a file that exists.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every `mtllib` reference in an OBJ resolves to an existing .mtl file")
    void mtllibFilesExist() throws IOException {
        for (String block : OBJ_BLOCKS) {
            ParsedObj obj = parseObj(objPath(block));
            assertFalse(obj.mtllibs.isEmpty(),
                    "Block " + block + " has no `mtllib` statement. Without one, every `usemtl` "
                            + "in the OBJ resolves to an unknown material (NoSuchElementException at "
                            + "ObjMaterialLibrary.getMaterial). Add `mtllib <file>.mtl` at the top.");
            for (String lib : obj.mtllibs) {
                Path mtlPath = objPath(block).getParent().resolve(lib);
                assertTrue(Files.exists(mtlPath),
                        "Block " + block + " references mtllib `" + lib + "` but the file does not exist at "
                                + mtlPath);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Test: every `usemtl` name used by an OBJ is defined in its MTL library.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every `usemtl` name is defined by a `newmtl` in the referenced MTL library")
    void usemtlNamesExistInMtl() throws IOException {
        for (String block : OBJ_BLOCKS) {
            ParsedObj obj = parseObj(objPath(block));
            Set<String> usedMaterials = new HashSet<>();
            for (Face f : obj.faces) {
                if (f.usemtl != null) usedMaterials.add(f.usemtl);
            }
            if (usedMaterials.isEmpty()) continue;

            Set<String> definedMaterials = new HashSet<>();
            for (String lib : obj.mtllibs) {
                ParsedMtl mtl = parseMtl(objPath(block).getParent().resolve(lib));
                definedMaterials.addAll(mtl.materials.keySet());
            }

            for (String mat : usedMaterials) {
                assertTrue(definedMaterials.contains(mat),
                        "Block " + block + " uses material `" + mat + "` but no MTL library defines it. "
                                + "Defined materials: " + definedMaterials
                                + ". This would throw NoSuchElementException from "
                                + "ObjMaterialLibrary.getMaterial at parse time.");
            }
        }
    }

    // ------------------------------------------------------------------------
    // Test: every `#slot` reference in an MTL's map_Kd has a matching entry
    // in the consuming JSON model's textures map.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every #slot in an MTL's map_Kd is bound in the JSON model textures map")
    void mtlSlotsAreBoundInJsonTextures() throws IOException {
        for (String block : OBJ_BLOCKS) {
            ParsedObj obj = parseObj(objPath(block));
            Set<String> requiredSlots = new HashSet<>();
            for (String lib : obj.mtllibs) {
ParsedMtl mtl = parseMtl(objPath(block).getParent().resolve(lib));
                // Only count slots used by materials actually referenced by this block.
                Set<String> usedMaterials = new HashSet<>();
                for (Face f : obj.faces) {
                    if (f.usemtl != null) usedMaterials.add(f.usemtl);
                }
                for (Map.Entry<String, String> e : mtl.materials.entrySet()) {
                    if (!usedMaterials.contains(e.getKey())) continue;
                    String slot = extractSlot(e.getValue());
                    if (slot != null) requiredSlots.add(slot);
                }
            }

            if (requiredSlots.isEmpty()) continue;

            Map<String, String> textures = parseJsonTextures(generatedModelPath(block));
            for (String slot : requiredSlots) {
                assertTrue(textures.containsKey(slot),
                        "Block " + block + "'s MTL references `#" + slot + "` but the generated JSON model "
                                + generatedModelPath(block) + " has no `textures." + slot + "` entry. "
                                + "UnbakedGeometryHelper.resolveDirtyMaterial would resolve it to the "
                                + "missing-texture sprite. Available slots: " + textures.keySet());
            }
        }
    }

    // ------------------------------------------------------------------------
    // Test: every texture slot in a JSON model resolves to a real PNG on disk.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every JSON texture binding points to an existing PNG under assets/scev/textures")
    void jsonTextureBindingsExistOnDisk() throws IOException {
        for (String block : OBJ_BLOCKS) {
            Map<String, String> textures = parseJsonTextures(generatedModelPath(block));
            for (Map.Entry<String, String> e : textures.entrySet()) {
                String value = e.getValue();
                // Skip layered references to other slots — only verify direct ResourceLocations.
                if (value.startsWith("#")) continue;
                String[] parts = value.split(":", 2);
                String ns = parts.length == 2 ? parts[0] : "minecraft";
                String path = parts.length == 2 ? parts[1] : parts[0];
                // We only track scev-owned textures (mod-internal); skip vanilla refs.
                if (!"scev".equals(ns)) continue;
                Path png = MAIN_ASSETS.resolve("textures").resolve(path + ".png");
                assertTrue(Files.exists(png),
                        "Block " + block + "'s JSON model slot `" + e.getKey() + "` -> `" + value
                                + "` resolves to missing PNG: " + png);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Test: simulating ObjModel's bake pipeline, every block produces >0
    // renderable quads.
    //
    // This is the "would this actually render?" check. We replay the exact
    // mesh-grouping + mat-null-skip logic from ObjModel.ModelMesh.addQuads
    // and count surviving quads per block.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every block produces at least one renderable quad when baked")
    void modelProducesRenderableGeometry() throws IOException {
        for (String block : OBJ_BLOCKS) {
            ParsedObj obj = parseObj(objPath(block));

            // Load every material referenced by any mtllib.
            Map<String, String> matToSlot = new HashMap<>();
            for (String lib : obj.mtllibs) {
                ParsedMtl mtl = parseMtl(objPath(block).getParent().resolve(lib));
                for (Map.Entry<String, String> e : mtl.materials.entrySet()) {
                    matToSlot.put(e.getKey(), e.getValue());
                }
            }

            int renderableQuads = 0;
            int droppedQuads = 0;
            for (Face f : obj.faces) {
                // ObjModel.ModelMesh.addQuads: `if (mat == null) return;`
                if (f.usemtl == null) {
                    droppedQuads++;
                    continue;
                }
                // `mtllib.getMaterial(name)` throws NoSuchElementException if
                // the material isn't defined — covered by usemtlNamesExistInMtl.
                if (!matToSlot.containsKey(f.usemtl)) {
                    droppedQuads++;
                    continue;
                }
                renderableQuads++;
            }

            assertEquals(0, droppedQuads,
                    "Block " + block + " would drop " + droppedQuads + " quads at bake time "
                            + "(mat==null or material undefined). See test failure detail above.");
            assertTrue(renderableQuads > 0,
                    "Block " + block + " produces zero renderable quads — it would be invisible "
                            + "in the real client. Check that the OBJ has `usemtl` and a valid mtllib.");

            // Sanity check: minimum face count for a meaningful 3D shape.
            // A block that renders as < 6 quads is almost certainly broken
            // (a cube has 6 faces minimum).
            assertTrue(renderableQuads >= 6,
                    "Block " + block + " produces only " + renderableQuads
                            + " quads. Even a unit cube has 6 faces — this is likely broken geometry.");
        }
    }

    // ========================================================================
    // OBJ / MTL parsing helpers
    //
    // Purpose: re-implement just enough of NeoForge's ObjModel.parse and
    // ObjMaterialLibrary to make the assertions above meaningful. We track
    // what `ObjModel` would see: which `usemtl` scope each face belongs to,
    // which materials are declared in each MTL, which texture slots those
    // materials reference.
    //
    // This intentionally mirrors the relevant logic in
    // net.neoforged.neoforge.client.model.obj.ObjModel — if NeoForge changes
    // the contract, this parser will need to be updated.
    // ========================================================================

    /** Parsed summary of an OBJ file — just what's needed for material validation. */
    private record ParsedObj(List<String> mtllibs, List<Face> faces) {}

    /** A face, annotated with the `usemtl` name in scope when declared. */
    private record Face(String usemtl, long lineNumber) {}

    /** Parsed summary of an MTL library — just the material table + map_Kd. */
    private record ParsedMtl(Map<String, String> materials) {}

    private static Path objPath(String block) {
        return MAIN_ASSETS.resolve("models/block").resolve(block + ".obj");
    }

    private static Path generatedModelPath(String block) {
        return GENERATED_ASSETS.resolve("models/block").resolve(block + ".json");
    }

    /**
     * Minimal OBJ parser mirroring ObjModel.parse's material-tracking logic:
     * {@code mtllib} records the library, {@code usemtl} sets the current
     * material scope, {@code f} emits a face tagged with the current scope.
     */
    private static ParsedObj parseObj(Path path) throws IOException {
        List<String> mtllibs = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        String currentMtl = null;
        long lineNo = 0;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] tokens = line.split("\\s+");
            switch (tokens[0]) {
                case "mtllib" -> {
                    if (tokens.length >= 2) mtllibs.add(tokens[1]);
                }
                case "usemtl" -> {
if (tokens.length >= 2) currentMtl = tokens[1];
                }
                case "f" -> faces.add(new Face(currentMtl, lineNo));
                default -> { /* ignored */ }
            }
        }
        return new ParsedObj(mtllibs, faces);
    }

    /**
     * Minimal MTL parser mirroring ObjMaterialLibrary: records each
     * {@code newmtl} with its {@code map_Kd} value (the diffuse texture
     * reference, which is what ObjModel uses for the renderable sprite).
     */
    private static ParsedMtl parseMtl(Path path) throws IOException {
        Map<String, String> materials = new LinkedHashMap<>();
        String current = null;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] tokens = line.split("\\s+");
            switch (tokens[0]) {
                case "newmtl" -> {
                    if (tokens.length >= 2) {
                        current = tokens[1];
                        materials.putIfAbsent(current, null);
                    }
                }
                case "map_Kd" -> {
                    if (current != null && tokens.length >= 2) {
                        // The last token is the texture ref (options go first).
                        materials.put(current, tokens[tokens.length - 1]);
                    }
                }
                default -> { /* ignored */ }
            }
        }
        return new ParsedMtl(materials);
    }

    /**
     * If {@code value} is a {@code #slot} reference, return the slot name;
     * otherwise return null. Matches the convention used by
     * {@code UnbakedGeometryHelper.resolveDirtyMaterial}.
     */
    private static String extractSlot(String value) {
        if (value == null) return null;
        return value.startsWith("#") ? value.substring(1) : null;
    }

    /**
     * Extract {@code textures: { slot: value, ... }} from a block model JSON
     * without depending on a JSON library (keep the test dep-light).
     */
    private static Map<String, String> parseJsonTextures(Path json) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.exists(json)) return result;
        String body = Files.readString(json, StandardCharsets.UTF_8);
        // Match the "textures" object, non-greedily.
        Matcher texturesBlock = Pattern.compile("\"textures\"\\s*:\\s*\\{([^}]*)}").matcher(body);
        if (!texturesBlock.find()) return result;
        String inside = texturesBlock.group(1);
        Matcher entry = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").matcher(inside);
        while (entry.find()) {
            result.put(entry.group(1), entry.group(2));
        }
        return result;
    }
}
