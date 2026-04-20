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

        add("container.scev.computer_case", "Computer case");
        add("container.scev.motherboard", "Motherboard");
        add("container.scev.laptop", "Tinkerpad");
        add("container.scev.machine", "Machine");

        add("text.scev.capacity", "Capacity");
        add("text.scev.ram_slots", "Memory slots");
        add("text.scev.pci_slots", "PCI Expansion slots");
        add("text.scev.m2_slots", "M.2 (NGFF) slots");
        add("text.scev.installed_components", "Installed components");
        add("text.scev.send_esc_hint", "Shift+Esc: Send Esc");
        add("text.scev.grab_input_hint", "Ctrl+Alt+G: Grab input");
        add("text.scev.release_grab_hint", "Ctrl+Alt+G: Release grab");

        add("button.scev.power", "Power");
        add("button.scev.reset", "Reset");
        add("tooltip.scev.power", "Toggle machine power");
        add("tooltip.scev.reset", "Send CPU reset to a running machine");

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
        addItem(ScevRegistry.SOC,                "System on a Chip (SoC)");
        addItem(ScevRegistry.SOLDERING_IRON,     "Pinecil Soldering Iron");

        addItem(ScevRegistry.FLASH_CHIP,         "Flash ROM Chip");
        addItem(ScevRegistry.HDD,                "IDE Hard Disk Drive");
        addItem(ScevRegistry.NVME,               "NVMe Drive");
        addItem(ScevRegistry.NVME_PRELOADED,     "NVMe Drive (Buildroot Linux)");
        addItem(ScevRegistry.VGA_CARD,           "Videoadapter Card");
        addItem(ScevRegistry.GPIO_CARD,          "GPIO Redstone Card");
        addItem(ScevRegistry.SOUND_CARD,         "Sound Card");
        addItem(ScevRegistry.RTL8169,            "RailTek RTL8169 Network Card");

        addItem(ScevRegistry.CPU1,               "CPU (Tier 1)");
        addItem(ScevRegistry.CPU2,               "CPU (Tier 2)");
        addItem(ScevRegistry.CPU3,               "CPU (Tier 3)");
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
    }
}
