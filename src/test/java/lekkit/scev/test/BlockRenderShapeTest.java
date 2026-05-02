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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * Captures the first parent class name in the file. Matches both Java
     * ({@code class Foo extends Bar}) and Kotlin ({@code class Foo : Bar} /
     * {@code class Foo(...) : Bar(...)} / {@code abstract class Foo
     * protected constructor(...) : Bar(...)}) declarations.
     */
    private static final Pattern EXTENDS_CLAUSE = Pattern.compile(
            "\\bclass\\s+\\w+\\s+extends\\s+(\\w+)|"
            + "\\bclass\\s+\\w+(?:\\s+protected\\s+constructor)?\\s*(?:\\([^)]*\\))?\\s*:\\s*(\\w+)");

    private static final Pattern RENDER_SHAPE_METHOD = Pattern.compile("\\bgetRenderShape\\s*\\(");

    /** Pull the first parent simple-name out of either a Java or Kotlin declaration. */
    private static String parentOf(Matcher m) {
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    @Test
    @DisplayName("Every scev BaseEntityBlock subclass inherits a getRenderShape override")
    void everyBaseEntityBlockHasRenderShape() throws IOException {
        Map<String, String> simpleNameToSource = new HashMap<>();
        for (Path src : SourcePackages.walk("lekkit/scev/blocks", "lekkit/scev/blockentity")) {
            String fname = src.getFileName().toString();
            String name = fname.replaceFirst("\\.(java|kt)$", "");
            simpleNameToSource.put(name, Files.readString(src, StandardCharsets.UTF_8));
        }

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : simpleNameToSource.entrySet()) {
            String cls = e.getKey();
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
                        + "render as invisible geometry. Add to the leaf or a shared base. "
                        + "Offenders: " + offenders);
    }

    /** Does {@code cls} end up at {@code BaseEntityBlock} walking the extends chain? */
    private static boolean descendsFromBaseEntityBlock(
            String cls, Map<String, String> files, Set<String> seen) {
        if (!seen.add(cls)) return false;                // cycle guard
        String src = files.get(cls);
        if (src == null) return false;                   // external class — can't see its ancestry
        Matcher m = EXTENDS_CLAUSE.matcher(src);
        if (!m.find()) return false;
        String parent = parentOf(m);
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
        return anyAncestorDeclaresGetRenderShape(parentOf(m), files, seen);
    }

    /**
     * Sanity-check that {@code DirectionalBlock.getRenderShape} returns
     * {@code RenderShape.MODEL} — not some other value that a sloppy
     * refactor might introduce. We grep because constructing a Block
     * subclass at JUnit time requires registry bootstrap we'd rather skip.
     */
    @Test
    @DisplayName("DirectionalBlock.getRenderShape returns RenderShape.MODEL (not INVISIBLE)")
    void directionalBlockReturnsModelRenderShape() throws IOException {
        Path file = SourcePackages.find("lekkit/scev/blocks/DirectionalBlock")
                .orElseThrow(() -> new AssertionError(
                        "DirectionalBlock source must exist under src/main/{kotlin,java}/lekkit/scev/blocks"));
        String src = Files.readString(file, StandardCharsets.UTF_8);
        // Capture the RenderShape constant inside getRenderShape's body.
        // Tolerates Java method bodies (`{ … return RenderShape.MODEL; }`)
        // and Kotlin expression bodies with optional return-type annotation
        // (`fun getRenderShape(...): RenderShape = RenderShape.MODEL`).
        Matcher m = Pattern.compile(
                "getRenderShape\\s*\\([^)]*\\)" + // method header up to the closing paren
                "(?:\\s*:\\s*\\w+)?" +             // optional Kotlin `: RenderShape` return type
                "\\s*[={][^}]*?RenderShape\\.(\\w+)",
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
