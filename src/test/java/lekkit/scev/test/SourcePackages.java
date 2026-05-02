/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Centralised package-path resolution for source-grep tests.
 *
 * Most of our static-grep tests need to read the source for a given
 * scev package. Some files have moved between {@code src/main/java}
 * and {@code src/main/kotlin} during the Java→Kotlin migration; rather
 * than every test redeclaring "look in both", they go through this.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Path src = SourcePackages.find("lekkit/scev/blocks/DirectionalBlock")
 *         .orElseThrow();
 * }</pre>
 */
final class SourcePackages {
    private SourcePackages() {}

    /**
     * Roots to search, in order. {@code kotlin} first because new code
     * lives there; {@code java} second so we still find the few files
     * that haven't been ported (RVVM bindings, Mixins).
     */
    private static final List<Path> ROOTS = List.of(
            projectRoot().resolve("src/main/kotlin"),
            projectRoot().resolve("src/main/java"));

    /** Source-language extensions, in fall-through order (matches ROOTS). */
    private static final String[] EXTENSIONS = { ".kt", ".java" };

    static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    /**
     * Locate the source file for a class given its package path
     * ({@code lekkit/scev/blocks/DirectionalBlock}). Returns the first
     * match — `.kt` wins over `.java` if both exist.
     */
    static Optional<Path> find(String classPath) {
        for (Path root : ROOTS) {
            for (String ext : EXTENSIONS) {
                Path candidate = root.resolve(classPath + ext);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Walk every {@code .java} or {@code .kt} file under the given
     * package paths (relative to a source root). Used by tests that
     * scan multiple packages at once (e.g. {@link BlockRenderShapeTest}
     * walking blocks + blockentity).
     */
    static List<Path> walk(String... packagePaths) throws IOException {
        List<Path> all = new ArrayList<>();
        for (String pkg : packagePaths) {
            for (Path root : ROOTS) {
                Path dir = root.resolve(pkg);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> {
                        String s = p.toString();
                        return s.endsWith(".java") || s.endsWith(".kt");
                    }).forEach(all::add);
                }
            }
        }
        return all;
    }
}
