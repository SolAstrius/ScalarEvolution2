/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.datagen;

import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.main.ScevRegistry;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ScevLangProvider extends LanguageProvider {
    public ScevLangProvider(PackOutput output) {
        super(output, ScalarEvolution.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add("itemGroup.scev.main", "Scalar Evolution");

        // Section titles — shown on the creative-tab banner strips.
        add("itemGroup.scev.section.cases_peripherals", "Cases & Peripherals");
        add("itemGroup.scev.section.crafting",          "Crafting Supplies");
        add("itemGroup.scev.section.computing",         "Compute Components");
        add("itemGroup.scev.section.storage",           "Storage Devices");
        add("itemGroup.scev.section.expansion",         "Expansion Cards");
        add("itemGroup.scev.section.motherboards",      "Motherboards");

        add("container.scev.computer_case", "Computer case");
        add("container.scev.motherboard", "Motherboard");
        add("container.scev.laptop", "Tinkerpad");
        add("container.scev.machine", "Machine");
        add("container.scev.mcu_board", "MCU Board");

        add("text.scev.capacity", "Capacity");
        add("text.scev.tier", "Tier");
        add("text.scev.cores", "Cores");
        add("text.scev.isa", "ISA");
        add("text.scev.embedded_ram", "On-die RAM");
        add("text.scev.firmware", "Firmware");
        add("text.scev.firmware.custom", "Custom (%s bytes)");
        add("text.scev.firmware.blank", "Blank");
        add("text.scev.firmware.linux", "Linux");
        add("text.scev.firmware.opensbi", "OpenSBI");
        add("text.scev.firmware.open_fw", "OpenSBI + U-Boot");
        add("text.scev.firmware.blinky", "Blinky (bare-metal demo)");
        add("text.scev.ram_slots", "Memory slots");
        add("text.scev.pci_slots", "PCI Expansion slots");
        add("text.scev.m2_slots", "M.2 (NGFF) slots");
        add("text.scev.installed_components", "Installed components");
        add("text.scev.send_esc_hint", "Shift+Esc: Send Esc");
        add("text.scev.grab_input_hint", "Ctrl+Alt+G: Grab input");
        add("text.scev.release_grab_hint", "Ctrl+Alt+G: Release grab");

        add("button.scev.power", "Power");
        add("tooltip.scev.power", "Toggle machine power");

        // Power-on preflight failure messages. Shown as the action-bar
        // overlay when the player clicks Power but a required component is
        // missing; paired with a red button flash + error beep.
        add("text.scev.power.fail.native_not_loaded", "RVVM native library not loaded — see server log");
        add("text.scev.power.fail.no_motherboard",    "Install a motherboard first");
        add("text.scev.power.fail.no_cpu",            "Install a CPU");
        add("text.scev.power.fail.no_flash",          "Install a firmware flash chip");
        add("text.scev.power.fail.no_ram",            "Install at least one RAM stick");
        add("text.scev.power.fail.no_soc",            "Install a System-on-Chip");

        addItem(ScevRegistry.EPOXY,              "Epoxy Solution");
        addItem(ScevRegistry.SILICA_COMPOUND,    "Silica Compound");
        addItem(ScevRegistry.MOLD_COMPOUND,      "Mold Compound");
        addItem(ScevRegistry.FIBERGLASS,         "Fiberglass");
        addItem(ScevRegistry.SILICON_WAFER,      "Silicon Wafer");
        addItem(ScevRegistry.PCB_BASE,           "Printed Circuit Board");
        addItem(ScevRegistry.DSUB_CONNECTOR,     "D-Sub Connector");
        addItem(ScevRegistry.CRYSTAL_OSCILLATOR, "Crystal Oscillator");
        addItem(ScevRegistry.ELECTRONIC_PARTS,   "Pile of electronic components");
        addItem(ScevRegistry.VOLTAGE_REGULATOR,  "Voltage Regulator Module (VRM)");
        addItem(ScevRegistry.RTC_MODULE,         "Real-time Clock");
        addItem(ScevRegistry.MEMORY_CHIP,        "Memory Chip");
        addItem(ScevRegistry.CHAR_DISPLAY,       "Character Display");
        addItem(ScevRegistry.GFX_DISPLAY,        "Graphics Display");
        addItem(ScevRegistry.SOC1,               "SE-1 Micro SoC");
        addItem(ScevRegistry.SOC2,               "SE-2 Embedded SoC");
        addItem(ScevRegistry.SOC3,               "SE-4 Application SoC");
        addItem(ScevRegistry.SOLDERING_IRON,     "Pinecil Soldering Iron");

        addItem(ScevRegistry.FLASH_CHIP,         "Flash ROM Chip");
        addItem(ScevRegistry.HDD,                "IDE Hard Disk Drive");
        addItem(ScevRegistry.NVME,               "NVMe Drive");
        addItem(ScevRegistry.NVME_PRELOADED,     "NVMe Drive (Buildroot Linux)");
        addItem(ScevRegistry.VGA_CARD,           "Videoadapter Card");
        addItem(ScevRegistry.GPIO_CARD,          "GPIO Redstone Card");
        addItem(ScevRegistry.SOUND_CARD,         "Sound Card");
        addItem(ScevRegistry.RTL8169,            "RailTek RTL8169 Network Card");

        addItem(ScevRegistry.CPU1,               "SE-1 Veteran");
        addItem(ScevRegistry.CPU2,               "SE-2 Core");
        addItem(ScevRegistry.CPU3,               "SE-4 Elite");
        addItem(ScevRegistry.RAM_SODIMM1,        "Memory Stick (8 MiB)");
        addItem(ScevRegistry.RAM_SODIMM2,        "Memory Stick (16 MiB)");
        addItem(ScevRegistry.RAM_SODIMM3,        "Memory Stick (32 MiB)");
        addItem(ScevRegistry.RAM_SODIMM4,        "Memory Stick (64 MiB)");
        addItem(ScevRegistry.MOTHERBOARD1,       "Motherboard");
        addItem(ScevRegistry.MOTHERBOARD2,       "Advanced Motherboard");
        addItem(ScevRegistry.MOTHERBOARD3,       "Enterprise Motherboard");

        addBlock(ScevRegistry.VT100,          "VT100 Terminal");
        addBlock(ScevRegistry.CRT_MONITOR,    "CRT Monitor");
        addBlock(ScevRegistry.WORKSTATION,    "Workstation Machine Case");
        addBlock(ScevRegistry.POWERMARK,      "PowerMark Case");
        addBlock(ScevRegistry.TINKERPAD,      "Tinkerpad Laptop");
        addBlock(ScevRegistry.KEYBOARD,       "Keyboard");
        addBlock(ScevRegistry.KEYBOARD_MOUSE, "Keyboard with mice");
        addBlock(ScevRegistry.MCU_BOARD,      "MCU Board");
    }
}
