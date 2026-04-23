/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.cc

import dan200.computercraft.api.filesystem.Mount
import dan200.computercraft.api.filesystem.WritableMount
import dan200.computercraft.api.peripheral.IComputerAccess
import dan200.computercraft.api.peripheral.IPeripheral
import lekkit.scev.compat.cc.ScevCCHandlers
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the reflection hop `ScevCCHandlers` uses to reach the real
 * `IPeripheral` hiding behind a CC wired-modem wrapper. We fake the
 * private-field layout (`peripheralWrappers` on the modem, `peripheral`
 * on the wrapper) because building a real `WiredModemPeripheral` needs
 * a BlockEntity, a Level, and a WiredElement — none of which exist in
 * a plain JUnit process.
 *
 * If CC renames either field in a new version, these tests still pass
 * (they drive the shape we match) but production returns null from the
 * `underlyingRemotePeripheral` helper, and the RPC layer falls back to
 * the method-name-only describe path. The real safety net is that
 * fallback, not this test.
 */
class ScevCCHandlersRemoteReflectionTest {

    /** Stand-in for CC's private RemotePeripheralWrapper. Needs one field. */
    @Suppress("unused")
    private class FakeWrapper(val peripheral: IPeripheral)

    /** Stand-in for a WiredModemPeripheral: holds the wrappers map. */
    @Suppress("unused")
    private class FakeModem : IPeripheral {
        val peripheralWrappers: MutableMap<IComputerAccess, MutableMap<String, FakeWrapper>> =
            ConcurrentHashMap()
        override fun getType() = "modem"
        override fun equals(other: IPeripheral?) = other === this
    }

    /** IPeripheral that the fake modem "exposes" as its remote. */
    private class Printer : IPeripheral {
        override fun getType() = "printer"
        override fun equals(other: IPeripheral?) = other === this
    }

    @Test fun `surfaces the real peripheral through the wrapper`() {
        val modem = FakeModem()
        val computer = fakeComputer()
        val printer = Printer()
        modem.peripheralWrappers[computer] = mutableMapOf("printer_0" to FakeWrapper(printer))

        val resolved = ScevCCHandlers.underlyingRemotePeripheral(modem, computer, "printer_0")
        assertSame(printer, resolved)
    }

    @Test fun `unknown remote name returns null`() {
        val modem = FakeModem()
        val computer = fakeComputer()
        modem.peripheralWrappers[computer] = mutableMapOf("printer_0" to FakeWrapper(Printer()))

        assertNull(ScevCCHandlers.underlyingRemotePeripheral(modem, computer, "monitor_2"))
    }

    @Test fun `different computer key returns null`() {
        val modem = FakeModem()
        val owner = fakeComputer()
        val stranger = fakeComputer()
        modem.peripheralWrappers[owner] = mutableMapOf("printer_0" to FakeWrapper(Printer()))

        assertNull(ScevCCHandlers.underlyingRemotePeripheral(modem, stranger, "printer_0"))
    }

    @Test fun `cache is per-class so a second modem type still resolves`() {
        val a = FakeModem()
        val b = OtherFakeModem()
        val computer = fakeComputer()
        val pA = Printer()
        val pB = Printer()
        a.peripheralWrappers[computer] = mutableMapOf("printer_0" to FakeWrapper(pA))
        b.peripheralWrappers[computer] = mutableMapOf("printer_0" to FakeWrapper(pB))

        assertSame(pA, ScevCCHandlers.underlyingRemotePeripheral(a, computer, "printer_0"))
        assertSame(pB, ScevCCHandlers.underlyingRemotePeripheral(b, computer, "printer_0"))
    }

    @Test fun `missing field returns null rather than throwing`() {
        val brokenModem = object : IPeripheral {
            override fun getType() = "modem"
            override fun equals(other: IPeripheral?) = other === this
        }
        assertNull(ScevCCHandlers.underlyingRemotePeripheral(brokenModem, fakeComputer(), "anything"))
    }

    /** Distinct class — exercises the per-class field-lookup cache. */
    @Suppress("unused")
    private class OtherFakeModem : IPeripheral {
        val peripheralWrappers: MutableMap<IComputerAccess, MutableMap<String, FakeWrapper>> =
            ConcurrentHashMap()
        override fun getType() = "modem"
        override fun equals(other: IPeripheral?) = other === this
    }

    private fun fakeComputer(): IComputerAccess = object : IComputerAccess {
        override fun mount(desiredLocation: String, mount: Mount, driveName: String) = null
        override fun mountWritable(desiredLocation: String, mount: WritableMount, driveName: String) = null
        override fun unmount(location: String?) {}
        override fun getID() = 0
        override fun queueEvent(event: String, vararg arguments: Any?) {}
        override fun getAttachmentName() = "test"
        override fun getAvailablePeripherals() = emptyMap<String, IPeripheral>()
        override fun getAvailablePeripheral(name: String) = null
        override fun getMainThreadMonitor() = null
    }
}
