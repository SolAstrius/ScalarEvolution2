/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.component

import lekkit.scev.component.api.Errno
import lekkit.scev.component.api.InvalidArgumentException
import lekkit.scev.component.api.PeripheralException
import lekkit.scev.component.api.ReadOnlyException
import lekkit.scev.component.dsl.scevComponent
import lekkit.scev.component.prebuilt.InventoryAccess
import lekkit.scev.component.prebuilt.SlotKind
import lekkit.scev.component.prebuilt.SlotSnapshot
import lekkit.scev.component.prebuilt.TransferResult
import lekkit.scev.component.prebuilt.fromInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the filesystem-shaped inventory surface: whole-stack
 * move (count=-1), partial move, swap, filter rejection, read-only
 * fallback, cross-peripheral stubs.
 */
class InventoryTest {

    // A mutable in-memory inventory that honours the full [InventoryAccess]
    // contract. Mirrors the semantics the scev runtime will enforce:
    //
    //  - move(from, to, -1): empty target → transfer; non-empty target → swap.
    //  - move(from, to,  N): partial transfer up to N; reports actual count
    //                        via TransferResult.Partial.
    //  - filter slots (SlotKind.INPUT_ONLY + named item) reject mismatches
    //    with Refused(EACCES).
    private class InMemoryInventory(
        initial: List<SlotSnapshot>,
        private val slotKinds: Map<Int, SlotKind> = emptyMap(),
        private val filterItem: Map<Int, String> = emptyMap(),
    ) : InventoryAccess {
        private val storage = initial.toMutableList()
        override val size = storage.size
        override val kind = "chest"

        override fun snapshot(index: Int) = storage[index]
        override fun slotKind(index: Int) = slotKinds[index] ?: SlotKind.GENERAL
        override fun maxSlotSize(index: Int) = 64

        override fun accepts(index: Int, item: String, componentsJson: String): Boolean {
            val filter = filterItem[index] ?: return true
            return filter == item
        }

        override fun move(fromSlot: Int, toSlot: Int, count: Int): TransferResult {
            val src = storage[fromSlot]
            val dst = storage[toSlot]
            if (src.count == 0) return TransferResult.Refused("empty source", Errno.EINVAL)

            // Whole-stack semantics: count == -1
            if (count == -1) {
                if (dst.count == 0) {
                    // Filter check on empty target
                    if (!accepts(toSlot, src.item, src.componentsJson)) {
                        return TransferResult.Refused("filter rejected", Errno.EACCES)
                    }
                    storage[toSlot] = src
                    storage[fromSlot] = SlotSnapshot("", 0, "")
                    return TransferResult.Success
                }
                // Non-empty target → swap (if both slots accept the swap).
                if (!accepts(toSlot, src.item, src.componentsJson) ||
                    !accepts(fromSlot, dst.item, dst.componentsJson)
                ) {
                    return TransferResult.Refused("swap rejected by filter", Errno.EACCES)
                }
                storage[fromSlot] = dst
                storage[toSlot] = src
                return TransferResult.Success
            }

            // Partial move — merge-stack semantics.
            require(count > 0) { "count must be -1 or positive, got $count" }
            if (dst.count > 0 && dst.item != src.item) {
                return TransferResult.Refused("item mismatch", Errno.EINVAL)
            }
            if (!accepts(toSlot, src.item, src.componentsJson)) {
                return TransferResult.Refused("filter rejected", Errno.EACCES)
            }
            val space = maxSlotSize(toSlot) - dst.count
            val canMove = minOf(count, src.count, space)
            if (canMove == 0) return TransferResult.Refused("target full", Errno.EINVAL)
            storage[toSlot] = dst.copy(
                item = src.item,
                count = dst.count + canMove,
                componentsJson = src.componentsJson,
            )
            storage[fromSlot] = if (src.count == canMove) {
                SlotSnapshot("", 0, "")
            } else {
                src.copy(count = src.count - canMove)
            }
            return if (canMove == count) {
                TransferResult.Success
            } else {
                TransferResult.Partial(canMove)
            }
        }
    }

    // =================================================================
    // Shape + basic reads
    // =================================================================

    @Test fun `inventory plugin exposes size, kind, empty_slots, per-slot tree`() {
        val inv = InMemoryInventory(listOf(
            SlotSnapshot("minecraft:iron_ingot", 32),
            SlotSnapshot("", 0),
            SlotSnapshot("minecraft:coal", 64),
        ))
        val built = scevComponent("chest") { fromInventory(access = { inv }) }

        val items = built.descriptor.plugins.single()
        val paths = items.properties.map { it.path }.toSet()

        // Aggregate metadata
        assertTrue("size" in paths)
        assertTrue("kind" in paths)
        assertTrue("empty_slots" in paths)
        assertTrue("slots/count" in paths)

        // Per-slot files
        for (i in 0..2) {
            assertTrue("slots/$i/item" in paths)
            assertTrue("slots/$i/count" in paths)
            assertTrue("slots/$i/max_count" in paths)
            assertTrue("slots/$i/components" in paths)
            assertTrue("slots/$i/kind" in paths)
        }

        // Reader values
        assertEquals(3, built.handlers.readers.getValue("items/size")())
        assertEquals("chest", built.handlers.readers.getValue("items/kind")())
        assertEquals(1, built.handlers.readers.getValue("items/empty_slots")())
        assertEquals("minecraft:iron_ingot", built.handlers.readers.getValue("items/slots/0/item")())
        assertEquals(32, built.handlers.readers.getValue("items/slots/0/count")())
        assertEquals("general", built.handlers.readers.getValue("items/slots/0/kind")())
    }

    // =================================================================
    // Whole-stack move (the `mv` filesystem verb)
    // =================================================================

    @Test fun `move with count -1 transfers to empty target`() {
        val inv = InMemoryInventory(listOf(
            SlotSnapshot("minecraft:iron_ingot", 32),
            SlotSnapshot("", 0),
        ))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val move = built.handlers.actions.getValue("items/move")
        // "whole stack" — omit count (optional 3rd arg)
        val ret = move(arrayOf<Any?>(0, 1))
        assertEquals(true, ret)
        assertEquals(0, built.handlers.readers.getValue("items/slots/0/count")())
        assertEquals(32, built.handlers.readers.getValue("items/slots/1/count")())
    }

    @Test fun `move with count -1 swaps non-empty slots`() {
        val inv = InMemoryInventory(listOf(
            SlotSnapshot("minecraft:iron_ingot", 32),
            SlotSnapshot("minecraft:gold_ingot", 16),
        ))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val move = built.handlers.actions.getValue("items/move")
        move(arrayOf<Any?>(0, 1, -1))
        // Swap — iron now at slot 1, gold at slot 0.
        assertEquals("minecraft:gold_ingot", built.handlers.readers.getValue("items/slots/0/item")())
        assertEquals(16, built.handlers.readers.getValue("items/slots/0/count")())
        assertEquals("minecraft:iron_ingot", built.handlers.readers.getValue("items/slots/1/item")())
        assertEquals(32, built.handlers.readers.getValue("items/slots/1/count")())
    }

    // =================================================================
    // Partial move (the `echo ... > push` action)
    // =================================================================

    @Test fun `partial move reports exact count on Success`() {
        val inv = InMemoryInventory(listOf(
            SlotSnapshot("minecraft:iron_ingot", 64),
            SlotSnapshot("", 0),
        ))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val move = built.handlers.actions.getValue("items/move")
        val ret = move(arrayOf<Any?>(0, 1, 16))
        assertEquals(true, ret, "full partial transfer reports Success→true")
        assertEquals(48, built.handlers.readers.getValue("items/slots/0/count")())
        assertEquals(16, built.handlers.readers.getValue("items/slots/1/count")())
    }

    @Test fun `partial move reports Partial when capped`() {
        val inv = InMemoryInventory(listOf(
            SlotSnapshot("minecraft:iron_ingot", 64),
            SlotSnapshot("minecraft:iron_ingot", 60),
        ))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val move = built.handlers.actions.getValue("items/move")
        // Ask to move 32; only 4 will fit (target has 60/64).
        val ret = move(arrayOf<Any?>(0, 1, 32))
        assertEquals(4, ret, "partial transfer reports actual count moved")
        assertEquals(60, built.handlers.readers.getValue("items/slots/0/count")())
        assertEquals(64, built.handlers.readers.getValue("items/slots/1/count")())
    }

    @Test fun `mismatched items refuse partial move with EINVAL`() {
        val inv = InMemoryInventory(listOf(
            SlotSnapshot("minecraft:iron_ingot", 32),
            SlotSnapshot("minecraft:gold_ingot", 32),
        ))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val move = built.handlers.actions.getValue("items/move")
        val ex = assertThrows(PeripheralException::class.java) {
            move(arrayOf<Any?>(0, 1, 8))
        }
        assertEquals(Errno.EINVAL, ex.errno)
        assertTrue(ex.message!!.contains("mismatch"), ex.message)
    }

    // =================================================================
    // Filter rejection
    // =================================================================

    @Test fun `filter rejects move into input-only slot with EACCES`() {
        val inv = InMemoryInventory(
            listOf(
                SlotSnapshot("minecraft:iron_ingot", 32),
                SlotSnapshot("", 0),
            ),
            slotKinds = mapOf(1 to SlotKind.FILTER),
            filterItem = mapOf(1 to "minecraft:gold_ingot"),
        )
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val move = built.handlers.actions.getValue("items/move")
        val ex = assertThrows(PeripheralException::class.java) {
            move(arrayOf<Any?>(0, 1))
        }
        assertEquals(Errno.EACCES, ex.errno)
        assertTrue(ex.message!!.contains("filter"), ex.message)
    }

    // =================================================================
    // accepts() query
    // =================================================================

    @Test fun `accepts query honours the filter`() {
        val inv = InMemoryInventory(
            listOf(
                SlotSnapshot("", 0),
                SlotSnapshot("", 0),
            ),
            slotKinds = mapOf(1 to SlotKind.FILTER),
            filterItem = mapOf(1 to "minecraft:diamond"),
        )
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val accepts = built.handlers.actions.getValue("items/accepts")
        assertEquals(true, accepts(arrayOf<Any?>(0, "minecraft:coal")))
        assertEquals(true, accepts(arrayOf<Any?>(1, "minecraft:diamond")))
        assertEquals(false, accepts(arrayOf<Any?>(1, "minecraft:coal")))
    }

    // =================================================================
    // Read-only fallback — default InventoryAccess throws
    // =================================================================

    private class ReadOnlyInv(contents: List<SlotSnapshot>) : InventoryAccess {
        private val s = contents
        override val size = s.size
        override val kind = "display"
        override fun snapshot(index: Int) = s[index]
        // No override of move/swap — default throws.
    }

    @Test fun `read-only inventory surfaces EROFS on move`() {
        val built = scevComponent("c") {
            fromInventory(access = { ReadOnlyInv(listOf(SlotSnapshot("x", 1), SlotSnapshot("", 0))) })
        }

        val move = built.handlers.actions.getValue("items/move")
        assertThrows(ReadOnlyException::class.java) {
            move(arrayOf<Any?>(0, 1))
        }
    }

    // =================================================================
    // Cross-peripheral stubs — ENOSYS until runtime lands
    // =================================================================

    @Test fun `push action stubs out with ENOSYS until runtime lands`() {
        val inv = InMemoryInventory(listOf(SlotSnapshot("x", 1)))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val push = built.handlers.actions.getValue("items/push")
        val ex = assertThrows(PeripheralException::class.java) {
            push(arrayOf<Any?>("other_peer", 0, 0))
        }
        assertEquals(Errno.ENOSYS, ex.errno)
        assertTrue(ex.message!!.contains("runtime peer resolver"))
    }

    @Test fun `pull action stubs out with ENOSYS until runtime lands`() {
        val inv = InMemoryInventory(listOf(SlotSnapshot("", 0)))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val pull = built.handlers.actions.getValue("items/pull")
        val ex = assertThrows(PeripheralException::class.java) {
            pull(arrayOf<Any?>("other", 0, 0))
        }
        assertEquals(Errno.ENOSYS, ex.errno)
    }

    // =================================================================
    // Action-file arg parsing
    // =================================================================

    @Test fun `move rejects non-numeric args with EINVAL`() {
        val inv = InMemoryInventory(listOf(SlotSnapshot("x", 1), SlotSnapshot("", 0)))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val move = built.handlers.actions.getValue("items/move")
        assertThrows(InvalidArgumentException::class.java) {
            move(arrayOf<Any?>("not a number", 1))
        }
        assertThrows(InvalidArgumentException::class.java) {
            move(arrayOf<Any?>(0, "also not"))
        }
    }

    // =================================================================
    // Event declaration — slot_changed shape
    // =================================================================

    @Test fun `fromInventory declares slot_changed event with 5-positional payload`() {
        val inv = InMemoryInventory(listOf(SlotSnapshot("x", 1)))
        val built = scevComponent("c") { fromInventory(access = { inv }) }

        val plugin = built.descriptor.plugins.single()
        val changed = plugin.events.single { it.name == "slot_changed" }
        // Positional shape: slot + before.(item,count) + after.(item,count)
        assertEquals(5, changed.paramShape.size)
        assertEquals("number", changed.paramShape[0].luaType)
        assertEquals("string", changed.paramShape[1].luaType)
        assertEquals("number", changed.paramShape[2].luaType)
        assertEquals("string", changed.paramShape[3].luaType)
        assertEquals("number", changed.paramShape[4].luaType)
    }

    // =================================================================
    // SlotKind labels — the fs "slots/<n>/kind" file contents
    // =================================================================

    @Test fun `SlotKind fsLabel matches lowercased name`() {
        assertEquals("general", SlotKind.GENERAL.fsLabel)
        assertEquals("input_only", SlotKind.INPUT_ONLY.fsLabel)
        assertEquals("output_only", SlotKind.OUTPUT_ONLY.fsLabel)
        assertEquals("fuel", SlotKind.FUEL.fsLabel)
        assertEquals("filter", SlotKind.FILTER.fsLabel)
        assertEquals("upgrade", SlotKind.UPGRADE.fsLabel)
    }
}
