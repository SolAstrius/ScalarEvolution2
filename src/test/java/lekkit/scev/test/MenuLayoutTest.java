/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import lekkit.scev.menu.SlotDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the slot coordinates + counts match the original 1.7.10 layouts.
 *
 * <p>These coordinates come verbatim from
 * {@code lekkit.scev.container.ContainerComputerCase} and
 * {@code lekkit.scev.container.ContainerMotherboard} in the upstream repo.
 * If the 1.21.1 menu drifts from these numbers, this test breaks.
 */
class MenuLayoutTest {

    @Test
    @DisplayName("ComputerCase menu has exactly 15 slots (motherboard + 14 components)")
    void computerCaseHasCorrectSlotCount() {
        assertEquals(15, SlotDef.COMPUTER_CASE.size());
    }

    @Test
    @DisplayName("Motherboard menu has exactly 14 component slots")
    void motherboardHasCorrectSlotCount() {
        assertEquals(14, SlotDef.MOTHERBOARD.size());
    }

    @Test
    @DisplayName("ComputerCase slot positions match original 1.7.10 layout")
    void computerCaseSlotPositions() {
        assertSlot(SlotDef.COMPUTER_CASE, 0, 8, 18, "slot_motherboard");
        assertSlot(SlotDef.COMPUTER_CASE, 1, 80, 36, "slot_cpu");
        assertSlot(SlotDef.COMPUTER_CASE, 2, 110, 102, "slot_flash");
        // 4 RAM slots
        assertSlot(SlotDef.COMPUTER_CASE, 3, 110, 24, "slot_ram");
        assertSlot(SlotDef.COMPUTER_CASE, 4, 110, 42, "slot_ram");
        assertSlot(SlotDef.COMPUTER_CASE, 5, 110, 60, "slot_ram");
        assertSlot(SlotDef.COMPUTER_CASE, 6, 110, 78, "slot_ram");
        // 2 M.2 NVMe slots
        assertSlot(SlotDef.COMPUTER_CASE, 7, 80, 72, "slot_m2");
        assertSlot(SlotDef.COMPUTER_CASE, 8, 80, 90, "slot_m2");
        // 6 PCI slots
        assertSlot(SlotDef.COMPUTER_CASE, 9, 44, 18, "slot_pci");
        assertSlot(SlotDef.COMPUTER_CASE, 10, 44, 36, "slot_pci");
        assertSlot(SlotDef.COMPUTER_CASE, 11, 44, 54, "slot_pci");
        assertSlot(SlotDef.COMPUTER_CASE, 12, 44, 72, "slot_pci");
        assertSlot(SlotDef.COMPUTER_CASE, 13, 44, 90, "slot_pci");
        assertSlot(SlotDef.COMPUTER_CASE, 14, 44, 108, "slot_pci");
    }

    @Test
    @DisplayName("Motherboard slot positions match original 1.7.10 layout")
    void motherboardSlotPositions() {
        assertSlot(SlotDef.MOTHERBOARD, 0, 80, 36, "slot_cpu");
        assertSlot(SlotDef.MOTHERBOARD, 1, 110, 102, "slot_flash");
        assertSlot(SlotDef.MOTHERBOARD, 2, 110, 24, "slot_ram");
        assertSlot(SlotDef.MOTHERBOARD, 3, 110, 42, "slot_ram");
        assertSlot(SlotDef.MOTHERBOARD, 4, 110, 60, "slot_ram");
        assertSlot(SlotDef.MOTHERBOARD, 5, 110, 78, "slot_ram");
        assertSlot(SlotDef.MOTHERBOARD, 6, 80, 72, "slot_m2");
        assertSlot(SlotDef.MOTHERBOARD, 7, 80, 90, "slot_m2");
        assertSlot(SlotDef.MOTHERBOARD, 8, 44, 18, "slot_pci");
        assertSlot(SlotDef.MOTHERBOARD, 9, 44, 36, "slot_pci");
        assertSlot(SlotDef.MOTHERBOARD, 10, 44, 54, "slot_pci");
        assertSlot(SlotDef.MOTHERBOARD, 11, 44, 72, "slot_pci");
        assertSlot(SlotDef.MOTHERBOARD, 12, 44, 90, "slot_pci");
        assertSlot(SlotDef.MOTHERBOARD, 13, 44, 108, "slot_pci");
    }

    @Test
    @DisplayName("No two slots in a layout overlap with each other")
    void slotsDoNotOverlap() {
        assertNoOverlaps(SlotDef.COMPUTER_CASE);
        assertNoOverlaps(SlotDef.MOTHERBOARD);
    }

    @Test
    @DisplayName("All slots sit inside the 176x222 'fat' GUI rectangle")
    void slotsFitInGuiBounds() {
        for (List<SlotDef> layout : List.of(SlotDef.COMPUTER_CASE, SlotDef.MOTHERBOARD)) {
            for (SlotDef slot : layout) {
                assertTrue(slot.x() >= 8 && slot.x() + 16 <= SlotDef.FAT_IMAGE_WIDTH,
                        "Slot " + slot.index() + " x=" + slot.x() + " out of GUI bounds");
                assertTrue(slot.y() >= 18 && slot.y() + 16 <= SlotDef.FAT_PLAYER_INV_Y,
                        "Slot " + slot.index() + " y=" + slot.y()
                                + " overlaps player inventory (y>=" + SlotDef.FAT_PLAYER_INV_Y + ")");
            }
        }
    }

    private static void assertSlot(List<SlotDef> layout, int index, int x, int y, String bg) {
        SlotDef def = layout.get(index);
        assertEquals(index, def.index(), "Slot index at position " + index);
        assertEquals(x, def.x(), "Slot " + index + " x");
        assertEquals(y, def.y(), "Slot " + index + " y");
        assertEquals(bg, def.background(), "Slot " + index + " background");
    }

    private static void assertNoOverlaps(List<SlotDef> layout) {
        for (int i = 0; i < layout.size(); i++) {
            for (int j = i + 1; j < layout.size(); j++) {
                SlotDef a = layout.get(i);
                SlotDef b = layout.get(j);
                assertFalse(a.intersects(b),
                        "Slots " + a.index() + " @ (" + a.x() + "," + a.y() + ") and "
                                + b.index() + " @ (" + b.x() + "," + b.y() + ") overlap");
            }
        }
    }
}
