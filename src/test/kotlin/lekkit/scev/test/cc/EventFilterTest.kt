/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.cc

import lekkit.scev.compat.cc.ScevCCComputer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Subscription filter on [ScevCCComputer] in isolation. Whether the
 * filter is applied at the wire boundary is covered separately; here
 * we only assert the state-transition table:
 *
 *   default      -> wildcard (null filter)
 *   subscribe(*) -> wildcard
 *   subscribe(names) -> whitelist
 *   subscribe(more) -> union with previous
 *   unsubscribe(some) -> remove
 *   unsubscribe(*) -> empty (block all)
 */
class EventFilterTest {
    private fun freshComputer() = ScevCCComputer(UUID.randomUUID())

    @Test fun `default filter is wildcard`() {
        val c = freshComputer()
        assertNull(c.eventFilterSnapshot())
    }

    @Test fun `subscribe with names switches to whitelist`() {
        val c = freshComputer()
        val f = c.subscribeEvents(listOf("redstone", "peripheral_attach"))
        assertEquals(setOf("redstone", "peripheral_attach"), f)
        assertEquals(setOf("redstone", "peripheral_attach"), c.eventFilterSnapshot())
    }

    @Test fun `subscribe extends an existing whitelist`() {
        val c = freshComputer()
        c.subscribeEvents(listOf("a"))
        val f = c.subscribeEvents(listOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), f)
    }

    @Test fun `subscribe with empty names resets to wildcard`() {
        val c = freshComputer()
        c.subscribeEvents(listOf("a"))
        val f = c.subscribeEvents(emptyList())
        assertNull(f, "empty subscribe means 'allow all again'")
        assertNull(c.eventFilterSnapshot())
    }

    @Test fun `unsubscribe on wildcard is a no-op`() {
        val c = freshComputer()
        val f = c.unsubscribeEvents(listOf("redstone"))
        assertNull(f, "no whitelist active, nothing to remove")
    }

    @Test fun `unsubscribe removes names from existing whitelist`() {
        val c = freshComputer()
        c.subscribeEvents(listOf("a", "b", "c"))
        val f = c.unsubscribeEvents(listOf("b"))
        assertEquals(setOf("a", "c"), f)
    }

    @Test fun `unsubscribe with empty names blocks all events`() {
        val c = freshComputer()
        c.subscribeEvents(listOf("a"))
        val f = c.unsubscribeEvents(emptyList())
        assertEquals(emptySet<String>(), f, "empty unsubscribe means 'drop everything'")
    }

    @Test fun `whitelist of unknown names still tolerates absent removals`() {
        val c = freshComputer()
        c.subscribeEvents(listOf("a", "b"))
        val f = c.unsubscribeEvents(listOf("c"))
        assertEquals(setOf("a", "b"), f, "absent name removed cleanly without error")
    }
}
