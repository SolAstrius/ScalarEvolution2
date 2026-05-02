/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

open class NvmeItem(props: Properties) : StorageItem(props, "rootfs.ext2", SIZE_MB) {
    companion object {
        /**
         * Declared capacity in MiB. Matches the shipped preloaded-NVMe
         * templates ([lekkit.scev.machine.storage.AlpineDiskTemplate],
         * [lekkit.scev.machine.storage.BuildrootDiskTemplate]), so blank
         * NVMe and preloaded NVMe items both advertise "1 GiB" and the
         * per-UUID image scheme is uniform across the two paths.
         * StorageManager enforces the cap on both creation paths (sparse
         * create for blank, sparse-extend after template copy for
         * preloaded).
         */
        const val SIZE_MB: Long = 1024L
    }
}
