/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

/**
 * Bounded byte ring of recent guest TX, kept by [ScevRpcManager] and
 * shipped to late-joining VT100 viewers so they don't open a freshly
 * black screen.
 *
 * The byte stream is the source of truth: the VT/xterm parser is
 * deterministic, so replaying the same bytes through the same parser
 * yields the same screen. That includes ReGIS (`ESC P p … ESC \`) and
 * sixel (`ESC P q … ESC \`) — they're just DCS payloads, the client's
 * mlterm parses them and renders identically. No state snapshot, no
 * pixel streaming, no fork-side serialization.
 *
 * **DCS-safe wrap.** Naive ring drop can truncate a DCS mid-payload,
 * leaving the parser waiting for an ST that never comes — every
 * subsequent byte then accumulates in the parser's DCS buffer instead
 * of being interpreted. We avoid this by tracking minimal parser state
 * (GROUND vs in-ESC vs in-DCS-or-friends) as bytes are written, and on
 * wrap dropping forward to the most recent "GROUND state" boundary.
 *
 * The parser state machine recognizes the five C1 control intros that
 * have to terminate with ST:
 *  - DCS (ESC P / 0x90)
 *  - OSC (ESC ] / 0x9D)
 *  - SOS (ESC X / 0x98)
 *  - PM  (ESC ^ / 0x9E)
 *  - APC (ESC _ / 0x9F)
 *
 * CSI / SS2 / SS3 etc. don't need this treatment because they finish
 * inside one or two trailing bytes — the parser self-recovers on the
 * next escape sequence start.
 *
 * If GROUND state hasn't been reached within the ring's capacity (e.g.
 * the entire ring is one giant unterminated sixel), we fall back to a
 * forced truncation. Late joiner sees a corrupted picture, lives.
 */
class SerialReplayBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    private val buf: ByteArray = ByteArray(capacity)

    /** Monotonic byte index of the next write. `head - tail` is the
     *  number of valid bytes currently in the ring. We use uint64 logic
     *  here for clarity — overflow at 2^63 bytes is irrelevant. */
    private var head: Long = 0
    private var tail: Long = 0

    /** Position of the first byte AFTER the most recent moment the
     *  parser was in GROUND state. We can safely truncate up to (but
     *  not past) this without leaving the parser in a stuck state. */
    private var lastSafeHead: Long = 0

    /** Minimal parser state. Big enough to know whether a drop would
     *  strand a DCS-family payload mid-stream. */
    private enum class State { GROUND, ESC, IN_ST_TERMINATED }
    private var state: State = State.GROUND

    /* ---------------- public API ---------------- */

    /** Append [len] bytes from [bytes] starting at offset 0. */
    fun write(bytes: ByteArray, len: Int) {
        if (len <= 0) return
        var i = 0
        while (i < len) {
            val b = bytes[i]
            // Wraparound write — head & (capacity - 1) only works for
            // power-of-two capacities; use rem for safety.
            buf[(head % capacity).toInt()] = b
            head++
            advanceState(b)
            if (state == State.GROUND) {
                lastSafeHead = head
            }
            if (head - tail > capacity) {
                // Prefer dropping to the last safe boundary so a DCS
                // payload isn't split. Fall back to forced truncation
                // if nothing safe has happened recently.
                tail = if (lastSafeHead > tail) {
                    maxOf(lastSafeHead, head - capacity)
                } else {
                    head - capacity
                }
            }
            i++
        }
    }

    /** Number of valid bytes currently buffered. */
    fun size(): Int = (head - tail).toInt()

    /** Snapshot the buffered bytes in arrival order. Allocates a new
     *  array; intended for the rare event of a player opening a VT100
     *  screen, not for hot paths. */
    fun snapshot(): ByteArray {
        val n = size()
        if (n == 0) return EMPTY
        val out = ByteArray(n)
        val start = (tail % capacity).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(buf, start, out, 0, first)
        if (first < n) {
            System.arraycopy(buf, 0, out, first, n - first)
        }
        return out
    }

    /** Drop everything. Intended for resets / machine-reboot. */
    fun clear() {
        head = 0
        tail = 0
        lastSafeHead = 0
        state = State.GROUND
    }

    /* ---------------- parser state machine ---------------- */

    private fun advanceState(b: Byte) {
        val u = b.toInt() and 0xFF
        when (state) {
            State.GROUND -> when {
                u == 0x1B -> state = State.ESC                              // ESC
                u == 0x90 || u == 0x9D || u == 0x98 || u == 0x9E || u == 0x9F ->
                    state = State.IN_ST_TERMINATED                          // C1: DCS/OSC/SOS/PM/APC
                // CSI (0x9B) and other C1s are short — leave state at
                // GROUND; the next printable / final-byte tick keeps it
                // there. Misclassifying them costs at most a single
                // extra byte of "safe boundary lag," which is fine.
            }
            State.ESC -> when (u) {
                0x50, 0x5D, 0x58, 0x5E, 0x5F ->
                    state = State.IN_ST_TERMINATED                          // ESC P/]/X/^/_
                else ->
                    // Any other ESC sequence finishes within a byte or
                    // two; treat as GROUND. CSI's variable param run is
                    // also fine to call GROUND for our purposes — we
                    // just want to avoid stranding ST-terminated ones.
                    state = State.GROUND
            }
            State.IN_ST_TERMINATED -> when {
                // ESC \ ends the payload. We saw an ESC last byte but
                // didn't transition out of IN_ST_TERMINATED — encode
                // that with a sticky "saw ESC" flag inline.
                u == 0x5C && wasPrevEsc -> state = State.GROUND             // ESC \
                u == 0x9C -> state = State.GROUND                           // C1 ST
                // Any other byte stays inside the payload.
            }
        }
        wasPrevEsc = (u == 0x1B)
    }

    /** Tracked separately from [state] because the IN_ST_TERMINATED →
     *  GROUND transition needs to look back one byte for ESC \. */
    private var wasPrevEsc: Boolean = false

    companion object {
        /** Default capacity. 256 KiB covers a full Alpine boot (~80 KB)
         *  plus a comfortable session before wrap. Big enough that the
         *  vast majority of openings replay everything; small enough
         *  to fit in a chunk's NBT extra data. */
        const val DEFAULT_CAPACITY: Int = 256 * 1024

        private val EMPTY: ByteArray = ByteArray(0)
    }
}
