/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal MessagePack encoder/decoder covering only the types the RPC
 * uses: nil, bool, int (negative + positive), double, string, bin, array,
 * map. Enough to stay interoperable with any standard msgpack reader on
 * the guest side ({@code cmp.c}, {@code msgpack-c}, python {@code msgpack}).
 *
 * <p>Deliberately unoptimised: allocates small intermediate lists and
 * reads into a {@link ByteArrayOutputStream}. RPC traffic is small-frame;
 * optimising this would be premature.
 *
 * <p>Unsupported types — {@code ext}, {@code float32}, {@code timestamp} —
 * throw {@link IllegalArgumentException} on decode and are never emitted.
 */
public final class MsgPack {
    private MsgPack() {}

    /* ---------------- Encode ---------------- */

    public static byte[] encode(MsgValue v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        try { writeValue(out, v); } catch (IOException e) { throw new AssertionError(e); }
        return out.toByteArray();
    }

    private static void writeValue(ByteArrayOutputStream out, MsgValue v) throws IOException {
        switch (v.getKind()) {
            case NIL -> out.write(0xC0);
            case BOOL -> out.write(v.asBool() ? 0xC3 : 0xC2);
            case INT -> writeInt(out, v.asInt());
            case DOUBLE -> {
                out.write(0xCB);
                long bits = Double.doubleToLongBits((Double) v.raw());
                writeU64(out, bits);
            }
            case STRING -> {
                byte[] utf = v.asString().getBytes(StandardCharsets.UTF_8);
                int n = utf.length;
                if (n <= 31) out.write(0xA0 | n);
                else if (n <= 0xFF) { out.write(0xD9); out.write(n); }
                else if (n <= 0xFFFF) { out.write(0xDA); writeU16(out, n); }
                else { out.write(0xDB); writeU32(out, n); }
                out.write(utf, 0, n);
            }
            case BYTES -> {
                byte[] b = v.asBytes();
                int n = b.length;
                if (n <= 0xFF) { out.write(0xC4); out.write(n); }
                else if (n <= 0xFFFF) { out.write(0xC5); writeU16(out, n); }
                else { out.write(0xC6); writeU32(out, n); }
                out.write(b, 0, n);
            }
            case ARRAY -> {
                List<MsgValue> xs = v.asArray();
                int n = xs.size();
                if (n <= 15) out.write(0x90 | n);
                else if (n <= 0xFFFF) { out.write(0xDC); writeU16(out, n); }
                else { out.write(0xDD); writeU32(out, n); }
                for (MsgValue x : xs) writeValue(out, x);
            }
            case MAP -> {
                Map<MsgValue, MsgValue> m = v.asMap();
                int n = m.size();
                if (n <= 15) out.write(0x80 | n);
                else if (n <= 0xFFFF) { out.write(0xDE); writeU16(out, n); }
                else { out.write(0xDF); writeU32(out, n); }
                for (Map.Entry<MsgValue, MsgValue> e : m.entrySet()) {
                    writeValue(out, e.getKey());
                    writeValue(out, e.getValue());
                }
            }
        }
    }

    private static void writeInt(ByteArrayOutputStream out, long n) {
        if (n >= 0) {
            if (n <= 0x7F) out.write((int) n);                            // positive fixint
            else if (n <= 0xFF) { out.write(0xCC); out.write((int) n); }  // uint8
            else if (n <= 0xFFFF) { out.write(0xCD); writeU16(out, (int) n); }
            else if (n <= 0xFFFFFFFFL) { out.write(0xCE); writeU32(out, (int) n); }
            else { out.write(0xCF); writeU64(out, n); }
        } else {
            if (n >= -32) out.write((int) (n & 0xFF));                     // negative fixint
            else if (n >= Byte.MIN_VALUE) { out.write(0xD0); out.write((int) (n & 0xFF)); }
            else if (n >= Short.MIN_VALUE) { out.write(0xD1); writeU16(out, (int) n); }
            else if (n >= Integer.MIN_VALUE) { out.write(0xD2); writeU32(out, (int) n); }
            else { out.write(0xD3); writeU64(out, n); }
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int n) {
        out.write((n >>> 8) & 0xFF);
        out.write(n & 0xFF);
    }
    private static void writeU32(ByteArrayOutputStream out, int n) {
        out.write((n >>> 24) & 0xFF);
        out.write((n >>> 16) & 0xFF);
        out.write((n >>> 8) & 0xFF);
        out.write(n & 0xFF);
    }
    private static void writeU64(ByteArrayOutputStream out, long n) {
        for (int s = 56; s >= 0; s -= 8) out.write((int) ((n >>> s) & 0xFF));
    }

    /* ---------------- Decode ---------------- */

    /** Parse position tracker so the recursive decoder doesn't need a wrapper class. */
    private static final class Reader {
        final byte[] buf;
        int pos;
        Reader(byte[] buf) { this.buf = buf; }
        int u8() { return buf[pos++] & 0xFF; }
        int u16() { int h = u8(); int l = u8(); return (h << 8) | l; }
        long u32() { long a = u8(); long b = u8(); long c = u8(); long d = u8();
                     return (a << 24) | (b << 16) | (c << 8) | d; }
        long u64() { long r = 0; for (int i = 0; i < 8; i++) r = (r << 8) | u8(); return r; }
        byte[] take(int n) {
            if (pos + n > buf.length) throw new IllegalArgumentException("msgpack: short read");
            byte[] out = new byte[n];
            System.arraycopy(buf, pos, out, 0, n);
            pos += n;
            return out;
        }
    }

    public static MsgValue decode(byte[] buf) { return decode(buf, 0, buf.length); }
    public static MsgValue decode(byte[] buf, int off, int len) {
        Reader r = new Reader(java.util.Arrays.copyOfRange(buf, off, off + len));
        MsgValue v = readValue(r);
        return v;
    }

    private static MsgValue readValue(Reader r) {
        int b = r.u8();
        if (b <= 0x7F) return MsgValue.of((long) b);                       // positive fixint
        if (b >= 0xE0) return MsgValue.of((long) (byte) b);                // negative fixint
        if ((b & 0xE0) == 0xA0) return MsgValue.of(new String(r.take(b & 0x1F), StandardCharsets.UTF_8));
        if ((b & 0xF0) == 0x90) return readArray(r, b & 0x0F);
        if ((b & 0xF0) == 0x80) return readMap(r, b & 0x0F);
        return switch (b) {
            case 0xC0 -> MsgValue.NIL;
            case 0xC2 -> MsgValue.FALSE;
            case 0xC3 -> MsgValue.TRUE;
            case 0xC4 -> MsgValue.of(r.take(r.u8()));
            case 0xC5 -> MsgValue.of(r.take(r.u16()));
            case 0xC6 -> MsgValue.of(r.take((int) r.u32()));
            case 0xCA -> { int bits = (int) r.u32(); yield MsgValue.of((double) Float.intBitsToFloat(bits)); }
            case 0xCB -> MsgValue.of(Double.longBitsToDouble(r.u64()));
            case 0xCC -> MsgValue.of((long) r.u8());
            case 0xCD -> MsgValue.of((long) r.u16());
            case 0xCE -> MsgValue.of(r.u32());
            case 0xCF -> MsgValue.of(r.u64());                              // may wrap negative for values > 2^63-1
            case 0xD0 -> MsgValue.of((long) (byte) r.u8());
            case 0xD1 -> MsgValue.of((long) (short) r.u16());
            case 0xD2 -> MsgValue.of((long) (int) r.u32());
            case 0xD3 -> MsgValue.of(r.u64());
            case 0xD9 -> MsgValue.of(new String(r.take(r.u8()), StandardCharsets.UTF_8));
            case 0xDA -> MsgValue.of(new String(r.take(r.u16()), StandardCharsets.UTF_8));
            case 0xDB -> MsgValue.of(new String(r.take((int) r.u32()), StandardCharsets.UTF_8));
            case 0xDC -> readArray(r, r.u16());
            case 0xDD -> readArray(r, (int) r.u32());
            case 0xDE -> readMap(r, r.u16());
            case 0xDF -> readMap(r, (int) r.u32());
            default -> throw new IllegalArgumentException(
                    String.format("msgpack: unsupported tag 0x%02X (ext / reserved / float32-only?)", b));
        };
    }

    private static MsgValue readArray(Reader r, int n) {
        List<MsgValue> xs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) xs.add(readValue(r));
        return MsgValue.ofArray(xs);
    }

    private static MsgValue readMap(Reader r, int n) {
        Map<MsgValue, MsgValue> m = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            MsgValue k = readValue(r);
            MsgValue v = readValue(r);
            m.put(k, v);
        }
        return MsgValue.ofMap(m);
    }
}
