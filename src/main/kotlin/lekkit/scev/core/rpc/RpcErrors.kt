/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.core.rpc

/**
 * Stable string codes for RPC error responses. The wire shape is a map
 * `{code: <one of these>, message: <human-readable>}`; codes are the
 * machine-readable handle a guest can branch on without having to parse
 * a localised message.
 *
 * New codes are additive — guests treat unknown codes as [GENERIC] and
 * fall back to the message string. Don't repurpose an existing code's
 * meaning.
 */
object RpcErrors {
    /** Catch-all when the host can't classify the failure. */
    const val GENERIC = "rpc_error"

    /** Caller-supplied argument missing, wrong type, or wrong shape. */
    const val BAD_ARGS = "bad_args"

    /** Method name isn't registered on this dispatcher. */
    const val NO_SUCH_METHOD = "no_such_method"

    /** Peripheral name doesn't resolve on this computer. */
    const val NO_SUCH_PEER = "no_such_peer"

    /** A peripheral method threw a `LuaException`. */
    const val LUA_ERROR = "lua_error"

    /** A peripheral method threw an unchecked Java exception. Message is
     *  the exception's own message — not the full stack. */
    const val RUNTIME_ERROR = "runtime_error"

    /** Handler threw something the dispatcher didn't expect. Message is
     *  intentionally generic; the full trace stays server-side. */
    const val INTERNAL_ERROR = "internal_error"

    /** Method requires an optional dependency (CC: Tweaked, …) that
     *  isn't present on this server. */
    const val NOT_INSTALLED = "not_installed"

    /** Operation is recognised but not supported in the current
     *  configuration (e.g. yielding peripheral inside a `batch_par`). */
    const val UNSUPPORTED = "unsupported"

    /** Response payload would exceed the wire frame cap. The guest
     *  should retry with a narrower request (filtered describe, paged
     *  list, …) or wait for chunked transfer support. */
    const val FRAME_TOO_LARGE = "frame_too_large"

    /** A `batch` item didn't run because an earlier item errored and
     *  `stop_on_error` was set. The skipped item's slot in the result
     *  array carries this code so the guest can tell skipped items
     *  apart from real failures. */
    const val SKIPPED = "skipped"
}
