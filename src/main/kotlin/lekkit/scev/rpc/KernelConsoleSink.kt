/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.rpc

/**
 * Subscriber for guest kernel-console TX bytes.
 *
 * [ScevRpcManager] drains the kernel UART once per server tick and
 * fans the drained bytes out to every registered sink. Sinks are
 * called on the server thread; long-running work should hop to a
 * coroutine or another thread.
 *
 * The buffer passed in is reused across ticks — copy out anything
 * you need to retain past return.
 */
fun interface KernelConsoleSink {
    /**
     * @param bytes drain buffer (do NOT retain past return)
     * @param len   number of valid bytes; bytes[0..len) is the payload
     */
    fun onConsoleBytes(bytes: ByteArray, len: Int)
}
