/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.cc

import dan200.computercraft.api.lua.IArguments
import dan200.computercraft.api.lua.ILuaContext
import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.api.lua.LuaFunction
import dan200.computercraft.api.lua.MethodResult
import dan200.computercraft.api.lua.ObjectArguments
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IPeripheral
import kotlinx.coroutines.test.runTest
import lekkit.scev.compat.cc.ScevLuaContext
import lekkit.scev.compat.cc.ScevPeripheralMethods
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Validates that `signaturesFor` + `ParamSpec` + `ReturnShape` capture
 * everything reachable from reflection in a way that matches what the
 * `describe` RPC needs to emit.
 *
 * These are value-equality tests — no dispatch runs. Dispatch is
 * covered separately in [ScevPeripheralMethodsTest].
 */
class MethodSignatureTest {

    @BeforeEach fun clear() = ScevPeripheralMethods.clearCacheForTests()

    // ---------------- fakes ----------------

    enum class Colour { RED, GREEN, BLUE }

    class Inherited : IPeripheral {
        override fun getType() = "child"
        override fun equals(other: IPeripheral?) = other === this

        @LuaFunction
        fun childMethod(): String = "child"
    }

    abstract class ParentShape : IPeripheral {
        override fun equals(other: IPeripheral?) = other === this

        @LuaFunction
        fun parentMethod(x: Int): Int = x * 2
    }

    class InheritsParent : ParentShape() {
        override fun getType() = "grandchild"
        @LuaFunction
        fun ownMethod(): Boolean = true
    }

    class Covers : IPeripheral {
        override fun getType() = "covers"
        override fun equals(other: IPeripheral?) = other === this

        @LuaFunction
        fun noParams() {
        }

        @LuaFunction
        fun primitives(a: Int, b: Double, c: Boolean, d: String): Int = a

        @LuaFunction
        fun withOptional(a: Optional<String>): String = a.orElse("x")

        @LuaFunction
        fun withEnum(c: Colour): String = c.name

        @LuaFunction
        fun withOptEnum(c: Optional<Colour>): String = c.map { it.name }.orElse("none")

        @LuaFunction
        fun multiReturn(): Array<Any> = arrayOf(1, 2, 3)

        @LuaFunction
        fun dynamicReturn(): MethodResult = MethodResult.of("x")

        @LuaFunction
        fun withInjected(c: IComputerAccess?, ctx: ILuaContext, args: IArguments, a: Int): Int = a

        @LuaFunction(mainThread = true)
        fun mainThreadMethod(): Int = 0

        @LuaFunction(unsafe = true)
        fun unsafeMethod(a: IArguments): Int = a.count()

        @LuaFunction(value = ["aliasA", "aliasB"])
        fun aliased(): String = "aliased"
    }

    // ---------------- param types ----------------

    @Test fun `primitive params get correct luaType`() {
        val sig = ScevPeripheralMethods.signaturesFor(Covers())["primitives"]
        assertNotNull(sig)
        assertEquals(4, sig!!.params.size)
        assertEquals("number", sig.params[0].luaType)
        assertEquals("number", sig.params[1].luaType)
        assertEquals("boolean", sig.params[2].luaType)
        assertEquals("string", sig.params[3].luaType)
        for (p in sig.params) assertFalse(p.optional)
    }

    @Test fun `Optional params are marked optional with inner luaType`() {
        val sig = ScevPeripheralMethods.signaturesFor(Covers())["withOptional"]!!
        assertEquals(1, sig.params.size)
        assertEquals("string", sig.params[0].luaType)
        assertTrue(sig.params[0].optional)
    }

    @Test fun `Enum params carry enumValues as lowercased names`() {
        val sig = ScevPeripheralMethods.signaturesFor(Covers())["withEnum"]!!
        assertEquals(1, sig.params.size)
        assertEquals("string", sig.params[0].luaType)
        assertEquals(listOf("red", "green", "blue"), sig.params[0].enumValues)
        assertFalse(sig.params[0].optional)
    }

    @Test fun `Optional enum params are optional with enumValues`() {
        val sig = ScevPeripheralMethods.signaturesFor(Covers())["withOptEnum"]!!
        assertEquals(1, sig.params.size)
        assertEquals("string", sig.params[0].luaType)
        assertEquals(listOf("red", "green", "blue"), sig.params[0].enumValues)
        assertTrue(sig.params[0].optional)
    }

    @Test fun `injected CC params are stripped from params list`() {
        val sig = ScevPeripheralMethods.signaturesFor(Covers())["withInjected"]!!
        // IComputerAccess, ILuaContext, IArguments all dropped — only `a` remains.
        assertEquals(1, sig.params.size)
        assertEquals("number", sig.params[0].luaType)
    }

    // ---------------- return shape ----------------

    @Test fun `return shape maps correctly`() {
        val sigs = ScevPeripheralMethods.signaturesFor(Covers())
        assertEquals(ScevPeripheralMethods.ReturnShape.NONE, sigs["noParams"]!!.returnShape)
        assertEquals(ScevPeripheralMethods.ReturnShape.ONE, sigs["primitives"]!!.returnShape)
        assertEquals(ScevPeripheralMethods.ReturnShape.MANY, sigs["multiReturn"]!!.returnShape)
        assertEquals(ScevPeripheralMethods.ReturnShape.DYNAMIC, sigs["dynamicReturn"]!!.returnShape)
    }

    // ---------------- @LuaFunction flags ----------------

    @Test fun `mainThread and unsafe flags are surfaced`() {
        val sigs = ScevPeripheralMethods.signaturesFor(Covers())
        assertTrue(sigs["mainThreadMethod"]!!.mainThread)
        assertFalse(sigs["mainThreadMethod"]!!.unsafe)
        assertTrue(sigs["unsafeMethod"]!!.unsafe)
        assertFalse(sigs["unsafeMethod"]!!.mainThread)
    }

    @Test fun `aliases are captured under every alias key`() {
        val sigs = ScevPeripheralMethods.signaturesFor(Covers())
        // Both alias names appear as map keys.
        assertTrue("aliasA" in sigs.keys)
        assertTrue("aliasB" in sigs.keys)
        // The 'aliased' method name is NOT a key when explicit aliases set.
        assertFalse("aliased" in sigs.keys)
        // Each alias entry lists both aliases.
        assertEquals(listOf("aliasA", "aliasB"), sigs["aliasA"]!!.aliases)
        assertEquals(listOf("aliasA", "aliasB"), sigs["aliasB"]!!.aliases)
        // The signature's `name` reflects which alias this key is for.
        assertEquals("aliasA", sigs["aliasA"]!!.name)
        assertEquals("aliasB", sigs["aliasB"]!!.name)
    }

    // ---------------- declaring class ----------------

    @Test fun `declaredBy simple class name tracks the hierarchy`() {
        val childSigs = ScevPeripheralMethods.signaturesFor(Inherited())
        assertEquals("Inherited", childSigs["childMethod"]!!.declaredBy)

        val grandchildSigs = ScevPeripheralMethods.signaturesFor(InheritsParent())
        assertEquals("InheritsParent", grandchildSigs["ownMethod"]!!.declaredBy)
        // Parent's @LuaFunction methods are inherited and the simple
        // name is still the parent's — tells the guest which class
        // originally defined the method.
        assertEquals("ParentShape", grandchildSigs["parentMethod"]!!.declaredBy)
    }

    // ---------------- signatureString formatting ----------------

    @Test fun `signatureString renders cleanly`() {
        val sigs = ScevPeripheralMethods.signaturesFor(Covers())
        assertEquals("noParams() -> nil", sigs["noParams"]!!.signatureString())
        assertEquals(
            "primitives(arg0: number, arg1: number, arg2: boolean, arg3: string) -> value",
            sigs["primitives"]!!.signatureString(),
        )
        assertEquals("withOptional(arg0: string?) -> value", sigs["withOptional"]!!.signatureString())
        // Enum rendering: the trailing ` ∈ {red|green|blue}` section.
        assertTrue(
            sigs["withEnum"]!!.signatureString().contains("∈ {red|green|blue}"),
            sigs["withEnum"]!!.signatureString(),
        )
        assertTrue(sigs["mainThreadMethod"]!!.signatureString().contains("[mainThread]"))
        assertTrue(sigs["unsafeMethod"]!!.signatureString().contains("[unsafe]"))
    }

    // ---------------- enriched errors ----------------

    class ThrowsForBadArg : IPeripheral {
        override fun getType() = "errs"
        override fun equals(other: IPeripheral?) = other === this

        @LuaFunction
        fun doIt(x: Int): Int = x
    }

    @Test fun `LuaException from arg coercion is appended with signature`() = runTest {
        val ex = try {
            // Pass a string where an int is required → IArguments.getInt throws.
            ScevPeripheralMethods.dispatch(
                ThrowsForBadArg(),
                fakeComputer(),
                ScevLuaContext(),
                "doIt",
                ObjectArguments("not a number"),
            )
            null
        } catch (e: LuaException) { e }
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains(" -- doIt(arg0: number) -> value"), ex.message)
    }

    class ThrowsLuaBody : IPeripheral {
        override fun getType() = "errs2"
        override fun equals(other: IPeripheral?) = other === this

        @LuaFunction
        fun boom(): Int = throw LuaException("nope")
    }

    @Test fun `LuaException thrown by body is appended with signature`() = runTest {
        val ex = try {
            ScevPeripheralMethods.dispatch(
                ThrowsLuaBody(),
                fakeComputer(),
                ScevLuaContext(),
                "boom",
                ObjectArguments(),
            )
            null
        } catch (e: LuaException) { e }
        assertNotNull(ex)
        assertTrue(ex!!.message!!.startsWith("nope"), ex.message)
        assertTrue(ex.message!!.contains("boom() -> value"), ex.message)
    }

    // ---------------- helpers ----------------

    private fun fakeComputer(): IComputerAccess = object : IComputerAccess {
        override fun mount(desiredLocation: String, mount: dan200.computercraft.api.filesystem.Mount, driveName: String) = null
        override fun mountWritable(desiredLocation: String, mount: dan200.computercraft.api.filesystem.WritableMount, driveName: String) = null
        override fun unmount(location: String?) {}
        override fun getID() = 0
        override fun queueEvent(event: String, vararg arguments: Any?) {}
        override fun getAttachmentName() = "test"
        override fun getAvailablePeripherals() = emptyMap<String, IPeripheral>()
        override fun getAvailablePeripheral(name: String) = null
        override fun getMainThreadMonitor() = null
    }
}
