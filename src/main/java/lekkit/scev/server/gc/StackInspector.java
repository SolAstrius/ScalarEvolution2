/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lekkit.scev.main.ScevDataComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Recursive walker: given an {@link ItemStack}, emit every {@code STORAGE_UUID}
 * reachable through it.
 *
 * <p>This is the "unpacker" that turns a single stack — which might be a flat
 * NVMe, or a motherboard holding nested components, or a shulker box inside a
 * chest, or any combination of those — into a flat stream of UUIDs for the
 * scanner and event paths to harvest.
 *
 * <h2>What it looks through</h2>
 *
 * <ol>
 *   <li><b>Direct {@code STORAGE_UUID}</b> on the stack — the flat case.</li>
 *   <li><b>Motherboard inventory</b> via {@code MOTHERBOARD_INVENTORY} data
 *       component. A motherboard held in a chest (or on the ground, or
 *       inside another container) can carry up to 14 nested component stacks;
 *       any of the NVMe or flash slots may reference a UUID.</li>
 *   <li><b>{@link Capabilities.ItemHandler#ITEM} capability</b> — the broad
 *       catch-all for stack-level container items. NeoForge auto-registers
 *       this against {@link DataComponents#CONTAINER} for every shulker box
 *       in vanilla, and most modded "container-as-item" patterns
 *       (Sophisticated Backpacks, Iron Chests blocks as pickups, …) register
 *       it themselves. Walking via capability instead of by known data
 *       component means new container items "just work" without updating us.</li>
 *   <li><b>{@link DataComponents#BUNDLE_CONTENTS}</b> — vanilla 1.21 bundles
 *       do NOT auto-register {@code ItemHandler.ITEM}, so we read the raw
 *       component. If NeoForge adds a bundle handler in a future release, the
 *       capability path above will cover bundles too; this remains a harmless
 *       fallback.</li>
 * </ol>
 *
 * <h2>What it doesn't look through (documented limitations)</h2>
 *
 * <ul>
 *   <li>AE2 storage cells. Items inside an AE2 cell preserve their full
 *       {@code DataComponentPatch}, so UUIDs survive round-trip — but we don't
 *       decode AE2's {@code storage_cell_inv} NBT here. An AE2 compat scanner
 *       registered via {@link ScevGcScannerRegistryEvent} would close this gap.</li>
 *   <li>Refined Storage disks. Contents live in the external world-save file
 *       {@code refinedstorage_disks.dat}; the disk item only carries a frequency
 *       ID. Compat scanner would need to parse that file.</li>
 *   <li>Mekanism QIO frequencies — same class of problem as RS.</li>
 *   <li>Create contraption entities. Mounted inventories live in the
 *       {@code Contraption}'s saved NBT, not on the entity as a capability.</li>
 * </ul>
 *
 * <h2>Cycle guard</h2>
 *
 * <p>An {@link ItemStack} could in principle contain itself (pathological data
 * or a buggy mod). The visited-set prevents infinite recursion without
 * special-casing — identity-equality because {@link ItemStack} doesn't override
 * equals/hashCode to ignore NBT, so two stacks with the same data but different
 * identities won't collide and won't skip real content.
 */
public final class StackInspector {
    private StackInspector() {}

    /**
     * Emit every {@code STORAGE_UUID} reachable from {@code stack} to
     * {@code collector}. A flat stack with a direct UUID emits once; a
     * motherboard with four nested NVMes emits four times; nested containers
     * (shulker-in-chest-in-hand) recurse.
     *
     * <p>Safe on null/empty stacks (no-op). Safe on arbitrary depth
     * (cycle-guarded). Thread-safe iff the underlying {@link ItemStack}s
     * aren't being mutated concurrently — callers hold the server thread
     * during scans.
     */
    public static void inspect(ItemStack stack, Consumer<UUID> collector) {
        inspect(stack, collector, new HashSet<>());
    }

    private static void inspect(ItemStack stack, Consumer<UUID> collector, Set<ItemStack> visited) {
        if (stack == null || stack.isEmpty()) return;
        // Identity-based visited set: we're guarding against literal recursion
        // (same stack object reached twice), not "we've seen a stack with
        // these fields." Distinct stacks carrying the same data must each
        // contribute their UUID.
        if (!visited.add(stack)) return;

        // 1. Direct STORAGE_UUID — the flat case.
        UUID uuid = stack.get(ScevDataComponents.STORAGE_UUID.get());
        if (uuid != null) collector.accept(uuid);

        // 2. Motherboard's 14-slot nested inventory.
        ItemContainerContents mb = stack.get(ScevDataComponents.MOTHERBOARD_INVENTORY.get());
        if (mb != null) {
            mb.stream().forEach(s -> inspect(s, collector, visited));
        }

        // 3. ItemHandler.ITEM — shulkers, backpacks, and most modded container-items.
        IItemHandler handler = stack.getCapability(Capabilities.ItemHandler.ITEM);
        if (handler != null) {
            int slots = handler.getSlots();
            for (int i = 0; i < slots; i++) {
                inspect(handler.getStackInSlot(i), collector, visited);
            }
        }

        // 4. Bundle contents — NeoForge doesn't register ItemHandler.ITEM for
        //    bundles in 1.21.1. Read the raw component as a fallback.
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            bundle.items().forEach(s -> inspect(s, collector, visited));
        }
    }
}
