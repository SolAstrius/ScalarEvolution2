/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.items

import lekkit.scev.machine.MachineSpec
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Marker interface for items that host a per-stack RISC-V machine —
 * phones, tablets, e-readers, Game-Boy-style consoles. The
 * [lekkit.scev.server.HandheldTickHost] discovers stacks by checking
 * `item is IHandheldComputer` while walking online players'
 * inventories.
 *
 * **Identity** is the `MACHINE_UUID` data component on the stack
 * (allocated on first tick). **Persistent state** (RAM contents,
 * registers) does not survive un-equip — only what the guest wrote to
 * its STORAGE_UUID-backed disk image persists. Equivalent to the
 * computer-case BE behaviour, but with the player's inventory taking
 * the role of the chunk that owns the BE.
 *
 * Implementations vary only in:
 *  - how they assemble a [MachineSpec] from the stack (built-in motherboard
 *    layout vs. inline component slots),
 *  - the chassis model + screen UV used by the in-hand renderer.
 *
 * Both are encoded by [buildSpec] and the per-item-class chassis profile
 * looked up by the BEWLR.
 */
interface IHandheldComputer {

    /**
     * Build the [MachineSpec] for this stack. Called by the tick host
     * the first time it observes the stack with no live VM. May
     * mutate the stack's motherboard inventory (storage UUID
     * allocation) — the tick host re-`set`s the resulting components
     * back onto the stack to persist the mutation.
     *
     * Returns null on a malformed stack (e.g. missing motherboard);
     * the tick host treats null as "skip this tick."
     */
    fun buildSpec(uuid: UUID, stack: ItemStack): MachineSpec?
}
