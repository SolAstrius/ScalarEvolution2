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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces that every block class extending {@code BaseEntityBlock} overrides
 * {@code getRenderShape} to return {@code RenderShape.MODEL}.
 *
 * <p><b>Why this exists.</b> {@code BaseEntityBlock.getRenderShape} defaults
 * to {@link net.minecraft.world.level.block.RenderShape#INVISIBLE} — useful
 * for chests/beacons that render entirely through a {@code BlockEntityRenderer},
 * but a silent footgun for blocks that want their JSON/OBJ model to draw.
 * Without the override, the chunk mesher skips the block entirely and it
 * appears as invisible geometry in the world.
 *
 * <p>This test is a source grep — fast, doesn't need a Minecraft boot, and
 * catches the bug at CI time rather than at "why is my block invisible"
 * debugging time. Any {@code class X extends BaseEntityBlock} (directly or
 * via an intermediate like {@code DirectionalBlock}) must either contain a
 * {@code getRenderShape} method of its own, or its base class must.
 */
class BlockRenderShapeTest {

    private static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    private static final Path BLOCKS_PKG       = projectRoot().resolve("src/main/java/lekkit/scev/blocks");
    private static final Path BLOCK_ENTITY_PKG = projectRoot().resolve("src/main/java/lekkit/scev/blockentity");

    /**
     * Every block class that descends from {@code BaseEntityBlock} must end
     * up inheriting a {@code getRenderShape} override somewhere in its
     * ancestor chain. We don't require the leaf class to declare it — if the
     * leaf extends a local intermediate (like {@code DirectionalBlock}) and
     * that intermediate declares it, the leaf is covered.
     *
     * <p>Concretely: scan every .java in the blocks package, find those that
     * descend (directly or transitively, via an intermediate in the same
     * tree) from {@code BaseEntityBlock}. For each one, walk the "extends"
     * chain back through local files. If the leaf OR any local ancestor
     * declares {@code getRenderShape}, it's fine. Otherwise flag it.
     */
    @Test
    @DisplayName("Every scev BaseEntityBlock subclass inherits a getRenderShape override")
    void everyBaseEntityBlockHasRenderShape() throws IOException {
        Map<String, String> simpleNameToSource = new HashMap<>();
        for (Path java : findJavaSources()) {
            String name = java.getFileName().toString().replace(".java", "");
            simpleNameToSource.put(name, Files.readString(java, StandardCharsets.UTF_8));
        }

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : simpleNameToSource.entrySet()) {
            String cls = e.getKey();
            String src = e.getValue();
            // Not a BaseEntityBlock descendant? skip.
            if (!descendsFromBaseEntityBlock(cls, simpleNameToSource, new HashSet<>())) continue;
            // Walk up the chain, looking for getRenderShape anywhere.
            if (!anyAncestorDeclaresGetRenderShape(cls, simpleNameToSource, new HashSet<>())) {
                offenders.add(cls);
            }
        }
        assertTrue(offenders.isEmpty(),
                "The following scev block classes descend from BaseEntityBlock but no class in "
                        + "their ancestor chain (inside this repo) overrides getRenderShape. "
                        + "BaseEntityBlock defaults to RenderShape.INVISIBLE, so these blocks "
                        + "render as invisible geometry. Add to the leaf or a shared base:\n\n"
                        + "    @Override\n"
                        + "    protected RenderShape getRenderShape(BlockState state) {\n"
                        + "        return RenderShape.MODEL;\n"
                        + "    }\n\nOffenders: " + offenders);
    }

    /** Does {@code cls} end up at {@code BaseEntityBlock} walking the extends chain? */
    private static boolean descendsFromBaseEntityBlock(
            String cls, Map<String, String> files, Set<String> seen) {
        if (!seen.add(cls)) return false;                // cycle guard
        String src = files.get(cls);
        if (src == null) return false;                   // external class — can't see its ancestry
        Matcher m = EXTENDS_CLAUSE.matcher(src);
        if (!m.find()) return false;
        String parent = m.group(1);
        if ("BaseEntityBlock".equals(parent)) return true;
        return descendsFromBaseEntityBlock(parent, files, seen);
    }

    /** Does {@code cls} or any of its local ancestors declare getRenderShape? */
    private static boolean anyAncestorDeclaresGetRenderShape(
            String cls, Map<String, String> files, Set<String> seen) {
        if (!seen.add(cls)) return false;
        String src = files.get(cls);
        if (src == null) return false;                   // reached an external class — stop
        if (RENDER_SHAPE_METHOD.matcher(src).find()) return true;
        Matcher m = EXTENDS_CLAUSE.matcher(src);
        if (!m.find()) return false;
        return anyAncestorDeclaresGetRenderShape(m.group(1), files, seen);
    }

    /** Captures the first {@code extends Foo} simple name in the file. */
    private static final Pattern EXTENDS_CLAUSE =
            Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+(\\w+)");
    private static final Pattern RENDER_SHAPE_METHOD =
            Pattern.compile("\\bgetRenderShape\\s*\\(");

    private static List<Path> findJavaSources() throws IOException {
        List<Path> all = new ArrayList<>();
        for (Path pkg : new Path[] { BLOCKS_PKG, BLOCK_ENTITY_PKG }) {
            if (!Files.isDirectory(pkg)) continue;
            try (Stream<Path> walk = Files.walk(pkg)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(all::add);
            }
        }
        return all;
    }

    /**
     * Sanity-check that {@code DirectionalBlock.getRenderShape} returns
     * {@code RenderShape.MODEL} — not some other value that a sloppy refactor
     * might introduce. We grep because constructing a Block subclass at JUnit
     * time requires registry bootstrap we'd rather skip.
     */
    @Test
    @DisplayName("DirectionalBlock.getRenderShape returns RenderShape.MODEL (not INVISIBLE)")
    void directionalBlockReturnsModelRenderShape() throws IOException {
        Path file = BLOCKS_PKG.resolve("DirectionalBlock.java");
        String src = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(
                "getRenderShape\\s*\\([^)]*\\)\\s*\\{[^}]*return\\s+RenderShape\\.(\\w+)\\s*;",
                Pattern.DOTALL).matcher(src);
        assertTrue(m.find(),
                "DirectionalBlock.getRenderShape must be present and return a RenderShape "
                        + "enum constant. File: " + file);
        String value = m.group(1);
        assertEquals("MODEL", value,
                "DirectionalBlock.getRenderShape returns RenderShape." + value
                        + " — must be MODEL for the JSON/OBJ block model to render. "
                        + "INVISIBLE means the chunk mesher skips the block entirely.");
    }
}
