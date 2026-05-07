/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.rpc

import lekkit.scev.rpc.ChunkStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ChunkStore] in isolation: bounds, monotonicity, eviction, TTL.
 * The ScevRpcManager-wired path is covered separately — these tests
 * only assert the store's local invariants.
 */
class ChunkStoreTest {
    private val uuid = UUID.randomUUID()

    @Test fun `add returns monotonic ids and read returns slices`() {
        val s = ChunkStore(uuid)
        val payload = ByteArray(1024) { (it and 0xFF).toByte() }
        val id1 = s.add(payload, 0L)!!
        val id2 = s.add(payload, 0L)!!
        assertEquals(id1 + 1, id2, "stream ids should be monotonic")

        // First 100 bytes of stream 1.
        val slice = s.read(id1, 0, 100)!!
        assertEquals(100, slice.size)
        assertArrayEquals(payload.copyOfRange(0, 100), slice)

        // Tail starting at 1000.
        val tail = s.read(id1, 1000, 1024)!!
        assertEquals(24, tail.size)
        assertArrayEquals(payload.copyOfRange(1000, 1024), tail)

        // Read at exact size returns empty (EOF).
        val eof = s.read(id1, 1024, 64)!!
        assertEquals(0, eof.size)
    }

    @Test fun `read on unknown stream returns null`() {
        val s = ChunkStore(uuid)
        assertNull(s.read(9999L, 0, 64))
    }

    @Test fun `discard removes the stream`() {
        val s = ChunkStore(uuid)
        val id = s.add(ByteArray(64), 0L)!!
        assertTrue(s.discard(id), "first discard reports presence")
        assertFalse(s.discard(id), "second discard is a no-op")
        assertNull(s.read(id, 0, 64), "discarded stream is gone")
    }

    @Test fun `oversize rejected at per-stream cap`() {
        val s = ChunkStore(
            uuid,
            maxStreamBytes = 1024,
        )
        assertNotNull(s.add(ByteArray(1024), 0L), "exactly cap is fine")
        assertNull(s.add(ByteArray(1025), 0L), "one over cap returns null")
    }

    @Test fun `oldest stream evicted when count cap exceeded`() {
        val s = ChunkStore(uuid, maxStreams = 2)
        val a = s.add(ByteArray(8), 0L)!!
        val b = s.add(ByteArray(8), 0L)!!
        val c = s.add(ByteArray(8), 0L)!!
        // 'a' should have been evicted to make room for 'c'.
        assertNull(s.read(a, 0, 8), "oldest stream evicted")
        assertNotNull(s.read(b, 0, 8))
        assertNotNull(s.read(c, 0, 8))
        assertEquals(2, s.pendingCount())
    }

    @Test fun `eviction frees the byte budget`() {
        // Two-stream cap, total bytes 16 — third 8-byte add forces an eviction.
        val s = ChunkStore(uuid, maxStreams = 8, maxTotalBytes = 16)
        val a = s.add(ByteArray(8), 0L)!!
        val b = s.add(ByteArray(8), 0L)!!
        assertEquals(16, s.pendingBytes())
        val c = s.add(ByteArray(8), 0L)!!
        assertNull(s.read(a, 0, 8), "byte cap forced 'a' to be evicted")
        assertNotNull(s.read(b, 0, 8))
        assertNotNull(s.read(c, 0, 8))
        assertEquals(16, s.pendingBytes())
    }

    @Test fun `tickEvictExpired drops streams older than ttl`() {
        val s = ChunkStore(uuid, ttlMillis = 100)
        val a = s.add(ByteArray(8), now = 0L)!!
        val b = s.add(ByteArray(8), now = 60L)!!
        val c = s.add(ByteArray(8), now = 200L)!!

        // At t=149, 'a' is 149ms old (>ttl) and gets swept;
        // 'b' is 89ms old (<ttl) and survives.
        s.tickEvictExpired(now = 149L)
        assertNull(s.read(a, 0, 8), "a expired")
        assertNotNull(s.read(b, 0, 8), "b still under ttl")
        assertNotNull(s.read(c, 0, 8), "c is the newest")

        // At t=300, 'b' is 240ms old, 'c' is 100ms old (boundary
        // — implementation evicts at age >= ttl, so 'c' goes too).
        s.tickEvictExpired(now = 300L)
        assertNull(s.read(b, 0, 8), "b expired")
        assertNull(s.read(c, 0, 8), "c at exactly ttl boundary is evicted")
    }

    @Test fun `evicted ids are never reused`() {
        val s = ChunkStore(uuid, maxStreams = 1)
        val a = s.add(ByteArray(8), 0L)!!
        val b = s.add(ByteArray(8), 0L)!!  // Evicts 'a'.
        assertNotEquals(a, b, "ids never reused even after eviction")
        assertNull(s.read(a, 0, 8), "stale id reads as missing, not as 'b's data")
    }

    @Test fun `read tolerates short maxLen and returns the actual slice`() {
        val s = ChunkStore(uuid)
        val id = s.add(byteArrayOf(1, 2, 3, 4, 5), 0L)!!
        val slice = s.read(id, 1, 100)!!
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), slice)
    }
}
