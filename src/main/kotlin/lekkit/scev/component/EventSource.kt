/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A push channel from a component to the guest.
 *
 * Peripherals that emit events (chat arriving, player entering a
 * detection zone, a redstone edge, a modem message) declare one
 * [EventSource] per event name. Mod code calls [fire] from the
 * server tick; scev fans the payload out to any currently-subscribed
 * guest subscribers via the FS / RPC layer.
 *
 * Events land in two places on the guest:
 *
 * 1. The per-component stream at `/dev/scev/<name>.events` — each
 *    fire produces one JSON line.
 * 2. The machine-wide aggregate at `/sys/scev/events` — used by the
 *    existing [lekkit.scev.rpc.ScevRpcManager] event fan-out when a
 *    ScevCCComputer is attached.
 *
 * [T] is whatever the event payload is. The runtime is responsible
 * for serialising it (via the describe-registered converter). For the
 * dead API, [fire] just records into the subscriber list without
 * producing bytes.
 *
 * Threading: [fire] should be called from the server tick. [subscribe]
 * and [unsubscribe] are safe from any thread — the underlying list is
 * copy-on-write.
 */
class EventSource<T> {
    private val subscribers = CopyOnWriteArrayList<(T) -> Unit>()

    /**
     * Register a callback. Returns a handle the caller can later pass
     * to [unsubscribe]. Use of the handle is optional — detaching the
     * component clears every subscriber.
     */
    fun subscribe(listener: (T) -> Unit): Subscription {
        subscribers.add(listener)
        return Subscription { subscribers.remove(listener) }
    }

    /** Remove via the [Subscription] handle. */
    fun unsubscribe(subscription: Subscription): Unit = subscription.cancel()

    /** Push [event] to every current subscriber. Call from the server tick. */
    fun fire(event: T) {
        for (listener in subscribers) {
            try {
                listener(event)
            } catch (_: Throwable) {
                // A misbehaving subscriber shouldn't take the tick
                // down with it. In production, log; for the dead API,
                // swallow silently.
            }
        }
    }

    /** Number of active subscribers. Useful for tests. */
    val subscriberCount: Int get() = subscribers.size

    /** Handle returned by [subscribe], idempotent on [cancel]. */
    class Subscription internal constructor(private val canceller: () -> Unit) {
        @Volatile private var cancelled = false

        fun cancel() {
            if (cancelled) return
            cancelled = true
            canceller()
        }
    }
}
