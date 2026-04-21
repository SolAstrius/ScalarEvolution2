/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lekkit.scev.client.sections.CreativeModeInventoryAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards against a specific crash class: Mixin reserves every package
 * declared in a {@code mixins.json} as "mixin-owned" and throws
 * {@code IllegalClassLoadError} at runtime when non-mixin code tries to
 * reference types inside it. Duck interfaces (accessed via
 * {@code (IFace) target} casts from regular client code) must therefore
 * live <em>outside</em> any such package.
 *
 * <p>Originating incident: {@code CreativeModeInventoryAccess} was placed
 * in {@code lekkit.scev.mixin.creative_tab}; the client crashed on first
 * tick when {@code ScevCreativeTab.renderBanners} attempted the
 * {@code (IFace) screen} cast. Moved to {@code lekkit.scev.client.sections}.
 *
 * <p>This test is policy enforcement — it would not have fired before the
 * original fix because the interface was in the forbidden package. Going
 * forward it fires if anyone reverts the move.
 */
class MixinPackageHygieneTest {

    private static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    @Test
    @DisplayName("scev.mixins.json declares a mixin-only package prefix")
    void mixinConfigDeclaresPackagePrefix() throws IOException {
        String prefix = mixinPackagePrefix();
        assertNotNull(prefix, "scev.mixins.json must set a `package` field");
        assertFalse(prefix.isBlank(), "mixin package prefix must be non-empty");
    }

    @Test
    @DisplayName("CreativeModeInventoryAccess is outside the mixin-owned package")
    void duckInterfaceOutsideMixinPackage() throws IOException {
        String mixinPrefix = mixinPackagePrefix();
        String ifacePkg = CreativeModeInventoryAccess.class.getPackageName();
        assertFalse(
                ifacePkg.equals(mixinPrefix) || ifacePkg.startsWith(mixinPrefix + "."),
                "Duck interface " + CreativeModeInventoryAccess.class.getName()
                        + " lives in " + ifacePkg + " which is inside the Mixin-owned "
                        + "package " + mixinPrefix + ". Non-mixin code casting to this "
                        + "interface (e.g. ScevCreativeTab.renderBanners) will crash at "
                        + "runtime with IllegalClassLoadError. Move the interface to a "
                        + "non-mixin package like lekkit.scev.client.sections.");
    }

    /** Reads {@code scev.mixins.json} and returns the declared {@code package} prefix. */
    private static String mixinPackagePrefix() throws IOException {
        Path config = projectRoot().resolve("src/main/resources/scev.mixins.json");
        assertTrue(Files.exists(config), "mixin config missing at " + config);
        JsonObject json = JsonParser.parseString(
                Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(json.has("package"), "scev.mixins.json lacks `package` field");
        return json.get("package").getAsString();
    }
}
