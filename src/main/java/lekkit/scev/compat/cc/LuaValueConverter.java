/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.compat.cc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lekkit.scev.rpc.MsgValue;

/**
 * Lossless-enough conversion between {@link MsgValue}s (RPC wire) and
 * plain Java objects CC accepts as event arguments.
 *
 * <p>CC's Lua VM accepts these Java types as event args:
 * {@code Boolean}, {@code String}, {@code Number} (any JDK numeric),
 * {@code byte[]}, {@code Map<String,Object>} (becomes a Lua table),
 * {@code Object[]} or {@code Collection} (becomes a numerically-keyed
 * table), {@code null}. Anything else gets stringified or dropped
 * depending on where it appears.
 *
 * <p>Going RPC → Lua:
 * <ul>
 *   <li>NIL → {@code null}</li>
 *   <li>BOOL → Boolean</li>
 *   <li>INT → Long (Lua numbers are doubles, but CC preserves integer
 *       representation when the value fits).</li>
 *   <li>DOUBLE → Double</li>
 *   <li>STRING → String</li>
 *   <li>BYTES → byte[]</li>
 *   <li>ARRAY → Object[]</li>
 *   <li>MAP → Map with String keys (non-string keys are toString'd — Lua
 *       tables allow non-string keys but CC's queueEvent converter
 *       flattens them anyway)</li>
 * </ul>
 *
 * <p>Going Lua → RPC is only exercised by the @LuaFunction methods on
 * {@link ScevCCPeripheral}, so it's intentionally narrower — the method
 * signatures do their own coercion.
 */
final class LuaValueConverter {
    private LuaValueConverter() {}

    static Object toLua(MsgValue v) {
        return switch (v.getKind()) {
            case NIL -> null;
            case BOOL -> v.asBool();
            case INT -> v.asInt();
            case DOUBLE -> (Double) v.raw();
            case STRING -> v.asString();
            case BYTES -> v.asBytes();
            case ARRAY -> {
                List<MsgValue> xs = v.asArray();
                Object[] arr = new Object[xs.size()];
                for (int i = 0; i < xs.size(); i++) arr[i] = toLua(xs.get(i));
                yield arr;
            }
            case MAP -> {
                Map<MsgValue, MsgValue> src = v.asMap();
                Map<String, Object> out = new LinkedHashMap<>(src.size() * 2);
                for (Map.Entry<MsgValue, MsgValue> e : src.entrySet()) {
                    String k = e.getKey().isString() ? e.getKey().asString() : e.getKey().toString();
                    out.put(k, toLua(e.getValue()));
                }
                yield out;
            }
        };
    }

    /** Batch: convert a list of RPC args to the Object[] CC queueEvent wants. */
    static Object[] toLuaArgs(List<MsgValue> args) {
        Object[] out = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) out[i] = toLua(args.get(i));
        return out;
    }

    /**
     * Coerce arbitrary Java values back into {@link MsgValue}s — used
     * when surfacing the result of a reflective @LuaFunction invocation.
     * Unknown types are wrapped as their toString().
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static MsgValue toMsg(Object o) {
        if (o == null) return MsgValue.NIL;
        if (o instanceof Boolean b) return MsgValue.of(b);
        if (o instanceof Byte || o instanceof Short || o instanceof Integer || o instanceof Long) {
            return MsgValue.of(((Number) o).longValue());
        }
        if (o instanceof Number n) return MsgValue.of(n.doubleValue());
        if (o instanceof String s) return MsgValue.of(s);
        if (o instanceof byte[] b) return MsgValue.of(b);
        if (o instanceof int[] ia) {
            List<MsgValue> xs = new ArrayList<>(ia.length);
            for (int v : ia) xs.add(MsgValue.of((long) v));
            return MsgValue.ofArray(xs);
        }
        if (o instanceof Object[] arr) {
            List<MsgValue> xs = new ArrayList<>(arr.length);
            for (Object x : arr) xs.add(toMsg(x));
            return MsgValue.ofArray(xs);
        }
        if (o instanceof Iterable<?> it) {
            List<MsgValue> xs = new ArrayList<>();
            for (Object x : it) xs.add(toMsg(x));
            return MsgValue.ofArray(xs);
        }
        if (o instanceof Map m) {
            Map<MsgValue, MsgValue> out = new LinkedHashMap<>();
            for (Object e : m.entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) e;
                out.put(toMsg(entry.getKey()), toMsg(entry.getValue()));
            }
            return MsgValue.ofMap(out);
        }
        return MsgValue.of(o.toString());
    }
}
