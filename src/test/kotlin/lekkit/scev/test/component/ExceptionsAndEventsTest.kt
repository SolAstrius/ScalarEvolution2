/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.component

import lekkit.scev.component.ComponentRole
import lekkit.scev.component.EventSource
import lekkit.scev.component.api.BusyException
import lekkit.scev.component.api.Errno
import lekkit.scev.component.api.InvalidArgumentException
import lekkit.scev.component.api.LockedException
import lekkit.scev.component.api.NoMediumException
import lekkit.scev.component.api.OutOfRangeException
import lekkit.scev.component.api.PeripheralException
import lekkit.scev.component.api.PeripheralGoneException
import lekkit.scev.component.api.ReadOnlyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExceptionsAndEventsTest {

    @Test fun `every exception subclass carries the expected errno`() {
        // Pins the Java-visible surface: Java callers rely on being
        // able to read `.errno()` (or `.getErrno()`) and see the
        // Linux-matching constant.
        assertEquals(Errno.ENOMEDIUM, NoMediumException().errno)
        assertEquals(Errno.EACCES,    LockedException().errno)
        assertEquals(Errno.EBUSY,     BusyException().errno)
        assertEquals(Errno.EROFS,     ReadOnlyException().errno)
        assertEquals(Errno.EINVAL,    InvalidArgumentException().errno)
        assertEquals(Errno.ERANGE,    OutOfRangeException().errno)
        assertEquals(Errno.ENODEV,    PeripheralGoneException().errno)
    }

    @Test fun `PeripheralException is a RuntimeException`() {
        val thrown = assertThrows(RuntimeException::class.java) {
            throw NoMediumException("custom msg")
        }
        // Can still downcast to the full typed form.
        val pex = thrown as PeripheralException
        assertEquals(Errno.ENOMEDIUM, pex.errno)
        assertEquals("custom msg", pex.message)
    }

    @Test fun `exception causes chain correctly`() {
        val original = IllegalStateException("root cause")
        val wrapped = LockedException("wrapped", cause = original)
        assertEquals("root cause", wrapped.cause?.message)
    }

    @Test fun `ComponentRole parses case-insensitively`() {
        assertEquals(ComponentRole.PROPERTY_BAG, ComponentRole.fromString("property_bag"))
        assertEquals(ComponentRole.PROPERTY_BAG, ComponentRole.fromString("PROPERTY_BAG"))
        assertEquals(ComponentRole.RPC,          ComponentRole.fromString("rpc"))
        assertEquals(ComponentRole.MOUNT,        ComponentRole.fromString("Mount"))
    }

    @Test fun `unknown role produces a helpful error`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ComponentRole.fromString("nonsense")
        }
        val msg = ex.message!!
        assertEquals(true, msg.contains("nonsense"))
        assertEquals(true, msg.contains("property_bag"))
        assertEquals(true, msg.contains("rpc"))
    }

    @Test fun `EventSource fans events to subscribers`() {
        val source = EventSource<String>()
        val received = mutableListOf<String>()
        val sub1 = source.subscribe { received += "a:$it" }
        val sub2 = source.subscribe { received += "b:$it" }

        assertEquals(2, source.subscriberCount)
        source.fire("one")
        source.fire("two")

        assertEquals(listOf("a:one", "b:one", "a:two", "b:two"), received)

        sub1.cancel()
        source.fire("three")
        // After sub1 cancelled, only b sees it.
        assertEquals(
            listOf("a:one", "b:one", "a:two", "b:two", "b:three"),
            received,
        )
    }

    @Test fun `subscription cancel is idempotent`() {
        val source = EventSource<Int>()
        val sub = source.subscribe { }
        sub.cancel()
        sub.cancel()    // must not throw
        assertEquals(0, source.subscriberCount)
    }

    @Test fun `misbehaving subscriber does not break the fan-out`() {
        val source = EventSource<Int>()
        val tail = mutableListOf<Int>()
        source.subscribe { throw RuntimeException("first subscriber is angry") }
        source.subscribe { tail += it }
        source.fire(42)
        assertEquals(listOf(42), tail, "later subscribers still get the event")
    }
}
