/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Typed view over a decoded RPC frame. Every wire frame is a MessagePack
 * array whose first element is a small integer tag:
 *
 * <pre>
 *   [0, id, method, args]         - request
 *   [1, id, err_or_nil, result]   - response
 *   [2, name, args]               - event
 * </pre>
 *
 * <p>IDs are unsigned 32-bit; we carry them as {@code long} to dodge sign
 * confusion on the Java side. They're guest-chosen, guest-unique within a
 * session; Java reflects them back on the matching response.
 *
 * <p>Sealed so switch expressions exhaust cleanly. Use the static
 * constructors rather than the records directly when building outbound
 * frames — they validate basic shape.
 */
public sealed interface RpcFrame {

    int TAG_REQ = 0;
    int TAG_RSP = 1;
    int TAG_EVT = 2;

    record Request(long id, String method, List<MsgValue> args) implements RpcFrame {}
    record Response(long id, @Nullable String error, MsgValue result) implements RpcFrame {}
    record Event(String name, List<MsgValue> args) implements RpcFrame {}

    static Response ok(long id, MsgValue result) {
        return new Response(id, null, result == null ? MsgValue.NIL : result);
    }

    static Response error(long id, String message) {
        return new Response(id, message, MsgValue.NIL);
    }

    static Event event(String name, List<MsgValue> args) {
        return new Event(name, args == null ? List.of() : args);
    }
}
