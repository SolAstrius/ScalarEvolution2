/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import lekkit.scev.menu.SlotDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Static source-grep tests. Verifies the menu classes actually use
 * {@link SlotDef#COMPUTER_CASE} / {@link SlotDef#MOTHERBOARD} rather than
 * re-inventing slot coordinates inline — so the layout tests in
 * {@link MenuLayoutTest} are meaningful.
 *
 * <p>We can't JUnit-instantiate the menus without a full Minecraft
 * environment (they need a {@code Player}, a level, a BE, etc.), so we
 * assert the wiring at the source level instead.
 */
class MenuConstructionTest {

    private static String src(String simpleName) throws Exception {
        Path p = SourcePackages.find("lekkit/scev/menu/" + simpleName)
                .orElseThrow(() -> new AssertionError("menu source not found: " + simpleName));
        return Files.readString(p);
    }

    @Test
    @DisplayName("ComputerCaseMenu iterates SlotDef.COMPUTER_CASE to add its slots")
    void computerCaseMenuUsesSlotDef() throws Exception {
        String s = src("ComputerCaseMenu");
        assertTrue(s.contains("SlotDef.COMPUTER_CASE"),
                "ComputerCaseMenu must reference SlotDef.COMPUTER_CASE (single source of truth)");
        assertTrue(s.contains("addSlot("), "ComputerCaseMenu must call addSlot()");
    }

    @Test
    @DisplayName("MotherboardMenu iterates SlotDef.MOTHERBOARD to add its slots")
    void motherboardMenuUsesSlotDef() throws Exception {
        String s = src("MotherboardMenu");
        assertTrue(s.contains("SlotDef.MOTHERBOARD"),
                "MotherboardMenu must reference SlotDef.MOTHERBOARD");
        assertTrue(s.contains("addSlot("), "MotherboardMenu must call addSlot()");
    }

    @Test
    @DisplayName("All menus use the fat-GUI player inventory offsets")
    void menusUseFatGuiOffsets() throws Exception {
        for (String menu : List.of("ComputerCaseMenu", "MotherboardMenu", "MachineMenu")) {
            String s = src(menu);
            assertTrue(s.contains("FAT_PLAYER_INV_Y") || s.contains("FAT_HOTBAR_Y"),
                    menu + " must position player inventory via SlotDef.FAT_* "
                            + "constants (got hand-coded offsets?)");
        }
    }

    /** Catches a regression where a menu has a typo like "140 + 56" or "8 * 16 + 4". */
    @Test
    @DisplayName("No menu hand-codes raw y-coordinates for player inventory")
    void noHandCodedPlayerInventoryY() throws Exception {
        // The fat-GUI magic numbers we rely on: player inv row 0 y=140, hotbar
        // y=198. If a menu writes "140 +" or "198 +" or "84 +" to compute
        // inventory positions inline, it's bypassing SlotDef and risks drifting.
        Pattern badPattern = Pattern.compile("\\b(84|140|198)\\s*\\+\\s*\\d+");
        for (String menu : List.of("ComputerCaseMenu", "MotherboardMenu", "MachineMenu")) {
            String s = src(menu);
            assertFalse(badPattern.matcher(s).find(),
                    menu + " hand-codes a player-inventory y-offset; use SlotDef.FAT_* "
                            + "instead.");
        }
    }
}
