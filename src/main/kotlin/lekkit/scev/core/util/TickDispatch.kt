/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.core.util

import org.slf4j.Logger

/**
 * Shared iteration pattern for per-UUID server-tick registries.
 *
 * Both [lekkit.scev.rpc.ScevRpcManager] and
 * [lekkit.scev.server.SoundStreamManager] keep a
 * `ConcurrentHashMap<UUID, Manager>` and run `manager.tick()` on each
 * entry on every server tick. A throwing handler for one machine must
 * never take down the tick loop for the rest — hence the per-entry
 * try/catch. This extension factors that boilerplate out so adding a
 * third tick-driven registry tomorrow is one line.
 *
 * @param tag short prefix for the log line identifying which registry
 *   the failure came from (e.g. `"scev-rpc"`, `"scev-audio"`).
 */
inline fun <K, V> Map<K, V>.tickEach(tag: String, log: Logger, tick: (K, V) -> Unit) {
    if (isEmpty()) return
    for ((key, value) in this) {
        try {
            tick(key, value)
        } catch (e: RuntimeException) {
            log.warn("[{}] tick threw for {}", tag, key, e)
        }
    }
}
