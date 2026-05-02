/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine

/**
 * Creates a fresh [MachineBackend]. Injected into
 * [lekkit.scev.server.MachineManager] so tests can swap in a fake
 * backend without monkey-patching native loading.
 */
fun interface MachineBackendFactory {
    /** Return a newly-constructed, un-[initialized][MachineBackend.initialize] backend. */
    fun create(): MachineBackend
}
