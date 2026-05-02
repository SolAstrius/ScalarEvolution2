/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import java.util.UUID
import java.util.function.Consumer
import lekkit.scev.main.ScevDataComponents
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities

/**
 * Recursive walker: given an [ItemStack], emit every `STORAGE_UUID` reachable
 * through it. The "unpacker" that turns a single stack — possibly a flat
 * NVMe, a motherboard with nested components, a shulker-in-chest, or any
 * combination — into a flat stream of UUIDs for the scanner and event paths.
 *
 * **What it looks through:**
 *  1. Direct `STORAGE_UUID` on the stack (the flat case).
 *  2. Motherboard inventory (`MOTHERBOARD_INVENTORY` data component, up to 14
 *     nested component stacks).
 *  3. `Capabilities.ItemHandler.ITEM` — shulkers (NeoForge auto-registers
 *     against `DataComponents.CONTAINER`), Sophisticated Backpacks, Iron
 *     Chests pickups, etc. New container items "just work" without updates.
 *  4. `DataComponents.BUNDLE_CONTENTS` — vanilla 1.21 bundles do NOT auto-
 *     register an ItemHandler, so we read the component directly. If NeoForge
 *     adds a bundle handler later, this becomes harmless redundancy.
 *
 * **What it doesn't look through:** AE2 cells, Refined Storage disks,
 * Mekanism QIO, Create contraption inventories. Each lives in mod-specific
 * NBT/sidecar storage; closing those gaps is a job for compat scanners
 * registered via [ScannerRegistry].
 *
 * **Cycle guard.** A stack could in principle contain itself. The visited
 * set uses identity equality — two distinct stacks with identical data must
 * each contribute their UUIDs.
 */
object StackInspector {
    /**
     * Emit every `STORAGE_UUID` reachable from [stack] to [collector]. Flat
     * stack -> one emission; motherboard with four NVMes -> four; nested
     * containers recurse. Safe on null/empty stacks. Thread-safe iff the
     * underlying stacks aren't mutated concurrently — callers hold the
     * server thread during scans.
     */
    @JvmStatic fun inspect(stack: ItemStack?, collector: Consumer<UUID>) {
        // Identity-based set so two distinct stacks with the same data both contribute.
        inspect(stack, collector,
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ItemStack, Boolean>()))
    }

    private fun inspect(stack: ItemStack?, collector: Consumer<UUID>, visited: MutableSet<ItemStack>) {
        if (stack == null || stack.isEmpty) return
        if (!visited.add(stack)) return

        // 1. Direct STORAGE_UUID — the flat case.
        stack.get(ScevDataComponents.STORAGE_UUID.get())?.let(collector::accept)

        // 2. Motherboard's 14-slot nested inventory.
        stack.get(ScevDataComponents.MOTHERBOARD_INVENTORY.get())
            ?.stream()?.forEach { inspect(it, collector, visited) }

        // 3. ItemHandler.ITEM — shulkers, backpacks, and most modded container-items.
        stack.getCapability(Capabilities.ItemHandler.ITEM)?.let { h ->
            for (i in 0 until h.slots) inspect(h.getStackInSlot(i), collector, visited)
        }

        // 4. Bundle contents — NeoForge doesn't register ItemHandler.ITEM for
        //    1.21.1 bundles. Read the raw component as a fallback.
        stack.get(DataComponents.BUNDLE_CONTENTS)
            ?.items()?.forEach { inspect(it, collector, visited) }
    }
}
