/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.client.model.obj.ObjMaterialLibrary;
import net.neoforged.neoforge.client.model.obj.ObjTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Real integration test: invokes NeoForge's {@code ObjModel.parse} against every
 * shipped OBJ, using NeoForge's own {@code ObjMaterialLibrary} for MTL parsing.
 *
 * <p>Unlike {@link BlockModelRenderingTest}, this test exercises the production
 * parser — not a re-implementation. The catch: {@code ObjLoader.INSTANCE} needs
 * {@code Minecraft.getInstance().getResourceManager()} at construction time,
 * which isn't available in a pure JUnit context. We work around that by:
 *
 * <ol>
 *   <li>Creating an {@code ObjLoader} with {@code Unsafe.allocateInstance} —
 *       no constructor invocation.</li>
 *   <li>Pre-populating its internal {@code materialCache} with an
 *       {@code ObjMaterialLibrary} that we parsed from disk ourselves.</li>
 *   <li>Installing that loader as the static {@code INSTANCE}, so
 *       {@code ObjModel.parse} finds cached MTLs instead of reaching for
 *       the resource manager.</li>
 *   <li>Invoking {@code ObjModel.parse} reflectively and inspecting the
 *       resulting model tree for empty/unmaterialed meshes.</li>
 * </ol>
 *
 * <p>This catches failure modes that static analysis misses: face index
 * out-of-bounds, malformed UV indices, mesh grouping bugs, quiet drops by
 * {@code ObjModel.ModelMesh.addQuads} — anything that would make the real
 * client render the block as invisible geometry.
 */
class ObjLoaderParseTest {

    private static final Path MAIN_ASSETS = projectRoot().resolve("src/main/resources/assets/scev");

    private static Path projectRoot() {
        String override = System.getProperty("scev.projectDir");
        return override != null ? Paths.get(override) : Paths.get("").toAbsolutePath();
    }

    private static final List<String> OBJ_BLOCKS = List.of(
            "workstation", "powermark", "tinkerpad", "crt_monitor",
            "vt100", "keyboard", "keyboard_mouse");

    /**
     * Parses each shipped OBJ through the real {@code ObjModel.parse}, then
     * asserts the resulting mesh tree has a non-null material attached to
     * every face.
     *
     * <p>If this fails, the client will render the block invisible — the exact
     * symptom we're trying to prevent.
     */
    @Test
    @DisplayName("NeoForge ObjModel.parse produces non-empty materialed mesh for every block")
    void objModelParseProducesMateriallyBoundMeshes() throws Exception {
        ObjLoaderStub.install();
        try {
            for (String block : OBJ_BLOCKS) {
                MeshReport report = parseAndIntrospect(block);
                assertTrue(report.totalFaces > 0,
                        "Block " + block + " parsed to zero faces — the OBJ is empty or parse failed.");
                assertEquals(0, report.unmateriledFaces,
                        "Block " + block + " has " + report.unmateriledFaces
                                + " face(s) in meshes with null material. "
                                + "ObjModel.ModelMesh.addQuads skips these at bake time — the block "
                                + "would render as invisible geometry. Total faces: " + report.totalFaces);
                assertEquals(0, report.meshesWithNoFaces,
                        "Block " + block + " has " + report.meshesWithNoFaces
                                + " mesh(es) with zero faces — suggests the parser is creating empty "
                                + "meshes, likely due to mis-ordered usemtl directives.");
            }
        } finally {
            ObjLoaderStub.uninstall();
        }
    }

    /**
     * Parses an OBJ through {@code ObjModel.parse} and walks its private
     * {@code parts} tree to count faces — both renderable (mesh.mat != null)
     * and dropped (mesh.mat == null).
     */
    private static MeshReport parseAndIntrospect(String blockName) throws Exception {
        Class<?> objModelClass = Class.forName("net.neoforged.neoforge.client.model.obj.ObjModel");
        Class<?> settingsClass = Class.forName("net.neoforged.neoforge.client.model.obj.ObjModel$ModelSettings");

        // ObjModel.ModelSettings is a record: (ResourceLocation, boolean, boolean, boolean, boolean, String)
        Class<?> rlClass = Class.forName("net.minecraft.resources.ResourceLocation");
        Method parseRl = rlClass.getMethod("parse", String.class);
        Object rl = parseRl.invoke(null, "scev:models/block/" + blockName + ".obj");

        Constructor<?> settingsCtor = settingsClass.getDeclaredConstructor(
                rlClass, boolean.class, boolean.class, boolean.class, boolean.class, String.class);
        Object settings = settingsCtor.newInstance(rl, false, true, true, false, null);

        // ObjModel.parse(ObjTokenizer, ModelSettings)
        Method parseMethod = objModelClass.getDeclaredMethod("parse", ObjTokenizer.class, settingsClass);
        parseMethod.setAccessible(true);

        Path objPath = MAIN_ASSETS.resolve("models/block").resolve(blockName + ".obj");
        Object model;
        try (InputStream is = Files.newInputStream(objPath); ObjTokenizer tok = new ObjTokenizer(is)) {
            model = parseMethod.invoke(null, tok, settings);
        }

        // Walk the model's `parts` field (Multimap<String, ModelGroup>) and
        // count faces per mesh. ModelGroup and ModelMesh are private inner
        // classes, so we use reflection all the way down.
        Field partsField = objModelClass.getDeclaredField("parts");
        partsField.setAccessible(true);
        Object multimap = partsField.get(model);
        // Multimap#values() is defined on the Multimap interface but the
        // concrete class is in Guava's internals, which aren't open for
        // reflection. Find the method on the Multimap interface directly.
        Class<?> multimapIface = Class.forName("com.google.common.collect.Multimap");
        Method valuesMethod = multimapIface.getMethod("values");
        Collection<?> parts = (Collection<?>) valuesMethod.invoke(multimap);

        MeshReport report = new MeshReport();
        for (Object part : parts) {
            walkPart(part, report);
        }
        return report;
    }

    /**
     * Recursively walks a {@code ModelObject} (or its {@code ModelGroup}
     * subclass) counting faces per mesh.
     */
    private static void walkPart(Object part, MeshReport report) throws Exception {
        Class<?> partClass = part.getClass();
        // ModelObject.meshes (List<ModelMesh>)
        Field meshesField = findField(partClass, "meshes");
        meshesField.setAccessible(true);
        List<?> meshes = (List<?>) meshesField.get(part);
        for (Object mesh : meshes) {
            Field matField = mesh.getClass().getDeclaredField("mat");
            matField.setAccessible(true);
            Object mat = matField.get(mesh);

            Field facesField = mesh.getClass().getDeclaredField("faces");
            facesField.setAccessible(true);
            List<?> faces = (List<?>) facesField.get(mesh);

            if (faces.isEmpty()) {
report.meshesWithNoFaces++;
                continue;
            }
            report.totalFaces += faces.size();
            if (mat == null) {
                report.unmateriledFaces += faces.size();
            }
        }

        // ModelGroup adds a `parts` Multimap of child ModelObjects.
        Field groupParts = findFieldOrNull(partClass, "parts");
        if (groupParts != null) {
            groupParts.setAccessible(true);
            Object childMultimap = groupParts.get(part);
            if (childMultimap != null) {
                Class<?> multimapIface = Class.forName("com.google.common.collect.Multimap");
                Method cv = multimapIface.getMethod("values");
                Collection<?> children = (Collection<?>) cv.invoke(childMultimap);
                for (Object child : children) {
                    walkPart(child, report);
                }
            }
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new AssertionError("No field " + name + " on " + cls.getName() + " or its supers");
    }

    private static Field findFieldOrNull(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static final class MeshReport {
        int totalFaces;
        int unmateriledFaces;
        int meshesWithNoFaces;
    }

    // ------------------------------------------------------------------------
    // ObjLoader stub: allocates an ObjLoader without calling its constructor
    // (which needs Minecraft.getInstance()) and pre-populates its
    // materialCache so ObjModel.parse's mtllib lookups hit the cache instead
    // of reaching for the resource manager.
    //
    // The one complication: Class.forName triggers ObjLoader's static init,
    // which runs `public static ObjLoader INSTANCE = new ObjLoader()`, which
    // NPEs because `Minecraft.getInstance()` is null in a JUnit context. We
    // work around that by first installing a bare-bones Minecraft stub (also
    // via Unsafe.allocateInstance) so the constructor can finish, even
    // though the resulting `manager` is null — we never actually use it.
    // ------------------------------------------------------------------------

    private static final class ObjLoaderStub {
        private static Object previousInstance;
        private static Object previousMinecraft;

        static void install() throws Exception {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            // 1. Stub Minecraft.instance so ObjLoader's ctor doesn't NPE on
            //    Minecraft.getInstance().getResourceManager().
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mcStub = allocateInstance.invoke(unsafe, mcClass);
            // Minecraft's singleton field is named `instance` (private static).
            Field mcInstance = findStaticField(mcClass, "instance");
            mcInstance.setAccessible(true);
            previousMinecraft = mcInstance.get(null);
            mcInstance.set(null, mcStub);

            // 2. Load ObjLoader (now the ctor runs cleanly, setting manager=null).
            Class<?> loaderClass = Class.forName("net.neoforged.neoforge.client.model.obj.ObjLoader");

            // 3. Build a fresh stub with a pre-populated materialCache, swap it
            //    in as the static INSTANCE. This avoids relying on whatever
            //    the auto-initialized INSTANCE's fields contain.
            Object stub = allocateInstance.invoke(unsafe, loaderClass);
            setField(stub, "modelCache", new ConcurrentHashMap<>());

            Map<Object, ObjMaterialLibrary> materialCache = new ConcurrentHashMap<>();
            Path defaultMtl = MAIN_ASSETS.resolve("models/block/default.mtl");
            ObjMaterialLibrary lib;
            try (InputStream is = Files.newInputStream(defaultMtl); ObjTokenizer tok = new ObjTokenizer(is)) {
                lib = new ObjMaterialLibrary(tok);
            }
            Class<?> rlClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Method fromNsAndPath = rlClass.getMethod("fromNamespaceAndPath", String.class, String.class);
            Object rl = fromNsAndPath.invoke(null, "scev", "models/block/default.mtl");
            materialCache.put(rl, lib);
            setField(stub, "materialCache", materialCache);

            Field instanceField = loaderClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            previousInstance = instanceField.get(null);
            instanceField.set(null, stub);
        }

        static void uninstall() throws Exception {
            Class<?> loaderClass = Class.forName("net.neoforged.neoforge.client.model.obj.ObjLoader");
            Field instanceField = loaderClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            instanceField.set(null, previousInstance);
            previousInstance = null;

            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Field mcInstance = findStaticField(mcClass, "instance");
            mcInstance.setAccessible(true);
            mcInstance.set(null, previousMinecraft);
            previousMinecraft = null;
        }

        private static Field findStaticField(Class<?> cls, String name) throws NoSuchFieldException {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getName().equals(name) && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    return f;
                }
            }
            throw new NoSuchFieldException(cls.getName() + "." + name);
        }

        private static void setField(Object target, String name, Object value) throws Exception {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        }
    }
}
