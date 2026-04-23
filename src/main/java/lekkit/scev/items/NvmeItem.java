/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items;

public class NvmeItem extends StorageItem {
    /**
     * Declared capacity in MiB. Matches the shipped preloaded-NVMe templates
     * ({@link lekkit.scev.machine.storage.AlpineDiskTemplate}, {@link
     * lekkit.scev.machine.storage.BuildrootDiskTemplate}), so blank NVMe and
     * preloaded NVMe items both advertise "1 GiB" and the per-UUID image
     * scheme is uniform across the two paths. StorageManager enforces the
     * cap on both creation paths (sparse create for blank, sparse-extend
     * after template copy for preloaded).
     */
    public static final long SIZE_MB = 1024;

    public NvmeItem(Properties props) {
        super(props, "rootfs.ext2", SIZE_MB);
    }
}
