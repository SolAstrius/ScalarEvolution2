/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

import com.mojang.logging.LogUtils
import java.util.UUID

/**
 * Per-machine cache of oversized [RpcFrame.Response] payloads waiting
 * to be drained by the guest via `read_chunk`. Ownership rules:
 *
 *  - One [ChunkStore] per [ScevRpcManager]; mutated only on the server
 *    thread (every entry/exit is gated by the dispatcher coroutine
 *    scope which dispatches to the server thread).
 *  - Stream ids are monotonic per machine. Once evicted, the same id
 *    is never reused — guests that come back with a stale id always
 *    get a clean error rather than data from a different response.
 *  - Bounded: at most [maxStreams] outstanding, total bytes capped at
 *    [maxTotalBytes]. New chunked responses past either cap evict the
 *    oldest pending stream.
 *  - TTL'd: [tickEvictExpired] drops streams older than [ttlMillis]
 *    so guests that fetch their marker but never call `read_chunk`
 *    don't leak memory. Called from [ScevRpcManager.tick].
 *
 * Per-stream cap is enforced at [add] time: anything over
 * [maxStreamBytes] is refused with a return value of `null` — the
 * caller surfaces a [lekkit.scev.core.rpc.RpcErrors.FRAME_TOO_LARGE]
 * to the guest in place of the marker.
 */
class ChunkStore(
    private val machineUuid: UUID,
    private val ttlMillis: Long = DEFAULT_TTL_MS,
    private val maxStreams: Int = DEFAULT_MAX_STREAMS,
    private val maxStreamBytes: Int = DEFAULT_MAX_STREAM_BYTES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    private data class Entry(val streamId: Long, val bytes: ByteArray, val createdAt: Long)

    /** Insertion-ordered so eviction picks the oldest. */
    private val store = LinkedHashMap<Long, Entry>()
    private var totalBytes: Long = 0L
    private var nextStreamId: Long = 1L

    /**
     * Cache `bytes` and return the stream id assigned, or `null` if
     * the payload exceeds [maxStreamBytes] (genuine pathology — the
     * caller emits [lekkit.scev.core.rpc.RpcErrors.FRAME_TOO_LARGE]).
     *
     * Evicts oldest streams as needed to honour [maxStreams] and
     * [maxTotalBytes]. The returned stream id is unique-for-life of
     * this [ChunkStore]; never reused.
     */
    fun add(bytes: ByteArray, now: Long): Long? {
        if (bytes.size > maxStreamBytes) {
            LOG.warn(
                "[scev-rpc] {} chunk store refused {} bytes (per-stream cap {} bytes)",
                machineUuid, bytes.size, maxStreamBytes,
            )
            return null
        }
        // Make room before inserting so we never transiently exceed.
        while (store.size >= maxStreams || totalBytes + bytes.size > maxTotalBytes) {
            val it = store.entries.iterator()
            if (!it.hasNext()) break
            val evicted = it.next().value
            it.remove()
            totalBytes -= evicted.bytes.size
            LOG.debug(
                "[scev-rpc] {} evicted chunk stream {} ({} bytes) to make room",
                machineUuid, evicted.streamId, evicted.bytes.size,
            )
        }
        val id = nextStreamId++
        store[id] = Entry(id, bytes, now)
        totalBytes += bytes.size
        return id
    }

    /**
     * Read up to [maxLen] bytes starting at [offset] from stream
     * [streamId]. Returns `null` if the stream isn't known (evicted,
     * never existed, or already discarded). An empty array is a valid
     * return for offset == size — the guest treats that as EOF.
     */
    fun read(streamId: Long, offset: Long, maxLen: Int): ByteArray? {
        val e = store[streamId] ?: return null
        if (offset < 0 || offset > e.bytes.size) return null
        val remaining = (e.bytes.size - offset).toInt()
        val n = minOf(maxLen, remaining)
        val out = ByteArray(n)
        System.arraycopy(e.bytes, offset.toInt(), out, 0, n)
        return out
    }

    /** Total size of stream [streamId], or `null` if not present. */
    fun sizeOf(streamId: Long): Long? = store[streamId]?.bytes?.size?.toLong()

    /** Drop a stream early. Idempotent. */
    fun discard(streamId: Long): Boolean {
        val e = store.remove(streamId) ?: return false
        totalBytes -= e.bytes.size
        return true
    }

    /** Sweep entries older than [ttlMillis]. Called from the server tick. */
    fun tickEvictExpired(now: Long) {
        if (store.isEmpty()) return
        val cutoff = now - ttlMillis
        val it = store.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.createdAt > cutoff) break  // insertion order = age order
            it.remove()
            totalBytes -= e.bytes.size
            LOG.debug(
                "[scev-rpc] {} expired chunk stream {} ({} bytes, age {} ms)",
                machineUuid, e.streamId, e.bytes.size, now - e.createdAt,
            )
        }
    }

    /* ---------------- diagnostics ---------------- */

    fun pendingCount(): Int = store.size
    fun pendingBytes(): Long = totalBytes

    companion object {
        private val LOG = LogUtils.getLogger()

        const val DEFAULT_TTL_MS: Long = 30_000L
        const val DEFAULT_MAX_STREAMS: Int = 4
        const val DEFAULT_MAX_STREAM_BYTES: Int = 8 * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024
    }
}
