/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.cc

import dan200.computercraft.api.lua.Coerced
import dan200.computercraft.api.lua.IArguments
import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.api.lua.LuaFunction
import dan200.computercraft.api.lua.MethodResult
import dan200.computercraft.api.lua.ObjectArguments
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IDynamicPeripheral
import dan200.computercraft.api.peripheral.IPeripheral
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import lekkit.scev.compat.cc.ScevLuaContext
import lekkit.scev.compat.cc.ScevPeripheralMethods
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Exercises the dispatcher across the parameter/return-type matrix it
 * claims to cover. Uses hand-rolled fake peripherals so the tests run in
 * a pure-JVM context (no Minecraft, no CC runtime).
 */
class ScevPeripheralMethodsTest {
    private val ctx = ScevLuaContext()

    // IComputerAccess isn't directly instantiable (it's an interface),
    // and implementing all of it is overkill for dispatch tests. The
    // dispatcher only forwards the reference; null works as long as no
    // peripheral method we test dereferences it.
    private val computer: IComputerAccess? = null

    @BeforeEach fun clear() = ScevPeripheralMethods.clearCacheForTests()

    // ---------------- fakes ----------------

    /** Method signatures we want to cover. */
    class Fake : IPeripheral {
        override fun getType() = "fake"
        override fun equals(other: IPeripheral?) = other === this

        var voidCalled = false
        var intArg = 0
        var doubleArg = 0.0
        var stringArg = ""
        var boolArg = false
        var optStringCalled: Optional<String>? = null
        var coercedCalled: String? = null
        var computerRef: IComputerAccess? = null

        @LuaFunction
        fun noArgsVoid() {
            voidCalled = true
        }

        @LuaFunction
        fun echoInt(x: Int): Int = x + 1

        @LuaFunction
        fun echoLong(x: Long): Long = x + 1L

        @LuaFunction
        fun echoDouble(x: Double): Double = x * 2.0

        @LuaFunction
        fun echoBool(b: Boolean): Boolean = !b

        @LuaFunction
        fun echoString(s: String): String = s + "!"

        @LuaFunction
        fun takeIntAndString(n: Int, s: String): String = s.repeat(n)

        @LuaFunction
        fun takeOpt(s: Optional<String>): String {
            optStringCalled = s
            return s.orElse("default")
        }

        @LuaFunction
        fun takeCoerced(s: Coerced<String>): String {
            coercedCalled = s.value
            return s.value.uppercase()
        }

        @LuaFunction
        fun withComputer(c: IComputerAccess?, tag: String): String {
            computerRef = c
            return "c=$c tag=$tag"
        }

        @LuaFunction
        fun withArgs(a: IArguments): Int = a.count()

        @LuaFunction
        fun multiReturn(): Array<Any> = arrayOf(1L, "two", true)

        @LuaFunction
        fun passThrough(): MethodResult = MethodResult.of("already-a-result")

        @LuaFunction
        fun throwsLua(): Int {
            throw LuaException("expected failure")
        }

        @LuaFunction(value = ["aliasA", "aliasB"])
        fun aliased(): String = "via alias"

        @LuaFunction(mainThread = true)
        fun mainThreadOp(): String = "ran on (pretend) main thread"
    }

    /** Dynamic peripheral, methods reported at runtime. */
    class DynFake : IDynamicPeripheral {
        override fun getType() = "dyn"
        override fun equals(other: IPeripheral?) = other === this
        override fun getMethodNames(): Array<String> = arrayOf("alpha", "beta")
        override fun callMethod(
            computer: IComputerAccess,
            context: dan200.computercraft.api.lua.ILuaContext,
            method: Int,
            arguments: IArguments,
        ): MethodResult = when (method) {
            0 -> MethodResult.of("alpha-result", arguments.count())
            1 -> MethodResult.of("beta-result")
            else -> throw LuaException("bad index $method")
        }
    }

    // ---------------- enumeration ----------------

    @Test fun `methodNames surfaces all LuaFunction names including aliases`() {
        val names = ScevPeripheralMethods.methodNames(Fake())
        // aliased contributes two names, not its method name.
        assertTrue("noArgsVoid" in names)
        assertTrue("echoInt" in names)
        assertTrue("aliasA" in names)
        assertTrue("aliasB" in names)
        assertTrue("aliased" !in names, "method.name suppressed when explicit names set")
    }

    @Test fun `methodNames for dynamic peripheral uses getMethodNames`() {
        val names = ScevPeripheralMethods.methodNames(DynFake())
        assertEquals(setOf("alpha", "beta"), names)
    }

    // ---------------- primitive coercion ----------------

    @Test fun `void method returns empty MethodResult`() = runTest {
        val p = Fake()
        val r = dispatch(p, "noArgsVoid")
        assertNull(r.result, "empty MethodResult has null getResult")
        assertTrue(p.voidCalled)
    }

    @Test fun `int argument and return`() = runTest {
        val r = dispatch(Fake(), "echoInt", 41L)
        assertArrayEquals(arrayOf<Any?>(42), r.result)
    }

    @Test fun `long argument and return`() = runTest {
        val r = dispatch(Fake(), "echoLong", 100L)
        assertArrayEquals(arrayOf<Any?>(101L), r.result)
    }

    @Test fun `double argument and return`() = runTest {
        val r = dispatch(Fake(), "echoDouble", 1.5)
        assertArrayEquals(arrayOf<Any?>(3.0), r.result)
    }

    @Test fun `boolean argument and return`() = runTest {
        val r = dispatch(Fake(), "echoBool", true)
        assertArrayEquals(arrayOf<Any?>(false), r.result)
    }

    @Test fun `string argument and return`() = runTest {
        val r = dispatch(Fake(), "echoString", "hi")
        assertArrayEquals(arrayOf<Any?>("hi!"), r.result)
    }

    @Test fun `multiple ordered primitive args`() = runTest {
        val r = dispatch(Fake(), "takeIntAndString", 3L, "ab")
        assertArrayEquals(arrayOf<Any?>("ababab"), r.result)
    }

    // ---------------- Optional / Coerced ----------------

    @Test fun `Optional argument present`() = runTest {
        val p = Fake()
        val r = dispatch(p, "takeOpt", "hello")
        assertArrayEquals(arrayOf<Any?>("hello"), r.result)
        assertEquals(Optional.of("hello"), p.optStringCalled)
    }

    @Test fun `Optional argument absent`() = runTest {
        val p = Fake()
        val r = dispatch(p, "takeOpt")  // no args
        assertArrayEquals(arrayOf<Any?>("default"), r.result)
        assertEquals(Optional.empty<String>(), p.optStringCalled)
    }

    @Test fun `Coerced String argument`() = runTest {
        val p = Fake()
        val r = dispatch(p, "takeCoerced", 42L) // getStringCoerced accepts number
        assertArrayEquals(arrayOf<Any?>("42"), r.result)
        assertEquals("42", p.coercedCalled)
    }

    // ---------------- injected params ----------------

    @Test fun `IComputerAccess injected without consuming an IArguments index`() = runTest {
        val p = Fake()
        // "tag" is the first IArguments value at index 0. If the
        // dispatcher got the index bookkeeping wrong, it would try to
        // getString(1) and throw. Assert the tag landed correctly and
        // that the computer reference reached the method body.
        val r = dispatch(p, "withComputer", "hello")
        val single = r.result?.singleOrNull() as? String
        assertTrue(single != null && single.endsWith("tag=hello"), "got $single")
        assertTrue(p.computerRef != null, "computer should have been injected")
    }

    @Test fun `IArguments passthrough`() = runTest {
        val r = dispatch(Fake(), "withArgs", 10L, 20L, 30L)
        // Method returns args.count() → 3.
        assertArrayEquals(arrayOf<Any?>(3), r.result)
    }

    // ---------------- return shapes ----------------

    @Test fun `Object array return maps to multi-value result`() = runTest {
        val r = dispatch(Fake(), "multiReturn")
        assertArrayEquals(arrayOf<Any?>(1L, "two", true), r.result)
    }

    @Test fun `MethodResult return passed through`() = runTest {
        val r = dispatch(Fake(), "passThrough")
        assertArrayEquals(arrayOf<Any?>("already-a-result"), r.result)
    }

    // ---------------- errors ----------------

    @Test fun `unknown method throws LuaException`() = runTest {
        // JUnit's assertThrows lambda isn't a coroutine body, so
        // capture the suspend call's exception via try/catch instead.
        val ex = try {
            dispatch(Fake(), "doesNotExist")
            null
        } catch (e: LuaException) { e }
        assertTrue(ex != null && ex.message!!.contains("doesNotExist"))
    }

    @Test fun `LuaException thrown by method propagates`() = runTest {
        val ex = try {
            dispatch(Fake(), "throwsLua")
            null
        } catch (e: LuaException) { e }
        // v2 dispatcher enriches thrown LuaExceptions with the
        // method signature after ` -- `. The original message is
        // preserved at the head so guest parsers can strip the hint.
        assertTrue(ex?.message!!.startsWith("expected failure"), ex.message)
        assertTrue(ex.message!!.contains("throwsLua"), ex.message)
    }

    @Test fun `aliased names all dispatch to the same method`() = runTest {
        val p = Fake()
        assertArrayEquals(arrayOf<Any?>("via alias"), dispatch(p, "aliasA").result)
        assertArrayEquals(arrayOf<Any?>("via alias"), dispatch(p, "aliasB").result)
    }

    @Test fun `mainThread method executes synchronously for now`() = runTest {
        // scev dispatch is already on the server tick thread, so
        // mainThread=true is a no-op in the current impl. Pins the
        // contract so a future change that adds off-thread dispatch
        // flags the test.
        val r = dispatch(Fake(), "mainThreadOp")
        assertArrayEquals(arrayOf<Any?>("ran on (pretend) main thread"), r.result)
    }

    // ---------------- dynamic peripheral ----------------

    @Test fun `dynamic peripheral dispatches by name to callMethod`() = runTest {
        val r = ScevPeripheralMethods.dispatch(DynFake(), fakeComputer(), ctx, "alpha", ObjectArguments(1L, 2L))
        assertArrayEquals(arrayOf<Any?>("alpha-result", 2), r.result)
    }

    @Test fun `dynamic peripheral unknown method fails`() = runTest {
        val ex = try {
            ScevPeripheralMethods.dispatch(DynFake(), fakeComputer(), ctx, "gamma", ObjectArguments())
            null
        } catch (e: LuaException) { e }
        assertTrue(ex != null, "expected LuaException")
    }

    // ---------------- yielding MethodResult ----------------

    @Test fun `yielding method resumes after awaitEvent`() = runTest {
        // The peripheral's first call yields on pullEvent("scev_test");
        // the callback resumes with the event args and returns a plain
        // result. Drives ScevCCComputer's event channel to confirm the
        // suspend-and-resume path lands the expected result.
        val machineUuid = java.util.UUID.randomUUID()
        val computer = lekkit.scev.compat.cc.ScevCCComputer(machineUuid)
        val peripheral = YieldingFake()

        // Launch dispatch in the test scheduler; queue the matching
        // event; assert the coroutine completes with the expected
        // result. runTest advances the virtual clock, so the suspend
        // resolves deterministically.
        val job = async {
            ScevPeripheralMethods.dispatch(peripheral, computer, ctx, "waitAndReturn", ObjectArguments())
        }
        // Yield once so the peripheral enters its pullEvent suspension
        // before we inject. Without this, trySend could race and the
        // event arrives before the dispatcher has started awaiting.
        yield()
        computer.injectEventFromGuest("scev_test", listOf("payload"))
        val r = job.await()
        assertArrayEquals(arrayOf<Any?>("resumed with payload"), r.result)
    }

    class YieldingFake : IPeripheral {
        override fun getType() = "yielding"
        override fun equals(other: IPeripheral?) = other === this

        @LuaFunction
        fun waitAndReturn(): MethodResult =
            MethodResult.pullEvent("scev_test") { response ->
                // response[0] = event_name, response[1..] = args.
                val payload = response.getOrNull(1) as? String ?: "(missing)"
                MethodResult.of("resumed with $payload")
            }
    }

    // ---------------- helpers ----------------

    private suspend fun dispatch(p: IPeripheral, name: String, vararg args: Any?): MethodResult {
        return ScevPeripheralMethods.dispatch(p, fakeComputer(), ctx, name, ObjectArguments(*args))
    }

    /**
     * DynFake.callMethod expects a non-null computer. For the static
     * dispatcher tests we use null since we don't touch the computer;
     * for dynamic tests we inject a minimal fake.
     */
    private fun fakeComputer(): IComputerAccess =
        computer ?: object : IComputerAccess {
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
