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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-grep + vertex-math tests for the TinkerpadRenderer that pin down
 * the orientation fix for the "display rendered on the other side" bug.
 *
 * <p><b>Why this isn't a real renderer test.</b> We can't instantiate
 * {@code BlockEntityRenderer} in a unit test — it requires a Minecraft
 * {@code MultiBufferSource}, a client render context, and a live BE.
 * Instead we parse the renderer's own {@code emitVertex} calls out of the
 * source, compute the right-hand-rule cross product of the first three
 * vertices, and assert the resulting face normal.
 *
 * <p><b>What's locked in.</b>
 * <ul>
 *   <li>Exactly one quad is emitted, on the lid's +Z face (interior /
 *       keyboard-facing). A real-laptop screen is on the interior of the
 *       lid, not its exterior; drawing on the exterior produces a
 *       "screen on the back cover" visual that looks wrong.</li>
 *   <li>Winding {@code BL → TL → TR → BR} gives a -Z block-local normal.
 *       After a 180° yaw for {@code FACING=SOUTH} (the typical placement
 *       where the player is south of the block and looked north to place
 *       it), that rotates to +Z in world, facing the player. The player
 *       sees the quad's front face and UVs read left-to-right.</li>
 *   <li>UVs are canonical: {@code BL→(0,1), TL→(0,0), TR→(1,0), BR→(1,1)}.
 *       No U or V mirror hacks.</li>
 * </ul>
 */
class TinkerpadRendererTest {

    private static final Path RENDERER = projectRoot()
            .resolve("src/main/java/lekkit/scev/client/render/blockentity/TinkerpadRenderer.java");

    private static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    @Test
    @DisplayName("Renderer emits exactly one quad (4 vertices) on the lid's interior face")
    void singleQuadOnInteriorFace() throws IOException {
        List<Vertex> quad = parseEmitVertexCalls();
        assertEquals(4, quad.size(),
                "renderer must emit exactly 4 vertices = one quad on the lid's interior face");

        String src = Files.readString(RENDERER);
        double lidZMax = parseConst(src, "LID_Z_MAX");
        for (Vertex v : quad) {
            assertTrue(v.z > lidZMax,
                    "every vertex must sit outside the lid on the +Z (interior) side "
                            + "(z > LID_Z_MAX = " + lidZMax + "). Got z=" + v.z + ". "
                            + "If you moved the quad to the -Z face, revert: the exterior face "
                            + "shows a 'screen on the back of the lid', not where a real "
                            + "laptop screen lives.");
        }
    }

    @Test
    @DisplayName("Winding yields a -Z block-local normal (→ +Z world front after FACING=SOUTH)")
    void windingYieldsNegativeZBlockLocalNormal() throws IOException {
        List<Vertex> quad = parseEmitVertexCalls();
        double[] a = sub(quad.get(1), quad.get(0));
        double[] b = sub(quad.get(2), quad.get(0));
        double[] n = cross(a, b);

        assertTrue(n[2] < 0,
                "Quad winding must give a -Z block-local normal so FACING=SOUTH rotates it to "
                        + "+Z (player side). Got normal = (" + n[0] + ", " + n[1] + ", " + n[2] + "). "
                        + "A +Z block-local normal would rotate to -Z world — player looks at "
                        + "the back face of the quad, text appears mirrored.");
    }

    @Test
    @DisplayName("Quad sits just outside the +Z lid face, inside the z-bias tolerance")
    void screenZIsJustPastInteriorFace() throws IOException {
        String src = Files.readString(RENDERER);
        double lidZMax = parseConst(src, "LID_Z_MAX");
        double screenZ = parseConst(src, "SCREEN_Z");

        assertTrue(screenZ > lidZMax,
                "SCREEN_Z (" + screenZ + ") must be greater than LID_Z_MAX (" + lidZMax + ").");

        double gap = screenZ - lidZMax;
        assertTrue(gap > 0.0005 && gap < 0.05,
                "z-bias gap (" + gap + ") must be > 0.0005 (avoid z-fighting) and "
                        + "< 0.05 (not float too far from the face). Got " + gap + ".");
    }

    @Test
    @DisplayName("UV mapping is canonical: BL→(0,1), TL→(0,0), TR→(1,0), BR→(1,1)")
    void uvMappingIsCanonical() throws IOException {
        List<Vertex> quad = parseEmitVertexCalls();
        double minX = quad.stream().mapToDouble(v -> v.x).min().orElseThrow();
        double maxX = quad.stream().mapToDouble(v -> v.x).max().orElseThrow();
        double minY = quad.stream().mapToDouble(v -> v.y).min().orElseThrow();
        double maxY = quad.stream().mapToDouble(v -> v.y).max().orElseThrow();

        for (Vertex v : quad) {
            boolean left = approx(v.x, minX);
            boolean right = approx(v.x, maxX);
            boolean bottom = approx(v.y, minY);
            boolean top = approx(v.y, maxY);
            String corner;
            double expectedU, expectedV;
            if (left && bottom)       { corner = "BL"; expectedU = 0; expectedV = 1; }
            else if (left && top)     { corner = "TL"; expectedU = 0; expectedV = 0; }
            else if (right && top)    { corner = "TR"; expectedU = 1; expectedV = 0; }
            else if (right && bottom) { corner = "BR"; expectedU = 1; expectedV = 1; }
            else { fail("vertex (" + v.x + ", " + v.y + ") doesn't match any corner"); return; }

            assertTrue(approx(v.u, expectedU) && approx(v.v, expectedV),
                    "corner " + corner + " at (" + v.x + ", " + v.y + ") has UV ("
                            + v.u + ", " + v.v + "), expected (" + expectedU + ", " + expectedV
                            + "). Don't flip U as a shortcut — if text is mirrored, the winding "
                            + "is wrong, not the UV.");
        }
    }

    @Test
    @DisplayName("Renderer documents the single-quad choice and the winding rationale")
    void rendererDocumentsTheFix() throws IOException {
        String src = Files.readString(RENDERER);
        assertTrue(src.toLowerCase().contains("winding"),
                "TinkerpadRenderer's javadoc must explain the winding-vs-normal rationale.");
        assertTrue(src.toLowerCase().contains("interior"),
                "TinkerpadRenderer's javadoc must say WHY we draw on the lid's interior face "
                        + "(where a real laptop's screen actually lives).");
    }

    /* ---------------------- source parsing helpers ---------------------- */

    private record Vertex(double x, double y, double z, double u, double v) {}

    /**
     * Parse {@code emitVertex(..., X, Y, SCREEN_Z, U, V, ...)} calls out of
     * the renderer. We resolve {@code SCREEN_X0/X1/Y0/Y1/Z} to their numeric
     * values from the class's own {@code static final} constants.
     */
    private static List<Vertex> parseEmitVertexCalls() throws IOException {
        String src= Files.readString(RENDERER, StandardCharsets.UTF_8);

        double x0 = parseConst(src, "SCREEN_X0");
        double x1 = parseConst(src, "SCREEN_X1");
        double y0 = parseConst(src, "SCREEN_Y0");
        double y1 = parseConst(src, "SCREEN_Y1");
        double z  = parseConst(src, "SCREEN_Z");

        Pattern p = Pattern.compile(
                "emitVertex\\([^,]+,[^,]+,\\s*(SCREEN_X[01]),\\s*(SCREEN_Y[01]),\\s*SCREEN_Z,\\s*"
                        + "([\\d.]+)f,\\s*([\\d.]+)f");
        Matcher m = p.matcher(src);

        List<Vertex> out = new ArrayList<>();
        while (m.find()) {
            double x = m.group(1).equals("SCREEN_X0") ? x0 : x1;
            double y = m.group(2).equals("SCREEN_Y0") ? y0 : y1;
            out.add(new Vertex(x, y, z, Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    /** Extract a {@code static final float NAME = 0.123f;} value from source. */
    private static double parseConst(String src, String name) {
        Matcher m = Pattern.compile(
                "static\\s+final\\s+float\\s+" + Pattern.quote(name)
                        + "\\s*=\\s*([^;]+);").matcher(src);
        assertTrue(m.find(), "constant " + name + " not found in TinkerpadRenderer source");
        return evalExpression(src, m.group(1).trim());
    }

    private static double evalExpression(String src, String expr) {
        String cleaned = expr.replaceAll("([\\d.]+)f\\b", "$1");
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            // fallthrough
        }
        Matcher m = Pattern.compile("([A-Z_]+)\\s*([+\\-])\\s*([A-Z_]+)").matcher(cleaned);
        if (m.matches()) {
            double a = parseConst(src, m.group(1));
            double b = parseConst(src, m.group(3));
            return m.group(2).equals("+") ? a + b : a - b;
        }
        throw new AssertionError("unsupported constant expression: " + expr);
    }

    private static double[] sub(Vertex a, Vertex b) {
        return new double[] {a.x - b.x, a.y - b.y, a.z - b.z};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
        };
    }

    private static boolean approx(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }
}
