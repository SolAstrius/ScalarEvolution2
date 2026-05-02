/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc.scanners

import lekkit.scev.server.MachineManager
import lekkit.scev.server.gc.DiskImageScanner
import lekkit.scev.server.gc.ScanContext

/**
 * Marks every currently-running machine's UUID as live.
 *
 * A machine's backing image file is being actively read and written by RVVM
 * while the VM runs; deleting it under the machine causes immediate guest
 * I/O errors and likely crashes the VM (or worse, silently corrupts the
 * guest filesystem). This scanner makes the "running machines are always
 * live" rule part of the normal scan pipeline rather than a special case
 * inside the orchestrator — keeps the safety invariant uniform.
 */
class RunningMachineScanner : DiskImageScanner {
    override fun scan(ctx: ScanContext) {
        for (u in MachineManager.getActiveUuids()) ctx.addLive(u)
    }
}
