/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import lekkit.scev.core.rpc.MsgValue

/**
 * Minimal MessagePack encoder/decoder covering only the types the RPC
 * uses: nil, bool, int (negative + positive), double, string, bin, array,
 * map. Enough to stay interoperable with any standard msgpack reader on
 * the guest side (`cmp.c`, `msgpack-c`, python `msgpack`).
 *
 * Deliberately unoptimised: allocates small intermediate lists and reads
 * into a [ByteArrayOutputStream]. RPC traffic is small-frame; optimising
 * this would be premature.
 *
 * Unsupported types — `ext`, `float32`, `timestamp` — throw
 * [IllegalArgumentException] on decode and are never emitted.
 */
object MsgPack {

    /* ---------------- Encode ---------------- */

    @JvmStatic
    fun encode(v: MsgValue): ByteArray {
        val out = ByteArrayOutputStream(64)
        writeValue(out, v)
        return out.toByteArray()
    }

    private fun writeValue(out: ByteArrayOutputStream, v: MsgValue) {
        when (v.kind) {
            MsgValue.Kind.NIL -> out.write(0xC0)
            MsgValue.Kind.BOOL -> out.write(if (v.asBool()) 0xC3 else 0xC2)
            MsgValue.Kind.INT -> writeInt(out, v.asInt())
            MsgValue.Kind.DOUBLE -> {
                out.write(0xCB)
                writeU64(out, java.lang.Double.doubleToLongBits(v.raw() as Double))
            }
            MsgValue.Kind.STRING -> {
                val utf = v.asString().toByteArray(StandardCharsets.UTF_8)
                val n = utf.size
                when {
                    n <= 31 -> out.write(0xA0 or n)
                    n <= 0xFF -> { out.write(0xD9); out.write(n) }
                    n <= 0xFFFF -> { out.write(0xDA); writeU16(out, n) }
                    else -> { out.write(0xDB); writeU32(out, n) }
                }
                out.write(utf, 0, n)
            }
            MsgValue.Kind.BYTES -> {
                val b = v.asBytes()
                val n = b.size
                when {
                    n <= 0xFF -> { out.write(0xC4); out.write(n) }
                    n <= 0xFFFF -> { out.write(0xC5); writeU16(out, n) }
                    else -> { out.write(0xC6); writeU32(out, n) }
                }
                out.write(b, 0, n)
            }
            MsgValue.Kind.ARRAY -> {
                val xs = v.asArray()
                val n = xs.size
                when {
                    n <= 15 -> out.write(0x90 or n)
                    n <= 0xFFFF -> { out.write(0xDC); writeU16(out, n) }
                    else -> { out.write(0xDD); writeU32(out, n) }
                }
                for (x in xs) writeValue(out, x)
            }
            MsgValue.Kind.MAP -> {
                val m = v.asMap()
                val n = m.size
                when {
                    n <= 15 -> out.write(0x80 or n)
                    n <= 0xFFFF -> { out.write(0xDE); writeU16(out, n) }
                    else -> { out.write(0xDF); writeU32(out, n) }
                }
                for ((k, vv) in m) {
                    writeValue(out, k)
                    writeValue(out, vv)
                }
            }
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, n: Long) {
        if (n >= 0) {
            when {
                n <= 0x7F -> out.write(n.toInt())                                      // positive fixint
                n <= 0xFF -> { out.write(0xCC); out.write(n.toInt()) }                 // uint8
                n <= 0xFFFF -> { out.write(0xCD); writeU16(out, n.toInt()) }
                n <= 0xFFFFFFFFL -> { out.write(0xCE); writeU32(out, n.toInt()) }
                else -> { out.write(0xCF); writeU64(out, n) }
            }
        } else {
            when {
                n >= -32 -> out.write((n and 0xFF).toInt())                            // negative fixint
                n >= Byte.MIN_VALUE.toLong() -> { out.write(0xD0); out.write((n and 0xFF).toInt()) }
                n >= Short.MIN_VALUE.toLong() -> { out.write(0xD1); writeU16(out, n.toInt()) }
                n >= Int.MIN_VALUE.toLong() -> { out.write(0xD2); writeU32(out, n.toInt()) }
                else -> { out.write(0xD3); writeU64(out, n) }
            }
        }
    }

    private fun writeU16(out: ByteArrayOutputStream, n: Int) {
        out.write((n ushr 8) and 0xFF)
        out.write(n and 0xFF)
    }
    private fun writeU32(out: ByteArrayOutputStream, n: Int) {
        out.write((n ushr 24) and 0xFF)
        out.write((n ushr 16) and 0xFF)
        out.write((n ushr 8) and 0xFF)
        out.write(n and 0xFF)
    }
    private fun writeU64(out: ByteArrayOutputStream, n: Long) {
        var s = 56
        while (s >= 0) {
            out.write(((n ushr s) and 0xFF).toInt())
            s -= 8
        }
    }

    /* ---------------- Decode ---------------- */

    /** Parse position tracker so the recursive decoder doesn't need a wrapper class. */
    private class Reader(val buf: ByteArray) {
        var pos = 0
        fun u8(): Int = buf[pos++].toInt() and 0xFF
        fun u16(): Int { val h = u8(); val l = u8(); return (h shl 8) or l }
        fun u32(): Long {
            val a = u8().toLong(); val b = u8().toLong()
            val c = u8().toLong(); val d = u8().toLong()
            return (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        fun u64(): Long {
            var r = 0L
            repeat(8) { r = (r shl 8) or u8().toLong() }
            return r
        }
        fun take(n: Int): ByteArray {
            if (pos + n > buf.size) throw IllegalArgumentException("msgpack: short read")
            val out = ByteArray(n)
            System.arraycopy(buf, pos, out, 0, n)
            pos += n
            return out
        }
    }

    @JvmStatic
    fun decode(buf: ByteArray): MsgValue = decode(buf, 0, buf.size)

    @JvmStatic
    fun decode(buf: ByteArray, off: Int, len: Int): MsgValue {
        val r = Reader(buf.copyOfRange(off, off + len))
        return readValue(r)
    }

    private fun readValue(r: Reader): MsgValue {
        val b = r.u8()
        if (b <= 0x7F) return MsgValue.of(b.toLong())                                   // positive fixint
        if (b >= 0xE0) return MsgValue.of(b.toByte().toLong())                          // negative fixint
        if ((b and 0xE0) == 0xA0) return MsgValue.of(String(r.take(b and 0x1F), StandardCharsets.UTF_8))
        if ((b and 0xF0) == 0x90) return readArray(r, b and 0x0F)
        if ((b and 0xF0) == 0x80) return readMap(r, b and 0x0F)
        return when (b) {
            0xC0 -> MsgValue.NIL
            0xC2 -> MsgValue.FALSE
            0xC3 -> MsgValue.TRUE
            0xC4 -> MsgValue.of(r.take(r.u8()))
            0xC5 -> MsgValue.of(r.take(r.u16()))
            0xC6 -> MsgValue.of(r.take(r.u32().toInt()))
            0xCA -> MsgValue.of(java.lang.Float.intBitsToFloat(r.u32().toInt()).toDouble())
            0xCB -> MsgValue.of(java.lang.Double.longBitsToDouble(r.u64()))
            0xCC -> MsgValue.of(r.u8().toLong())
            0xCD -> MsgValue.of(r.u16().toLong())
            0xCE -> MsgValue.of(r.u32())
            0xCF -> MsgValue.of(r.u64())                              // may wrap negative for values > 2^63-1
            0xD0 -> MsgValue.of(r.u8().toByte().toLong())
            0xD1 -> MsgValue.of(r.u16().toShort().toLong())
            0xD2 -> MsgValue.of(r.u32().toInt().toLong())
            0xD3 -> MsgValue.of(r.u64())
            0xD9 -> MsgValue.of(String(r.take(r.u8()), StandardCharsets.UTF_8))
            0xDA -> MsgValue.of(String(r.take(r.u16()), StandardCharsets.UTF_8))
            0xDB -> MsgValue.of(String(r.take(r.u32().toInt()), StandardCharsets.UTF_8))
            0xDC -> readArray(r, r.u16())
            0xDD -> readArray(r, r.u32().toInt())
            0xDE -> readMap(r, r.u16())
            0xDF -> readMap(r, r.u32().toInt())
            else -> throw IllegalArgumentException(
                "msgpack: unsupported tag 0x%02X (ext / reserved / float32-only?)".format(b))
        }
    }

    private fun readArray(r: Reader, n: Int): MsgValue {
        val xs = ArrayList<MsgValue>(n)
        repeat(n) { xs.add(readValue(r)) }
        return MsgValue.ofArray(xs)
    }

    private fun readMap(r: Reader, n: Int): MsgValue {
        val m = LinkedHashMap<MsgValue, MsgValue>(n * 2)
        repeat(n) {
            val k = readValue(r)
            val vv = readValue(r)
            m[k] = vv
        }
        return MsgValue.ofMap(m)
    }
}
