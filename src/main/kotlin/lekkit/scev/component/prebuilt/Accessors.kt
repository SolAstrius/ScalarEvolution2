/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.component.prebuilt

/**
 * Collection-of-same-shape helpers.
 *
 * A lot of peripherals expose N slots, N tanks, or N lines — the
 * layout inside each item is identical. Rather than writing N×M
 * property definitions, mod authors implement one of these
 * interfaces and scev expands it into the tree at attach time
 * (`slots/0/item`, `slots/0/count`, `slots/1/item`, …).
 *
 * These interfaces are pure data reads — they don't touch the world
 * by themselves, so the scev snapshot refresher can call them freely
 * off the tick. Writes (setItemStack, fillTank) belong in separate
 * actions.
 *
 * Implementations are typically a thin delegate around a NeoForge
 * capability handle.
 */

/** One inventory slot's worth of data, in the scev canonical shape. */
data class SlotSnapshot(
    /** Item resource id — e.g. `"minecraft:coal"`, `""` when empty. */
    val item: String,
    /** Stack size. 0 when empty. */
    val count: Int,
    /**
     * Item-component JSON — the data-components blob, serialised, for
     * advanced tooling. Empty when the stack is empty or has no
     * non-default components.
     */
    val componentsJson: String = "",
)

/** Read-only access to N inventory slots. */
interface SlotAccess {
    /** Total slot count. Stable for the lifetime of the access. */
    val size: Int

    /**
     * Snapshot slot at [index]. Out-of-range index throws
     * [IndexOutOfBoundsException]; the caller (scev's FUSE layer)
     * translates that to `ENOENT` on the guest.
     */
    fun snapshot(index: Int): SlotSnapshot
}

/**
 * Inventory is fundamentally filesystem-shaped — items are the
 * elements, slots are the containers, and the natural verbs are
 * POSIX (`ls`, `cat`, `mv`, `inotifywait`) rather than scalar-read /
 * action-file pairs. [InventoryAccess] is the semantic contract an
 * inventory implementation hands to scev; the runtime decides how
 * to expose it.
 *
 * For inventory-centric components (chests, barrels, ME networks as
 * peripherals), declare [lekkit.scev.component.ComponentRole.MOUNT];
 * the runtime mounts the inventory at
 * `/mnt/scev/inventories/<peer>/` with a native filesystem surface:
 *
 *   - `ls slots/`                       enumerate
 *   - `cat slots/3/item`, `count`, …    read a slot
 *   - `mv slots/3 slots/5`              whole-stack move (empty target)
 *                                       or swap (non-empty target)
 *   - `mv inv_a/slots/3 inv_b/slots/5`  cross-inventory move — one
 *                                       rename(2), atomic, because
 *                                       every inventory mounts inside
 *                                       the same FUSE tree
 *   - `echo "count=16 target=5" > slots/3/push`
 *                                       partial transfer (the one
 *                                       unavoidable non-POSIX verb;
 *                                       `mv` has no count arg)
 *   - `inotifywait slots/3`             slot-change notifications
 *
 * For inventory-embedded plugins (a reactor's input/output slots, a
 * generator's fuel slot), the same interface is hosted under a
 * `plugin("items")` in a PROPERTY_BAG component. The runtime presents
 * a nested slot tree with the same `mv`/`cat` semantics, just rooted
 * under the plugin's subdir.
 *
 * Default implementations of the mutating methods throw
 * [lekkit.scev.component.api.ReadOnlyException] so a read-only
 * inventory (display-only chests, remote views) overrides nothing
 * and scev surfaces `EROFS` on writes.
 */
interface InventoryAccess : SlotAccess {
    /** Descriptive kind tag — "chest" / "hopper" / "me_network" / ... */
    val kind: String get() = "generic"

    /** Max stack size for a specific slot. Usually 64, sometimes 16/1. */
    fun maxSlotSize(index: Int): Int = 64

    /** Functional role of a slot — used to expose filters / admissibility. */
    fun slotKind(index: Int): SlotKind = SlotKind.GENERAL

    /**
     * Move items from [fromSlot] to [toSlot] within this inventory.
     *
     * Semantics:
     *  - [count] == -1: move the whole stack. If the target is empty,
     *    transfer it; if it's non-empty, swap the two slots. This is
     *    what FUSE `rename(2)` on `mv slots/A slots/B` binds to.
     *  - [count] >= 0: transfer up to [count] items (capped by stack
     *    size, max slot size, and filters). Fewer than [count] may
     *    move and the result carries the actual count as
     *    [TransferResult.Partial].
     *
     * Refused transfers (filter rejection, locked slot) return
     * [TransferResult.Refused]. Structural errors (out-of-range slot
     * index) throw [IndexOutOfBoundsException] → `ENOENT` on the guest.
     *
     * Default throws [lekkit.scev.component.api.ReadOnlyException] so
     * a read-only inventory simply omits an override.
     */
    fun move(fromSlot: Int, toSlot: Int, count: Int = -1): TransferResult =
        throw lekkit.scev.component.api.ReadOnlyException(
            "inventory is read-only",
        )

    /**
     * Direct slot swap, independent of [move] — implementations can
     * optimise the two-slot case when [move] would otherwise allocate
     * a temporary. Default delegates to [move] with `count = -1` and
     * a non-empty target, which is the same observable behavior.
     */
    fun swap(a: Int, b: Int): TransferResult = move(a, b, -1)

    /**
     * Optional: check if a slot would accept an item without
     * performing the insertion. Implementations that honour filters
     * ({@link SlotKind#FILTER} slots, Mekanism input-only, AE2 config
     * slots) override this; default says "yes."
     */
    fun accepts(
        index: Int,
        item: String,
        componentsJson: String = "",
    ): Boolean = true
}

/**
 * What happened on a write-like [InventoryAccess] operation. Runtime
 * maps onto syscall return codes:
 *
 *  - [Success]  → 0, whole operation completed as requested.
 *  - [Partial]  → 0 with a partial count reported via the action's
 *                 result file (or the smaller-than-requested transfer
 *                 reflected on subsequent reads).
 *  - [Refused]  → the named errno (default [lekkit.scev.component.api.Errno.EACCES]).
 */
sealed class TransferResult {
    object Success : TransferResult()

    /** Some items moved; [moved] reports the actual count. */
    data class Partial(val moved: Int) : TransferResult()

    /**
     * No items moved. [reason] is a human-readable tag,
     * [errno] is what the guest's syscall returns.
     */
    data class Refused(
        val reason: String,
        val errno: Int = lekkit.scev.component.api.Errno.EACCES,
    ) : TransferResult()
}

/**
 * Functional role of an inventory slot. Used for introspection and
 * for the runtime to compute "is this a valid rename target" before
 * invoking [InventoryAccess.move].
 *
 *  - [GENERAL]     — unconstrained; most chest slots.
 *  - [INPUT_ONLY]  — items only go in (hopper insert, furnace input).
 *  - [OUTPUT_ONLY] — items only come out (furnace output, infuser result).
 *  - [FUEL]        — furnace fuel slot, RTG fuel, etc.
 *  - [FILTER]      — filter-capturing slots (AE2 config, Mekanism filter).
 *  - [UPGRADE]     — upgrade slots (speed upgrade, capacity upgrade,
 *                    turtle tool slot).
 */
enum class SlotKind {
    GENERAL,
    INPUT_ONLY,
    OUTPUT_ONLY,
    FUEL,
    FILTER,
    UPGRADE,
    ;

    /** Lowercase name for the FS `slots/<n>/kind` file. */
    val fsLabel: String get() = name.lowercase()
}

/**
 * Event emitted when a slot changes. Declared here because every
 * inventory peripheral will want the same shape.
 */
data class SlotChangedEvent(
    val slot: Int,
    val before: SlotSnapshot,
    val after: SlotSnapshot,
)

/** One fluid tank's worth of data. */
data class TankSnapshot(
    /** Fluid resource id — `""` when empty. */
    val fluid: String,
    /** Current amount, in millibuckets. 0 when empty. */
    val amount: Int,
    /** Tank capacity, in millibuckets. */
    val capacity: Int,
)

/** Read-only access to N fluid tanks. */
interface TankAccess {
    val size: Int
    fun snapshot(index: Int): TankSnapshot
}

/**
 * Minimal terminal-buffer shape — enough to surface monitor / printer
 * pages as files without a full terminal emulator.
 *
 * [width] × [height] char grid; [line] returns one row of text,
 * trimmed of trailing whitespace. Colour info is not exposed here —
 * if an author wants to expose per-cell fg/bg they can add a
 * sibling property returning an RLE-encoded string.
 */
interface TerminalAccess {
    val width: Int
    val height: Int
    val cursorX: Int
    val cursorY: Int

    /** One row as a string. Row index is 0-based. */
    fun line(row: Int): String
}
