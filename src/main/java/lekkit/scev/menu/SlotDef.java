/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.menu;

import java.util.List;

/**
 * Declarative slot placement for a menu: slot index within the container's own
 * inventory plus screen-space (x, y) and an optional background-texture hint.
 *
 * <p>Keeping layouts as plain data makes them trivially unit-testable — the tests
 * don't need to spin up a full Minecraft server to verify a menu's shape.
 */
public record SlotDef(int index, int x, int y, String background) {
    public static SlotDef of(int index, int x, int y, String bg) {
        return new SlotDef(index, x, y, bg);
    }

    /**
     * Layout for a computer-case menu: motherboard slot (index 0 = case)
     * plus 14 motherboard-inventory slots (index 1..14).
     *
     * <p>These coordinates match the original 1.7.10 {@code ContainerComputerCase}
     * exactly.
     */
    public static final List<SlotDef> COMPUTER_CASE = List.of(
            of(0,   8,  18, "slot_motherboard"),
            of(1,  80,  36, "slot_cpu"),
            of(2, 110, 102, "slot_flash"),
            of(3, 110,  24, "slot_ram"),
            of(4, 110,  42, "slot_ram"),
            of(5, 110,  60, "slot_ram"),
            of(6, 110,  78, "slot_ram"),
            of(7,  80,  72, "slot_m2"),
            of(8,  80,  90, "slot_m2"),
            of(9,  44,  18, "slot_pci"),
            of(10, 44,  36, "slot_pci"),
            of(11, 44,  54, "slot_pci"),
            of(12, 44,  72, "slot_pci"),
            of(13, 44,  90, "slot_pci"),
            of(14, 44, 108, "slot_pci")
    );

    /**
     * Layout for a motherboard-item menu: the 14 component slots only.
     * Indexes here are into the motherboard's own 14-slot inventory (0..13).
     */
    public static final List<SlotDef> MOTHERBOARD = List.of(
            of(0,   80,  36, "slot_cpu"),
            of(1,  110, 102, "slot_flash"),
            of(2,  110,  24, "slot_ram"),
            of(3,  110,  42, "slot_ram"),
            of(4,  110,  60, "slot_ram"),
            of(5,  110,  78, "slot_ram"),
            of(6,   80,  72, "slot_m2"),
            of(7,   80,  90, "slot_m2"),
            of(8,   44,  18, "slot_pci"),
            of(9,   44,  36, "slot_pci"),
            of(10,  44,  54, "slot_pci"),
            of(11,  44,  72, "slot_pci"),
            of(12,  44,  90, "slot_pci"),
            of(13,  44, 108, "slot_pci")
    );

    /**
     * Player inventory offsets for a "fat" GUI shape (imageHeight = 222).
     * Main inventory rows at y=140..176, hotbar at y=198.
     */
    public static final int FAT_PLAYER_INV_Y = 140;
    public static final int FAT_HOTBAR_Y = 198;
    public static final int FAT_IMAGE_WIDTH = 176;
    public static final int FAT_IMAGE_HEIGHT = 222;

    public boolean intersects(SlotDef other) {
        // Slots are 16x16 but Minecraft renders them at 18px pitch.
        return Math.abs(x - other.x) < 16 && Math.abs(y - other.y) < 16;
    }
}
