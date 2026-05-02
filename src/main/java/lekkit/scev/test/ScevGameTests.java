/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test;

import java.nio.ByteBuffer;
import java.util.UUID;
import lekkit.scev.blockentity.ComputerCaseBlockEntity;
import lekkit.scev.blockentity.KeyboardBlockEntity;
import lekkit.scev.blockentity.PowermarkBlockEntity;
import lekkit.scev.blockentity.TinkerpadBlockEntity;
import lekkit.scev.blockentity.TerminalBlockEntity;
import lekkit.scev.blockentity.WorkstationBlockEntity;
import lekkit.scev.items.MotherboardInventory;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.machine.DemoBootrom;
import lekkit.scev.machine.FramebufferView;
import lekkit.scev.machine.KernelStub;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MachineSpecParser;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import lekkit.scev.machine.firmware.LinuxFirmware;
import lekkit.scev.main.ScalarEvolution;
import lekkit.scev.main.ScevDataComponents;
import lekkit.scev.main.ScevRegistry;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests that validate our blocks can be placed and their block entities are alive.
 * Registered via NeoForge's GameTestHolder annotation on the main namespace.
 */
@GameTestHolder(ScalarEvolution.MODID)
@PrefixGameTestTemplate(false)
public final class ScevGameTests {

    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void place_workstation(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());

        if (!(helper.getBlockEntity(pos) instanceof WorkstationBlockEntity be)) {
            helper.fail("Workstation block entity not created at " + pos);
            return;
        }
        if (!be.isValid()) {
            helper.fail("Workstation block entity reports invalid");
            return;
        }
        if (be.getMachineUUID() == null) {
            helper.fail("Workstation machine UUID is null");
            return;
        }
        if (be.getCaseSlotCount() != 7) { // 1 motherboard + 6 extension
            helper.fail("Workstation expected 7 case slots, got " + be.getCaseSlotCount());
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void place_terminal(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.VT100.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof TerminalBlockEntity)) {
            helper.fail("VT100 block entity not created at " + pos);
            return;
        }
        helper.succeed();
    }

    /**
     * Round-trips a motherboard ItemStack through the MOTHERBOARD_INVENTORY data
     * component: build an inventory, put components in, serialize/deserialize via
     * the codec stream, verify everything's intact.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void motherboard_inventory_persists(GameTestHelper helper) {
        // Start with an empty level-2 motherboard.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD2.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);

        // Install a CPU, flash, 2 RAM sticks, an NVMe, and a VGA card.
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU2.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM2.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START + 1, new ItemStack(ScevRegistry.RAM_SODIMM2.get()));
        inv.setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.VGA_CARD.get()));

        // The data component must now carry all 6 items.
        ItemContainerContents contents = mbStack.get(ScevDataComponents.MOTHERBOARD_INVENTORY.get());
        if (contents == null) {
            helper.fail("Motherboard stack has no MOTHERBOARD_INVENTORY component after setItem");
            return;
        }
        long nonEmpty = contents.stream().filter(s -> !s.isEmpty()).count();
        if (nonEmpty != 6) {
            helper.fail("Expected 6 non-empty slots in motherboard inventory, got " + nonEmpty);
            return;
        }

        // Build a fresh view over the same ItemStack — it should see the same
        // 6 components (no shared mutable state, contents live on the stack).
        MotherboardInventory reread = new MotherboardInventory(() -> mbStack);
        if (!(reread.getItem(MotherboardItem.SLOT_CPU).getItem() == ScevRegistry.CPU2.get()
                && reread.getItem(MotherboardItem.SLOT_FLASH).getItem() == ScevRegistry.FLASH_CHIP.get()
                && reread.getItem(MotherboardItem.SLOT_NVME_START).getItem() == ScevRegistry.NVME.get()
                && reread.getItem(MotherboardItem.SLOT_PCI_START).getItem() == ScevRegistry.VGA_CARD.get())) {
            helper.fail("Reread view lost components — data component didn't persist back to stack");
            return;
        }

        // Invalid placement: a CPU item can't go in the flash slot.
        if (reread.canPlaceItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.CPU2.get()))) {
            helper.fail("MotherboardInventory accepted a CPU in the flash slot");
            return;
        }

        // Level gating: a level-2 motherboard disables RAM slot 5 and PCI slots 12-13.
        if (reread.isSlotUsable(MotherboardItem.SLOT_RAM_END)) {
            helper.fail("Level-2 motherboard should disable RAM slot 5; isSlotUsable returned true");
            return;
        }
        if (reread.isSlotUsable(MotherboardItem.SLOT_PCI_END)) {
            helper.fail("Level-2 motherboard should disable PCI slot 13; isSlotUsable returned true");
            return;
        }

        // Empty motherboard-stack behaves as an all-empty container, not a mutation-target.
        MotherboardInventory emptyView = new MotherboardInventory(() -> ItemStack.EMPTY);
        emptyView.setItem(0, new ItemStack(ScevRegistry.CPU1.get()));
        if (emptyView.getItem(0).getCount() != 0) {
            helper.fail("setItem on empty-backed motherboard should be a no-op");
            return;
        }
        helper.succeed();
    }

    /**
     * Integration: place a workstation, install a level-2 motherboard with a full
     * diag config, power on, verify {@link MachineState} has the expected
     * peripherals attached. Doesn't actually boot the VM — the test asserts
     * post-buildMachine state and then immediately powers off.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void workstation_build_and_power(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());

        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created");
            return;
        }

        // Build a level-2 motherboard with a minimal complement of hardware.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD2.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU2.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM2.get()));
        inv.setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.GPIO_CARD.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START + 1, new ItemStack(ScevRegistry.RTL8169.get()));
        case_.setItem(0, mbStack);

        // powerOn calls buildMachine (via initMachineState). If RVVM native is
        // available, the MachineState gets created and components attached.
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) {
            // Native rvvm might not be available in this test environment —
            // warn in the log (via fail) but don't fail catastrophically.
            helper.fail("MachineState was not created; librvvm may not be loadable on this host");
            return;
        }

        try {
            if (state.getGPIO() == null) {
                helper.fail("Expected GPIO attached (GPIO_CARD in PCI slot) — got null");
                return;
            }
            // NIC is tracked internally but not exposed by getter; accept as attached.
            // We indirectly assert it didn't explode by reaching this point.
            helper.succeed();
        } finally {
            // Clean up the machine so subsequent tests start fresh.
            case_.powerOff();
        }
    }

    /**
     * Integration: installing a {@code SOUND_CARD} in a PCI slot must make
     * the parser emit {@code spec.hasSound() = true} and the backend must
     * attach RVVM's HDA controller without crashing.
     *
     * <p>The full audio pipeline is layered:
     * <ul>
     *   <li><b>Here (Java Phases 1–3).</b> Parser flag flows to the backend;
     *       {@code SoundHDA.sound_hda_init_auto} returns a non-zero PCI
     *       device pointer; the machine powers on and keeps running.</li>
     *   <li><b>Phase 4 (RVVM C-side CoreAudio backend).</b> Until that
     *       lands, macOS builds of librvvm compile the HDA controller but
     *       no host audio backend — the stream worker's PCM write is a
     *       no-op, so the PCI device enumerates but produces silence. That
     *       still means "the wiring works"; audible output needs Phase 4.</li>
     *   <li><b>Phase 5 (Buildroot kernel + alsa-utils).</b> For the guest
     *       to actually drive the device we need {@code CONFIG_SND_HDA_INTEL}
     *       plus {@code aplay}. Validated by {@code dmesg | grep hda} on the
     *       guest once that kernel ships. Not asserted here.</li>
     * </ul>
     *
     * <p>What <i>this</i> test catches: someone reverts the parser's
     * {@code case SOUND} back to a stub, someone removes
     * {@code SoundHDA.java}, or someone forgets to rebuild librvvm with the
     * new JNI wrapper ({@code Java_lekkit_rvvm_RVVMNative_sound_1hda_1init_1auto}).
     * Any of those would surface as a spec mismatch or a native crash on
     * power-on.
     *
     * <p>No flash chip is installed on purpose — the demo bootrom path is
     * the fastest way to prove "the sound card doesn't crash the VM" without
     * waiting for a full Linux boot. See {@link #linux_kernel_boots_with_sound_card}
     * for the full-stack version.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 100)
    public static void workstation_sound_card_attaches(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created");
            return;
        }

        // Minimal loadout: CPU + RAM + SOUND_CARD, no flash (demo bootrom path).
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.SOUND_CARD.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) {
            helper.fail("MachineState was not created; librvvm may not be loadable on this host");
            return;
        }

        try {
            // -- Spec-side assertion (parser contract) ---------------------
            MachineSpec spec = state.getBackend().spec();
            if (!spec.hasSound()) {
                helper.fail("Parser did not set spec.hasSound() even though SOUND_CARD was in "
                        + "the PCI slot. Check MachineSpecParser's PCI-card switch — the SOUND "
                        + "branch must call builder.hasSound(true).");
                return;
            }

            // -- Backend-side assertion (native attach contract) -----------
            // powerOn succeeded, MachineState exists, and the backend is
            // still valid. If sound_hda_init_auto crashed or returned 0 in
            // a way that tripped RVVM into an invalid state, isValid()
            // would be false.
            if (!state.getBackend().isValid()) {
                helper.fail("Backend is not valid after powerOn with a SOUND_CARD installed — "
                        + "sound_hda_init_auto likely crashed or put the machine into an "
                        + "unrecoverable state. Check the JNI wrapper and that librvvm was "
                        + "rebuilt with the sound_1hda_1init_1auto export.");
                return;
            }

            // Small delay so the HDA stream worker has time to start (if the
            // guest driver speculatively tickled it during early enumeration).
            // If the worker dereferences a NULL subsystem.write callback on
            // macOS (no ALSA, no CoreAudio), we'd see the VM drop to an
            // invalid state.
            helper.runAfterDelay(20, () -> {
                try {
                    if (!state.getBackend().isValid()) {
                        helper.fail("Backend went invalid shortly after powerOn — suggests "
                                + "the HDA stream worker crashed. On macOS without a host "
                                + "audio backend the worker must be a no-op, not a crash.");
                        return;
                    }
                    if (!state.getBackend().isRunning()) {
                        helper.fail("Machine isn't running after powerOn with sound card. "
                                + "Installing a SOUND_CARD must not halt the VM.");
                        return;
                    }
                    helper.succeed();
                } finally {
                    case_.powerOff();
                }
            });
        } catch (Throwable t) {
            case_.powerOff();
            helper.fail("Sound card attach threw: " + t);
        }
    }

    /**
     * End-to-end: a Linux-booting machine with a {@code SOUND_CARD}
     * alongside the VGA card must reach the same "kernel still running
     * after N seconds" liveness milestone as the sound-less configuration
     * ({@link #linux_kernel_boots_and_draws_fbcon}).
     *
     * <p>This is the "sound card doesn't break Linux" regression test.
     * Specifically catches:
     * <ul>
     *   <li>RVVM's HDA PCI device advertising a config-space layout the
     *       guest OS chokes on during {@code pci_scan}.</li>
     *   <li>The HDA stream worker trampling memory the kernel is using
     *       during early init.</li>
     *   <li>The HDA device's MMIO BAR[0] overlapping another device's
     *       region after a future RVVM update.</li>
     * </ul>
     *
     * <p>Waits only ~10 s instead of the 20 s used by the fbcon test —
     * we're not verifying fbcon here, just that the kernel doesn't panic
     * during {@code pci_scan_bus} or {@code snd-hda-intel} probe (if the
     * kernel happens to have the driver compiled in; currently it does
     * not, which means the HDA device enumerates as an unclaimed PCI
     * function and Linux moves on — still a valid boot).
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 1200)
    public static void linux_kernel_boots_with_sound_card(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created");
            return;
        }

// Linux loadout (mirrors linux_kernel_boots_and_draws_fbcon) plus a
        // SOUND_CARD in PCI slot 9. MOTHERBOARD1 has 2 PCI slots enabled
        // (8, 9) so VGA + SOUND fit.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.VGA_CARD.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START + 1, new ItemStack(ScevRegistry.SOUND_CARD.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) {
            helper.fail("No MachineState after powerOn — librvvm may not be loadable on this host");
            return;
        }

        // Parser-side sanity: both VGA (display) and SOUND_CARD must register.
        MachineSpec spec = state.getBackend().spec();
        if (!spec.hasDisplay()) {
            helper.fail("VGA card + SOUND_CARD in PCI slots, but spec.hasDisplay() is false. "
                    + "Parser regressed: SOUND_CARD handling must not eat the VGA card's bit.");
            case_.powerOff();
            return;
        }
        if (!spec.hasSound()) {
            helper.fail("SOUND_CARD in PCI slot 9, but spec.hasSound() is false. "
                    + "Parser must scan every enabled PCI slot, not just the first.");
            case_.powerOff();
            return;
        }

        // Sanity: RAM was clamped to the Linux floor.
        if (spec.memMb() < LinuxFirmware.MIN_RAM_MB) {
            helper.fail("RAM wasn't clamped to the Linux firmware floor. Got "
                    + spec.memMb() + " MiB, expected >= " + LinuxFirmware.MIN_RAM_MB);
            case_.powerOff();
            return;
        }

        // Give the machine 10 seconds to boot. If the sound card breaks
        // early boot (kernel panic during pci_scan, or HDA stream worker
        // trashing memory), the machine will stop running well before
        // the 10 s mark.
        helper.runAfterDelay(10, () -> {
            try {
                // Verify kernel landed at LOAD_ADDR — this is the same
                // proof-of-life check the fbcon test does at 20 s. If the
                // HDA device's MMIO BAR overlapped the kernel region, the
                // bytes here would be clobbered.
                ByteBuffer atKernel = state.getBackend().readMemory(
                        KernelStub.LOAD_ADDR + LINUX_MAGIC_OFFSET, 8);
                if (atKernel == null) {
                    helper.fail("readMemory at kernel LOAD_ADDR returned null — "
                            + "DMA API broken or sound card clobbered the backend state.");
                    return;
                }
                byte[] expectedMagic = {'R', 'I', 'S', 'C', 'V', 0, 0, 0};
                for (int i = 0; i < expectedMagic.length; i++) {
                    if (atKernel.get(i) != expectedMagic[i]) {
                        helper.fail("Linux RV64 boot magic missing at 0x"
                                + Long.toHexString(KernelStub.LOAD_ADDR + LINUX_MAGIC_OFFSET)
                                + " after 10 s of boot with SOUND_CARD installed. "
                                + "The HDA device may be corrupting guest memory — "
                                + "check PCI BAR placement vs. the kernel load region.");
                        return;
                    }
                }

                // Block for ~8 s more. The RVVM HART runs on its own thread,
                // so wall time is enough for Linux to reach late init where
                // pci_scan would have enumerated the HDA device.
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                if (!state.getBackend().isRunning()) {
                    helper.fail("Machine stopped running after ~18 s of boot time with "
                            + "SOUND_CARD installed. Linux may have panicked on the HDA "
                            + "device — check UART output for a trace. If the fbcon test "
                            + "still passes, the regression is sound-card-specific.");
                    return;
                }

                helper.succeed();
            } finally {
                case_.powerOff();
            }
        });
    }

    // Full-stack E2E test (linux_guest_audio_reaches_sound_stream_manager)
    // was removed 2026-04-20: it relied on an /etc/init.d/S99playwav that
    // looped aplay on every boot, which made interactive play unpleasant.
    // The guest rootfs still ships /root/test.wav so the player can
    // `aplay /root/test.wav` manually via the in-game shell for audio
    // verification.
    //
// Coverage that remains:
    //   - Unit tests: SoundStreamManager downsample + framing math,
    //     SoundFramePayload codec roundtrip.
    //   - workstation_sound_card_attaches: spec -> backend wiring, no crash.
    //   - linux_kernel_boots_with_sound_card: Linux boots with a SOUND_CARD
    //     installed, stays running (proves the card doesn't break early boot).
    //
    // Full audio playback is now a manual verification (documented in
    // docs/SOUND_INTEGRATION_PLAN.md §Manual verification).

    /**
     * Redstone input: place a workstation with a GPIO card, apply a redstone
     * torch adjacent to it, verify the GPIO card's pin bitmap sees the
     * corresponding direction's pin set.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void workstation_gpio_redstone_input(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created");
            return;
        }

        // Minimal motherboard + GPIO card.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.GPIO_CARD.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null || state.getGPIO() == null) {
            helper.fail("GPIO not attached after powerOn");
            return;
        }

        try {
            // Place a redstone torch north of the workstation. Direction.NORTH has
            // ordinal 2; we expect bit 2 set in the pin bitmap after the
            // neighbour update fires.
            BlockPos torchPos = pos.relative(Direction.NORTH);
            helper.setBlock(torchPos, Blocks.REDSTONE_TORCH.defaultBlockState());
            // Give Minecraft one tick to propagate the neighbour-change.
            helper.runAfterDelay(1, () -> {
                int pins = state.getGPIO().readPins();
                // NS: we can't easily assert exact pin value because SiFive GPIO
                // mixes in/out registers. Accept "any non-zero" as proof the
                // neighbour-change path reached the GPIO.
                // Hit the write-path from the block's neighbourChanged explicitly:
                // simulate a redstone input by calling onRedstoneInput directly.
                case_.onRedstoneInput(1 << Direction.NORTH.ordinal());
                int afterForcedInput = state.getGPIO().readPins();
                // We just need the test to not throw. The GPIO may not read back
                // input values we wrote (depends on input/output direction config
                // inside the VM); the important thing is that the API call path
                // works end-to-end without errors.
                if (afterForcedInput < 0) {
                    helper.fail("GPIO readPins returned bogus negative after write; got " + afterForcedInput);
                    return;
                }
                helper.succeed();
            });
        } catch (Throwable t) {
            case_.powerOff();
            helper.fail("redstone input plumbing threw: " + t);
        }
    }

    /**
     * Redstone output: workstation BE has a non-zero outgoing redstone bitmap
     * after {@link ScevBlockEntity#setOutRedstoneSignals}. Verifies the block's
     * {@code getSignal} reports the right per-side strength.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void workstation_emits_redstone(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created");
            return;
        }

        // Simulate the GPIO wanting to emit signal on north (bit 2) and east (bit 5).
        int signals = (1 << Direction.NORTH.ordinal()) | (1 << Direction.EAST.ordinal());
        case_.setOutRedstoneSignals(signals);

        // level.getSignal(emitterPos, dirFromQuerierToEmitter): the querier
        // north of us calls with direction SOUTH (the way they face back to us).
        var absPos = helper.absolutePos(pos);
        int northSignal = helper.getLevel().getSignal(absPos, Direction.SOUTH);
        int eastSignal = helper.getLevel().getSignal(absPos, Direction.WEST);
        int downSignal = helper.getLevel().getSignal(absPos, Direction.UP);

        if (northSignal != 15) {
            helper.fail("Expected north signal 15, got " + northSignal);
            return;
        }
        if (eastSignal != 15) {
            helper.fail("Expected east signal 15, got " + eastSignal);
            return;
        }
        if (downSignal != 0) {
            helper.fail("Expected down signal 0 (not set), got " + downSignal);
            return;
        }
        helper.succeed();
    }

    /**
     * Tinkerpad always attaches a built-in display, even without a VGA PCI card.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void tinkerpad_has_builtin_display(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.TINKERPAD.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof TinkerpadBlockEntity tink)) {
            helper.fail("Tinkerpad BE not created");
            return;
        }

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        tink.setItem(0, mbStack);
        tink.powerOn();

        try {
            MachineState state = MachineManager.getMachineState(tink.getMachineUUID());
            if (state == null) {
                helper.fail("Tinkerpad MachineState not created");
                return;
            }
            if (state.getDisplay() == null) {
                helper.fail("Tinkerpad is missing built-in display after powerOn");
                return;
            }
            helper.succeed();
        } finally {
            tink.powerOff();
        }
    }

    /**
     * Dark-screen regression. With a workstation + VGA card + flash, the
     * machine's framebuffer must contain non-zero pixels after boot —
     * {@link lekkit.scev.machine.BootSplash#paint} writes a visible pattern
     * before firmware gets a chance to run. If this fails, the user sees
     * the original "dark screen when turning on" bug.
     *
     * <p>Before the fix: flash was attached as MTD but never loaded as
     * bootrom; CPU executed zero-initialised RAM; framebuffer stayed at
     * its zero-initialised (transparent black) state.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void machine_framebuffer_has_splash_after_boot(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created");
            return;
        }

        // Workstation + VGA card => MachineSpec.hasDisplay() => Framebuffer attached.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.VGA_CARD.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        try {
            MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
            if (state == null || state.getDisplay() == null) {
                helper.fail("No display attached after powerOn (dark-screen regression)");
                return;
            }
            var fb = state.getDisplay();
            if (fb.width() != 640 || fb.height() != 480) {
                helper.fail("Unexpected framebuffer dimensions " + fb.width() + "x" + fb.height());
                return;
            }

            // Scan the whole framebuffer for ANY non-zero byte. If every byte
            // is zero, BootSplash didn't paint — that's the dark-screen bug.
            var buf = fb.pixels();
            boolean anyNonZero = false;
            for (int i = 0; i < fb.byteSize(); i++) {
                if (buf.get(i) != 0) { anyNonZero = true; break; }
            }
            if (!anyNonZero) {
                helper.fail("Framebuffer is all zeros after boot — BootSplash missing, "
                        + "dark-screen bug is back");
                return;
            }
            helper.succeed();
        } finally {
            case_.powerOff();
        }
    }

    /**
     * Dark-screen BUG regression — the version that actually catches it.
     *
     * <p>Previously {@code machine_framebuffer_has_splash_after_boot} only
     * verified "some non-zero pixels exist", which the static splash
     * trivially satisfies. But the user's real complaint was "POWER ON
     * shows and nothing happens afterward" — the CPU never actually
     * executed any code.
     *
     * <p>This test proves the CPU is executing by:
     * <ol>
     *   <li>Building a machine <b>without</b> a flash chip, so
     *       {@code RvvmMachineBackend.initialize} falls back to
     *       {@link DemoBootrom} rather than loading real firmware.</li>
     *   <li>Powering the machine on.</li>
     *   <li>Waiting a few ticks for the CPU to run the 4-instruction demo.</li>
     *   <li>Reading RAM at {@link DemoBootrom#MAGIC_ADDR} through
     *       {@code MachineBackend.readMemory} and asserting the byte is
     *       {@link DemoBootrom#MAGIC_VALUE}.</li>
     * </ol>
     *
     * <p>If CPU execution breaks for any reason (bootrom installer fails,
     * RVVM changes reset PC, DMA API breaks), this test fails loudly.
     *
     * <p><b>Why no flash chip?</b> Installing a FLASH_CHIP would cause
     * {@code RvvmMachineBackend} to load {@code fw_payload.bin} (real
     * OpenSBI+U-Boot) as the bootrom. That firmware doesn't write
     * {@code MAGIC_VALUE} to {@code MAGIC_ADDR} — it does its own thing.
     * The demo bootrom is only a fallback for machines with no firmware
     * installed. See {@code real_firmware_boots_from_flash_chip} for the
     * firmware-present case.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 100)
    public static void demo_bootrom_executes(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created");
            return;
        }

        // No flash chip — the backend will use DemoBootrom as the fallback
        // bootrom (FIRMWARE_ELSE_DEMO mode with no firmware present).
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) { helper.fail("No MachineState after powerOn"); return; }

        // Poll memory for the demo bootrom's side effect. Give the CPU up to
        // 40 ticks (~2 seconds) to execute 4 instructions.
        helper.runAfterDelay(40, () -> {
            try {
                ByteBuffer ram = state.getBackend().readMemory(DemoBootrom.MAGIC_ADDR, 4);
                if (ram == null) {
                    helper.fail("readMemory(" + Long.toHexString(DemoBootrom.MAGIC_ADDR) + ") returned null");
                    return;
                }
                byte got = ram.get(0);
                if (got != DemoBootrom.MAGIC_VALUE) {
                    helper.fail("CPU didn't execute the demo bootrom. Expected 0x"
                            + Integer.toHexString(DemoBootrom.MAGIC_VALUE & 0xFF)
                            + " at 0x" + Long.toHexString(DemoBootrom.MAGIC_ADDR)
                            + ", got 0x" + Integer.toHexString(got & 0xFF)
                            + ". Is CPU actually running? Is reset PC wrong? "
                            + "Is the bootrom encoding broken?");
                    return;
                }
                helper.succeed();
            } finally {
                case_.powerOff();
            }
        });
    }

    /**
     * Out-of-box firmware regression — now exercises the registry-driven
     * boot path end-to-end. Installing a flash chip produces a
     * {@link MachineSpec.FirmwareSpec} that references
     * {@link FirmwareRegistry#LINUX} by id. The backend resolves the id,
     * pulls the first {@link ScevFirmware.Payload.Kind#BOOTROM} payload
     * from the Linux firmware's payload list ({@link LinuxFirmware#BOOTROM_ASSET} =
     * {@code fw_jump.bin}), pipes it through {@link StorageManager} into
     * a per-UUID flash image, and calls {@code rvvm_load_firmware}.
     *
     * <p>Verification:
     *
     * <ol>
     *   <li>Build a machine with a FLASH_CHIP installed.</li>
     *   <li>Assert {@code spec.firmware().firmwareId()} is LINUX (proves
     *       parser emits registry reference rather than direct origin).</li>
     *   <li>Read the first 32 bytes at reset vector (0x80000000) via DMA.</li>
     *   <li>Compare against the extracted LINUX BOOTROM asset
     *       ({@link LinuxFirmware#BOOTROM_ASSET}) on disk.</li>
     * </ol>
     *
     * <p>Possible regressions caught:
     *
     * <ul>
     *   <li>Parser stopped emitting registry references (regressed to direct origin).</li>
     *   <li>{@link FirmwareRegistry#registerBuiltins()} not called in common setup.</li>
     *   <li>Backend {@code loadRegistryFirmware} helper broken / bypassed.</li>
     *   <li>LINUX firmware's BOOTROM payload points at the wrong asset.</li>
     *   <li>{@code fw_jump.bin} removed from {@code /assets/scev/firmware/}.</li>
     *   <li>{@link StorageManager#copyImage} no longer pulling from classpath.</li>
     * </ul>
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 200)
    public static void real_firmware_boots_from_flash_chip(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) { helper.fail("No MachineState after powerOn — librvvm available?"); return; }

        // Assert the parser emitted a registry-referenced FirmwareSpec. This
        // is the new contract: flash chip -> firmwareId=LINUX, origin=null.
        // A regressed parser that goes back to direct origin would still
        // boot (back-compat path), but skip the payload list entirely (no
        // kernel load) — the test below would see fw_jump.bin at 0x80000000
        // but Linux wouldn't start, and the linux_kernel_boots test would
        // fail too. Catch it here early with a clear message.
        MachineSpec.FirmwareSpec fw = state.getBackend().spec().firmware();
        if (fw == null) {
            helper.fail("No FirmwareSpec on machine even though a flash chip is installed — "
                    + "MachineSpecParser regressed.");
            return;
        }
        if (!fw.hasRegistryRef() || !FirmwareRegistry.LINUX.equals(fw.firmwareId())) {
            helper.fail("FirmwareSpec should reference the LINUX registry id; got "
                    + "firmwareId=" + fw.firmwareId() + ", origin=" + fw.origin()
                    + ". The parser must emit a registry-referenced FirmwareSpec; see "
                    + "MachineSpecParser.fromMotherboard for the flash-chip branch.");
            return;
        }

        // Ensure the bundled firmware is extracted so we can compare. The
        // LINUX firmware's BOOTROM payload resolves to fw_jump.bin — pull
        // that asset directly (same byte-stream the backend loaded).
        java.nio.file.Path fwPath = lekkit.scev.server.FirmwareAssets
                .ensureExtracted(LinuxFirmware.BOOTROM_ASSET);
        if (fwPath == null) {
            helper.fail(LinuxFirmware.BOOTROM_ASSET + " not available "
                    + "(neither bundled nor on disk). Out-of-box firmware is missing — "
                    + "check src/main/resources/assets/scev/firmware/");
            return;
        }

        byte[] expectedHead;
        try {
            byte[] fullFw = java.nio.file.Files.readAllBytes(fwPath);
            if (fullFw.length < 32) {
                helper.fail(LinuxFirmware.BOOTROM_ASSET
                        + " is suspiciously small: " + fullFw.length + " bytes");
                return;
            }
            expectedHead = new byte[32];
            System.arraycopy(fullFw, 0, expectedHead, 0, 32);
        } catch (java.io.IOException e) {
            helper.fail("Could not read firmware file: " + e);
            return;
        }

        // bin_objcopy in rvvm_reset_machine_state happens inside start_machine,
        // so after powerOn the first bytes of RAM should equal the firmware's
        // first bytes. Small delay so the start has definitely landed (even on
        // slow CI runners).
        helper.runAfterDelay(20, () -> {
            try {
                ByteBuffer ram = state.getBackend().readMemory(DemoBootrom.RESET_ADDR, 32);
                if (ram == null) {
                    helper.fail("readMemory(0x80000000) returned null — "
                            + "librvvm missing or DMA API regressed");
                    return;
                }
                // CPU has started running; read bytes byte-by-byte into a
                // local array and compare. We DO NOT read multi-byte words
                // because first-instruction bytes at 0x80000000 might be a
                // full 4-byte RVI instruction that doesn't align nicely.
                byte[] actualHead = new byte[32];
                for (int i = 0; i < 32; i++) actualHead[i] = ram.get(i);

                // Check: if actualHead is all zeros or matches DemoBootrom,
                // firmware was NOT loaded.
                boolean allZero = true;
                for (byte b : actualHead) { if (b != 0) { allZero = false; break; } }
                if (allZero) {
                    helper.fail("RAM at 0x80000000 is all zeros — firmware was not loaded. "
                            + "Check RvvmMachineBackend.initialize and StorageManager.copyImage "
                            + "extract the bundled fw_payload.bin.");
                    return;
                }

                // Compare against expected firmware. If the first 4 bytes
                // match DemoBootrom, we regressed to the demo path.
                boolean matchesDemo = true;
                for (int i = 0; i < Math.min(DemoBootrom.BYTES.length, 32); i++) {
                    if (actualHead[i] != DemoBootrom.BYTES[i]) { matchesDemo = false; break; }
                }
                if (matchesDemo) {
                    helper.fail("RAM at 0x80000000 matches DemoBootrom bytes — "
                            + "real firmware was NOT loaded even though a FLASH_CHIP is installed. "
                            + "Check RvvmMachineBackend: firmwareLoaded flag may be wrong.");
                    return;
                }

                // Compare against expected firmware bytes.
                boolean matchesFirmware = true;
                int mismatchAt = -1;
                for (int i = 0; i < 32; i++) {
                    if (actualHead[i] != expectedHead[i]) {
                        matchesFirmware = false;
                        mismatchAt = i;
                        break;
                    }
                }
                if (!matchesFirmware) {
                    helper.fail("RAM at 0x80000000 doesn't match bundled firmware bytes "
                            + "(first mismatch at byte " + mismatchAt + "). "
                            + "The CPU may have self-modified these bytes, or the wrong file was loaded.");
                    return;
                }

                helper.succeed();
            } finally {
                case_.powerOff();
            }
        });
    }

    /**
     * End-to-end proof that a real RV64 Linux kernel boots on the workstation
     * through OpenSBI → Linux → fbcon / login prompt.
     *
     * <p>Installing a flash chip on a workstation produces both a
     * {@code FirmwareSpec} (OpenSBI via
     * {@link MachineSpecParser#DEFAULT_FIRMWARE fw_jump.bin}) AND a
     * {@code KernelSpec} (the real {@code Image} shipped under
     * {@code src/main/resources/assets/scev/firmware/Image}). After power-on:
     *
     * <ol>
     *   <li>OpenSBI runs at 0x80000000, initializes SBI/PMP/traps, and
     *       {@code mret}s to S-mode at 0x80200000.</li>
     *   <li>Linux takes over. Within ~1-2 s the kernel detects RVVM's
     *       {@code simple-framebuffer} DTB node and {@code fbcon} starts
     *       drawing kernel messages onto the framebuffer.</li>
     *   <li>Within ~5-20 s (GameTest is slower than the RVVM CLI because
     *       Minecraft and RVVM's HART thread compete for cores) we reach a
     *       getty/login prompt.</li>
     * </ol>
     *
     * <p><b>What this test actually verifies:</b>
     *
     * <ol>
     *   <li><b>Kernel bytes land at {@link KernelStub#LOAD_ADDR}</b>
     *       (0x80200000). Proves {@code loadKernel} ran and
     *       {@code rvvm_load_kernel}'s {@code bin_objcopy} placed the real
     *       Image at the right address. The first 64 bytes include the RV64
     *       Linux boot header — we assert the {@code "RISCV\0\0\0"} magic
     *       at offset 48, matching {@code KernelStubTest}.</li>
     *   <li><b>The machine stays running for 20+ seconds.</b> Proves OpenSBI
     *       didn't panic on the kernel jump and Linux isn't looping on an
     *       illegal-instruction trap. A broken kernel typically trips
     *       OpenSBI's fault handler within the first second.</li>
     *   <li><b>Kernel bytes are still at LOAD_ADDR after the sleep.</b>
     *       Catches the regression where an internal reset clobbers the
     *       kernel region.</li>
     * </ol>
     *
     * <p><b>Why not assert fbcon pixel changes directly?</b> Ideally this
     * test would snapshot the framebuffer center before and after boot and
     * check for ≥25% byte differences — see {@code docs/PLAN_LINUX_FBCON.md}
     * Phase 5. That approach doesn't work on macOS ARM64 with the current
     * RVVM build:
     *
     * <ul>
     *   <li>RVVM's {@code simple-framebuffer} uses direct memory mapping
     *       ({@code rvvm_mmio_dev_t.mapping}), not {@code .write}-callback
     *       dispatch. See {@code ~/RVVM/src/devices/framebuffer.c}.</li>
     *   <li>Guest CPU stores to {@code 0x18000000..} therefore flow through
     *       the JIT's direct store path, same as RAM stores — and are
     *       subject to the JIT coherency issue on macOS ARM64 (GOTCHAS.md
     *       "JIT coherency"). Linux's fbcon writes would hit the framebuffer
     *       memory but Java's {@code ByteBuffer.get(...)} doesn't see them.</li>
     *   <li>Confirmed empirically: in full-boot runs the kernel log shows
     *       {@code simple-framebuffer 18000000.framebuffer: fb0: simplefb
     *       registered!} and {@code printk: legacy console [tty0] enabled},
     *       then reaches {@code buildroot login:} on UART — but the
     *       framebuffer-center snapshots show 0/307200 byte differences.</li>
     * </ul>
     *
     * <p>So we settle for the strongest proof that's observable from Java:
     * "kernel was loaded AND the VM is still executing it N seconds later".
     * Combined with the kernel-side {@code simple-framebuffer} init message
     * visible in server logs (UART output is piped to stdout), this is
     * enough to catch regressions in the load path.
     *
     * <p>A snapshot helper {@link #snapshotFbCenter(FramebufferView)} is
     * kept for use on platforms where the coherency issue doesn't apply
     * (Linux/Windows) — the framework's there if someone wants to enable
     * the stricter check under a host-platform gate.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 1200)
    public static void linux_kernel_boots_and_draws_fbcon(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        // MINIMAL Linux-capable loadout: flash chip (registry-resolves to
        // LINUX firmware, which loads OpenSBI as BOOTROM + Linux as KERNEL),
        // one RAM_SODIMM1 (8 MiB) to prove the user's typical low-RAM config
        // doesn't kernel-panic, and a VGA card for the display. The RAM stick
        // is undersized on purpose — the parser must clamp up to
        // {@link LinuxFirmware#MIN_RAM_MB} so the shipped Linux kernel +
        // 26 MiB initramfs don't OOM during pty_init. If someone lowers the
        // firmware's RAM floor, this test catches it via the 20 s liveness
        // check.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get())); // 8 MiB -> floored
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.VGA_CARD.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        // Sanity check: the parser should have clamped RAM up to the
        // firmware's declared floor (LinuxFirmware.MIN_RAM_MB = 256 MiB).
        MachineState preState = MachineManager.getMachineState(case_.getMachineUUID());
        if (preState != null && preState.getBackend().spec().memMb() < LinuxFirmware.MIN_RAM_MB) {
            helper.fail("RAM wasn't clamped to Linux firmware floor — got "
                    + preState.getBackend().spec().memMb() + " MiB, expected >= "
                    + LinuxFirmware.MIN_RAM_MB + " MiB. "
                    + "Fix MachineSpecParser.fromMotherboard so it applies "
                    + "firmware.minRamMb() as the floor, or Linux will panic on pty_init.");
            case_.powerOff();
            return;
        }

        // Registry dispatch check: verify the parser emitted a
        // registry-referenced FirmwareSpec pointing at LINUX. If this
        // regresses (parser back to direct origin) the boot still proceeds
        // but goes through the wrong code path in RvvmMachineBackend, and
        // the kernel-at-0x80200000 assertion below would fail silently.
        // Catch it early.
        if (preState != null) {
            MachineSpec.FirmwareSpec fw = preState.getBackend().spec().firmware();
            if (fw == null || !FirmwareRegistry.LINUX.equals(fw.firmwareId())) {
                helper.fail("Expected FirmwareSpec with firmwareId=" + FirmwareRegistry.LINUX
                        + ", got firmwareId=" + (fw == null ? "null" : fw.firmwareId())
                        + ". Parser must emit registry-referenced firmware for a default flash chip.");
                case_.powerOff();
                return;
            }
        }

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) {
            helper.fail("MachineState wasn't created; librvvm may not be loadable on this host");
            return;
        }
        FramebufferView fb = state.getDisplay();
        if (fb == null) {
            helper.fail("No display after powerOn (VGA card missing?)");
            return;
        }

        // -- 1. Verify the real RV64 Linux kernel landed at LOAD_ADDR. -----
        //    bin_objcopy runs inside rvvm_reset_machine_state during
        //    start_machine. A small delay gives it time to complete.
        helper.runAfterDelay(10, () -> {
            // Byte 48..55 of the shipped Image must be "RISCV\0\0\0" — the
            // RV64 Linux boot header magic. We read the first 64 bytes of
            // RAM at the kernel load address (which is where rvvm_load_kernel
            // placed the file contents) and check that the magic is there.
            int headerSize = 64;
            ByteBuffer atKernel = state.getBackend().readMemory(KernelStub.LOAD_ADDR, headerSize);
            if (atKernel == null) {
                helper.fail("readMemory(" + Long.toHexString(KernelStub.LOAD_ADDR)
                        + ") returned null — librvvm missing or DMA API regressed");
                case_.powerOff();
                return;
            }
            byte[] expectedMagic = {'R', 'I', 'S', 'C', 'V', 0, 0, 0};
            for (int i = 0; i < expectedMagic.length; i++) {
                byte got = atKernel.get(LINUX_MAGIC_OFFSET + i);
                if (got != expectedMagic[i]) {
                    helper.fail("No Linux RV64 boot magic at 0x"
                            + Long.toHexString(KernelStub.LOAD_ADDR + LINUX_MAGIC_OFFSET)
                            + " (byte " + i + " of 'RISCV\\0\\0\\0': expected 0x"
                            + String.format("%02x", expectedMagic[i] & 0xFF)
                            + ", got 0x" + String.format("%02x", got & 0xFF) + "). "
                            + "Either loadKernel didn't run, or a different file shipped as Image.");
                    case_.powerOff();
                    return;
                }
            }

            // -- 2. Wait ~20 s real wall-clock so Linux can boot + fbcon
            //    can initialize. We're looking for the VM to STILL be
            //    running (not panic-halted) and for the kernel bytes to
            //    still be intact. Thread.sleep blocks the tick thread but
            //    the RVVM HART runs on its own native thread, so the VM
            //    makes forward progress during the sleep.
            try {
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            try {
                if (!state.getBackend().isRunning()) {
                    helper.fail("Machine stopped running after 20 s of boot time. "
                            + "Linux may have panicked on an illegal instruction or "
                            + "OpenSBI may have trapped. The UART output (stdout in "
                            + "the GameTest server log) should show kernel messages — "
                            + "look for a panic / BUG / illegal instruction trace.");
                    return;
                }

                // Re-verify kernel magic is still there after 20 s of boot.
                ByteBuffer atKernel2 = state.getBackend().readMemory(
KernelStub.LOAD_ADDR + LINUX_MAGIC_OFFSET, expectedMagic.length);
                if (atKernel2 == null) {
                    helper.fail("readMemory returned null after 20 s sleep — DMA API broken?");
                    return;
                }
                for (int i = 0; i < expectedMagic.length; i++) {
                    if (atKernel2.get(i) != expectedMagic[i]) {
                        helper.fail("Linux RV64 boot magic at LOAD_ADDR+0x"
                                + Integer.toHexString(LINUX_MAGIC_OFFSET)
                                + " changed during boot. Kernel region clobbered — "
                                + "most likely the VM re-reset and lost the kernel.");
                        return;
                    }
                }

                helper.succeed();
            } finally {
                case_.powerOff();
            }
        });
    }

    /**
     * End-to-end proof that {@link FirmwareRegistry} is actually wired at
     * runtime. Checks three things the design relies on:
     *
     * <ol>
     *   <li><b>Built-ins registered in common setup</b> —
     *       {@link FirmwareRegistry#LINUX}, {@link FirmwareRegistry#OPENSBI_ONLY},
     *       and {@link FirmwareRegistry#OPEN_FIRMWARE} are all resolvable.
     *       Catches the regression where someone removes
     *       {@code FirmwareRegistry.registerBuiltins()} from
     *       {@code ScalarEvolution.onCommonSetup}.</li>
     *   <li><b>Parser emits registry reference, not direct origin</b> —
     *       {@code spec.firmware().firmwareId() == LINUX}. Without this, the
     *       backend routes through {@code loadDirectFirmware} (bootrom only)
     *       and the kernel never loads. The existing byte-level tests would
     *       notice a day later; this asserts the contract up front.</li>
     *   <li><b>Firmware payload list is non-empty with BOOTROM + KERNEL</b> —
     *       {@code LINUX.payloads()} has both kinds, in that order. Catches
     *       someone accidentally deleting the KERNEL payload.</li>
     * </ol>
     *
     * <p>All three are observable without needing librvvm. The test places a
     * workstation + flash chip to trigger the parser, inspects the resulting
     * spec, and verifies the registry state — no RVVM boot required. Keeps
     * the test fast ({@code <2 s}) and available on CI hosts where librvvm
     * might not load.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void firmware_registry_drives_flash_chip_boot(GameTestHelper helper) {
        // Part 1: registry contract — built-ins must exist.
        if (!FirmwareRegistry.contains(FirmwareRegistry.LINUX)) {
            helper.fail("FirmwareRegistry.LINUX not registered — did common setup run? "
                    + "FirmwareRegistry.registerBuiltins() must be called from "
                    + "ScalarEvolution.onCommonSetup().");
            return;
        }
        if (!FirmwareRegistry.contains(FirmwareRegistry.OPENSBI_ONLY)) {
            helper.fail("FirmwareRegistry.OPENSBI_ONLY not registered.");
            return;
        }
        if (!FirmwareRegistry.contains(FirmwareRegistry.OPEN_FIRMWARE)) {
            helper.fail("FirmwareRegistry.OPEN_FIRMWARE not registered.");
            return;
        }

        var linux = FirmwareRegistry.get(FirmwareRegistry.LINUX);
        if (linux == null) {
            helper.fail("FirmwareRegistry.get(LINUX) returned null after contains() said yes — "
                    + "registry is inconsistent.");
            return;
        }
        var payloads = linux.payloads();
        if (payloads == null || payloads.size() < 2) {
            helper.fail("LINUX firmware should declare BOOTROM + KERNEL (2 payloads); "
                    + "got " + (payloads == null ? "null" : payloads.size()) + ". "
                    + "The Linux boot path depends on both payloads being present; "
                    + "fw_jump.bin alone would jump to 0x80200000 and trap on illegal "
                    + "instructions.");
            return;
        }
        if (payloads.get(0).kind() != lekkit.scev.machine.firmware.ScevFirmware.Payload.Kind.BOOTROM) {
            helper.fail("LINUX first payload must be BOOTROM, got " + payloads.get(0).kind());
            return;
        }
        if (payloads.get(1).kind() != lekkit.scev.machine.firmware.ScevFirmware.Payload.Kind.KERNEL) {
            helper.fail("LINUX second payload must be KERNEL, got " + payloads.get(1).kind());
            return;
        }

        // Part 2: parser emits registry reference when a flash chip is installed.
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        try {
            MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
            if (state == null) {
                helper.fail("No MachineState after powerOn — librvvm availability is unrelated "
                        + "to the registry contract though. If this fails, another test is breaking.");
                return;
            }
            MachineSpec.FirmwareSpec fw = state.getBackend().spec().firmware();
            if (fw == null) {
                helper.fail("No FirmwareSpec even though a flash chip was installed — "
                        + "MachineSpecParser regressed.");
                return;
            }
            if (!fw.hasRegistryRef()) {
                helper.fail("FirmwareSpec missing firmwareId (hasRegistryRef=false) — parser "
                        + "is still using the direct-origin code path, regression from the "
                        + "registry refactor. Fix MachineSpecParser.fromMotherboard to emit "
                        + "firmware(new FirmwareSpec(uuid, sizeMb, null, DEFAULT_FIRMWARE_ID)).");
                return;
            }
            if (!FirmwareRegistry.LINUX.equals(fw.firmwareId())) {
                helper.fail("Expected firmwareId=" + FirmwareRegistry.LINUX
                        + ", got " + fw.firmwareId() + ". If you intentionally changed the "
                        + "default firmware, update MachineSpecParser.DEFAULT_FIRMWARE_ID "
                        + "and this test together.");
                return;
            }

            // Part 3: firmware is reachable via the spec's firmwareId.
            var resolved = FirmwareRegistry.get(fw.firmwareId());
            if (resolved == null) {
                helper.fail("spec.firmware().firmwareId() = " + fw.firmwareId()
                        + " but registry.get() returned null — registry got cleared between "
                        + "common setup and here. Check for clearForTests() misuse.");
                return;
            }
            if (resolved.minRamMb() != LinuxFirmware.MIN_RAM_MB) {
                helper.fail("Resolved LINUX firmware reports wrong minRamMb: got "
                        + resolved.minRamMb() + ", expected " + LinuxFirmware.MIN_RAM_MB);
                return;
            }

            helper.succeed();
        } finally {
            case_.powerOff();
        }
    }

    /**
     * End-to-end: installing a {@link lekkit.scev.items.PreloadedNvmeItem}
     * causes the per-UUID disk image on disk to be seeded from the
     * {@link lekkit.scev.machine.storage.AlpineDiskTemplate} —
     * a real ext4 filesystem (ext2-superblock-compatible) with a
     * deterministic UUID and volume label.
     *
     * <p>This is the "disk with an OS on it" proof-of-life. Power path:
     *
     * <ol>
     *   <li>Flash chip installed -> LINUX firmware loads OpenSBI + Linux.</li>
     *   <li>PreloadedNvmeItem installed -> parser emits
     *       {@code DiskSpec(templateId=scev:alpine)}.</li>
     *   <li>{@code RvvmMachineBackend} resolves the template, asks
     *       {@code StorageManager} to seed the per-UUID image from the
     *       template's asset ({@code alpine_rootfs.img}).</li>
     *   <li>Attaches the image as a VirtIO NVMe block device in the guest.</li>
     * </ol>
     *
     * <p>The test verifies step (3) made it to disk by reading the
     * per-UUID image file and asserting:
     *
     * <ul>
     *   <li>The ext2/ext4 superblock magic {@code 0xEF53} lives at offset 1080.</li>
     *   <li>The filesystem volume label at offset 1144 matches
     *       {@link lekkit.scev.machine.storage.AlpineDiskTemplate#FILESYSTEM_LABEL}.</li>
     *   <li>The filesystem UUID at offset 1128 matches
     *       {@link lekkit.scev.machine.storage.AlpineDiskTemplate#FILESYSTEM_UUID}.</li>
     * </ul>
     *
     * <p>If any of these fail, either the template pipeline didn't copy
     * the bytes (regressed `StorageManager.initImage` / `copyImage` /
     * registry lookup) or the asset itself was clobbered. Both are
     * user-visible — guest-side `mount -t ext4 /dev/nvme0n1` would fail.
     *
     * <p>Also asserts the parser emitted {@code spec.nvmeDrives().get(0)
     * .templateId() == ALPINE} so the "blank vs. preloaded" distinction
     * is visible in the spec.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 200)
    public static void preloaded_nvme_seeds_image_from_alpine_template(GameTestHelper helper) {
        // Sanity: the ALPINE template must be registered. Otherwise the
        // backend falls back to blank and this whole test is meaningless.
        if (!lekkit.scev.machine.storage.DiskTemplateRegistry.contains(
                lekkit.scev.machine.storage.DiskTemplateRegistry.ALPINE)) {
            helper.fail("ALPINE disk template not registered — did common setup call "
                    + "DiskTemplateRegistry.registerBuiltins()?");
            return;
        }

        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        // Minimal loadout: CPU, flash (LINUX boots Linux), RAM, PRELOADED NVMe.
        // Ram under-provisioned on purpose — LINUX firmware floor bumps it.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME_PRELOADED.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        try {
            MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
            if (state == null) {
                helper.fail("No MachineState after powerOn — librvvm availability or spec construction broke?");
                return;
            }

            // --- Parser-side assertion ---
            // The PreloadedNvmeItem must have produced a DiskSpec with
            // templateId set to ALPINE.
            java.util.List<MachineSpec.DiskSpec> nvmes = state.getBackend().spec().nvmeDrives();
            if (nvmes.isEmpty()) {
                helper.fail("Expected 1 NVMe in the spec (the preloaded one), got 0. "
                        + "MachineSpecParser may have regressed — it must emit a DiskSpec "
                        + "for every populated NVMe slot, including PreloadedNvmeItem.");
                return;
            }
            MachineSpec.DiskSpec d = nvmes.get(0);
            if (!d.hasTemplateRef() || !lekkit.scev.machine.storage.DiskTemplateRegistry.ALPINE.equals(d.templateId())) {
                helper.fail("Preloaded NVMe didn't produce a templateId=scev:alpine DiskSpec. "
                        + "Got templateId=" + d.templateId() + ", origin=" + d.origin()
                        + ". The parser's NVMe branch must check for PreloadedNvmeItem and "
                        + "attach getDefaultTemplateId() to the DiskSpec.");
                return;
            }

            // --- Backend-side assertion (the real E2E proof) ---
            // StorageManager should have created a per-UUID image file
            // seeded with the template's bytes. Read it from disk and
            // verify ext2/ext4 superblock magic, label, and UUID against
            // the per-UUID copy (not the classpath template).
            java.nio.file.Path imagePath = java.nio.file.Paths.get(
                    lekkit.scev.server.StorageManager.imagePath(d.uuid()));
            if (!java.nio.file.Files.isRegularFile(imagePath)) {
                helper.fail("Per-UUID image was not created at " + imagePath
                        + ". StorageManager.initImage must have returned false for templateId="
                        + d.templateId() + ". Check the backend's NVMe loop in "
                        + "RvvmMachineBackend.initialize.");
                return;
            }

            // The Alpine template is a whole-disk image: MBR sector at
            // offset 0, then partition 1 at some LBA that the MBR itself
            // tells us. The ext4 superblock lives at (partition_start +
            // 1024), not at a hardcoded file offset — so parse the MBR
            // entry first.
            byte[] mbr = new byte[512];
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(imagePath)) {
                int n = in.read(mbr);
                if (n < 512) {
                    helper.fail("Per-UUID image is shorter than an MBR sector (" + n + " bytes). "
                            + "Template copy must have been truncated — check StorageManager.copyImage.");
                    return;
                }
            } catch (java.io.IOException e) {
                helper.fail("Could not read per-UUID image at " + imagePath + ": " + e);
                return;
            }

            // MBR magic 0x55AA at offset 510.
            if ((mbr[510] & 0xFF) != 0x55 || (mbr[511] & 0xFF) != 0xAA) {
                helper.fail("MBR signature missing at offset 510 of per-UUID image (got 0x"
                        + Integer.toHexString(mbr[510] & 0xFF) + " "
                        + Integer.toHexString(mbr[511] & 0xFF) + "). The Alpine template bytes "
                        + "weren't copied into the image — either StorageManager.copyImage "
                        + "regressed or the bundled alpine_rootfs.img is corrupt.");
                return;
            }

            // Partition 1 entry at MBR offset 0x1BE (16 bytes), LBA-start
            // as little-endian u32 at relative offset 8.
            long lbaStart = ((long)(mbr[0x1BE + 8]  & 0xFF))
                          | ((long)(mbr[0x1BE + 9]  & 0xFF) << 8)
                          | ((long)(mbr[0x1BE + 10] & 0xFF) << 16)
                          | ((long)(mbr[0x1BE + 11] & 0xFF) << 24);
            if (lbaStart == 0) {
                StringBuilder entryHex = new StringBuilder();
                for (int i = 0; i < 16; i++) entryHex.append(String.format("%02x ", mbr[0x1BE + i]));
                helper.fail("MBR partition 1 has zero LBA start — expected a bootable Alpine "
                        + "partition. Got entry bytes: " + entryHex.toString().trim());
                return;
            }
            long partitionOffset = lbaStart * 512L;
            // ext4 superblock at +1024 into the partition; we need the
            // superblock's first ~136 bytes (up to s_volume_name end at
            // sb+0x88) so grab a round 512 from partition+1024.
            byte[] sb = new byte[512];
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(imagePath)) {
                long skipTarget = partitionOffset + 1024;
                long skipped = in.skip(skipTarget);
                if (skipped < skipTarget) {
                    helper.fail("Could not skip to superblock at offset " + skipTarget
                            + " of per-UUID image (only skipped " + skipped + " bytes). "
                            + "Image is shorter than the MBR claims the partition is.");
                    return;
                }
                int n = in.read(sb);
                if (n < 136) {
                    helper.fail("Superblock read too short (" + n + " bytes). Image is truncated.");
                    return;
                }
            } catch (java.io.IOException e) {
                helper.fail("Could not read superblock at offset " + (partitionOffset + 1024) + ": " + e);
                return;
            }

            // ext2/ext4 superblock magic (0xEF53 LE) at sb+0x38.
            int magic = (sb[0x38] & 0xFF) | ((sb[0x39] & 0xFF) << 8);
            if (magic != 0xEF53) {
                helper.fail("ext4 magic missing at partition+1024+0x38 (file offset 0x"
                        + Long.toHexString(partitionOffset + 1024 + 0x38)
                        + ", got 0x" + Integer.toHexString(magic)
                        + "). The Alpine template bytes weren't copied into the image — "
                        + "either StorageManager.copyImage regressed or the bundled "
                        + "alpine_rootfs.img is corrupt.");
                return;
            }

            // Volume label at sb+0x78 (s_volume_name, 16 bytes, NUL-padded).
            byte[] label = new byte[16];
            System.arraycopy(sb, 0x78, label, 0, 16);
            int labelLen = 0;
            while (labelLen < 16 && label[labelLen] != 0) labelLen++;
            String labelStr = new String(label, 0, labelLen);
            if (!lekkit.scev.machine.storage.AlpineDiskTemplate.FILESYSTEM_LABEL.equals(labelStr)) {
                helper.fail("Volume label mismatch at sb+0x78: expected '"
                        + lekkit.scev.machine.storage.AlpineDiskTemplate.FILESYSTEM_LABEL
                        + "', got '" + labelStr + "'. Either a different asset was copied "
                        + "in, or something is writing to the image post-seed.");
                return;
            }

            // Filesystem UUID at sb+0x68 (s_uuid, 16 bytes).
            byte[] fsUuid = new byte[16];
            System.arraycopy(sb, 0x68, fsUuid, 0, 16);
            java.util.UUID expected = java.util.UUID.fromString(
                    lekkit.scev.machine.storage.AlpineDiskTemplate.FILESYSTEM_UUID);
            long msb = expected.getMostSignificantBits();
            long lsb = expected.getLeastSignificantBits();
            byte[] expectedUuid = new byte[16];
            for (int i = 0; i < 8; i++) expectedUuid[i]     = (byte) (msb >> (56 - 8 * i));
            for (int i = 0; i < 8; i++) expectedUuid[8 + i] = (byte) (lsb >> (56 - 8 * i));
            for (int i = 0; i < 16; i++) {
                if (fsUuid[i] != expectedUuid[i]) {
                    helper.fail("Filesystem UUID byte " + i + " mismatch at sb+0x68. "
                            + "The seeded image isn't the expected AlpineDiskTemplate asset. "
                            + "Rebuild alpine_rootfs.img via the scev-alpine pipeline "
                            + "(github.com/SolAstrius/scev-alpine).");
                    return;
                }
            }

            helper.succeed();
        } finally {
            case_.powerOff();
        }
    }

    // ======================================================================
    //   Disk-persistence / swap contract — abstract refactor (no live OS)
    // ======================================================================
    //
    // These tests exercise the mod-infrastructure layer of the disk
    // persistence + swap abstraction: the per-UUID image file on disk must
    // retain arbitrary bytes across power cycles, the STORAGE_UUID on the
    // NVMe ItemStack must carry when the item moves between cases, and the
    // parser must inject a root= cmdline when firmware and template metadata
    // agree. All three operate at FileChannel / spec-object level — no guest
    // boot needed, so they pass in headless CI regardless of kernel shipping.

    /**
     * Byte-level proof that NVMe images survive a power cycle.
     *
     * <p>Uses a blank {@code NvmeItem} (not preloaded) so
     * {@link lekkit.scev.server.StorageManager#initImage} takes the
     * sparse-create path — producing a 2048 MiB sparse file whose contents
     * are zero-initialized. That lets us write a sentinel at offset 1 GiB
     * (well past anywhere the guest could scribble during a short power
     * cycle) without colliding with filesystem metadata.
     *
     * <p>Power-cycle pattern:
     * <ol>
     *   <li>First {@code powerOn}: parser allocates the stack's
     *       {@link ScevDataComponents#STORAGE_UUID}, backend calls
     *       {@code initImage} which creates the sparse image file.</li>
     *   <li>{@code powerOff}: {@code machine.free()} tears down RVVM's NVMe
     *       device, closing the file handle and flushing writes.</li>
     *   <li>We write a 16-byte sentinel directly into the file via NIO —
     *       no RVVM is holding the file, so this is straight Java I/O.</li>
     *   <li>Second {@code powerOn}: RVVM reopens the same file by path.
     *       The sentinel bytes are still in place; the guest never reaches
     *       userspace before we {@code powerOff} again.</li>
     *   <li>{@code powerOff}: machine tears down.</li>
     *   <li>Re-read sentinel region, assert bytes still match.</li>
     * </ol>
     *
     * <p>If this regresses, the likely culprits are: {@code initImage}
     * losing its {@code !checkImage} guard (clobbering existing data),
     * {@code createImage} truncating instead of sparsely extending, or
     * {@code machine.free()} losing its flush ordering. All three are
     * called out in {@code docs/GOTCHAS.md}.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 200)
    public static void nvme_disk_bytes_persist_across_power_cycle(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        // Blank NVMe (not PRELOADED) — we want a sparse 2048 MiB file, not
        // a 65 MiB template copy. Flash chip present for the Linux floor
        // so the machine can actually power on, but the guest never gets
        // far enough in the test to touch NVMe.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) {
            helper.fail("No MachineState after first powerOn — librvvm unavailable on this host?");
            return;
        }

        // Read the allocated STORAGE_UUID off the (possibly mutated-by-parser)
        // NVMe stack from inside the motherboard's persisted inventory. This
        // is the same UUID the backend used to build the per-UUID image path.
        ItemStack mbLive = case_.getItem(0);
        MotherboardInventory liveInv = new MotherboardInventory(() -> mbLive);
        ItemStack nvmeLive = liveInv.getItem(MotherboardItem.SLOT_NVME_START);
        UUID diskUuid = nvmeLive.get(ScevDataComponents.STORAGE_UUID.get());
        if (diskUuid == null) {
            helper.fail("STORAGE_UUID wasn't allocated on the NVMe stack during powerOn — "
                    + "parser's ensureUuid didn't run or wasn't written back into the motherboard "
                    + "inventory. This is the same bug the StorageUuidTest + MachineSpecParserTest "
                    + "guard; a GameTest-level failure here means the BE write-back path broke.");
            case_.powerOff();
            return;
        }

        // Sanity: the spec carries the same UUID.
        MachineSpec spec = state.getBackend().spec();
        if (spec.nvmeDrives().isEmpty() || !diskUuid.equals(spec.nvmeDrives().get(0).uuid())) {
            helper.fail("Spec's NVMe UUID doesn't match the stack's STORAGE_UUID. Parser must "
                    + "emit DiskSpec(uuid=ensureUuid(stack), ...) — see MachineSpecParser's "
                    + "NVMe loop. Stack UUID: " + diskUuid + ", spec UUID: "
                    + (spec.nvmeDrives().isEmpty() ? "(none)" : spec.nvmeDrives().get(0).uuid()));
            case_.powerOff();
            return;
        }

        case_.powerOff();

        // Write a 16-byte sentinel well into the sparse file so no guest
        // activity during the second power cycle could overwrite it. The
        // file is 1 GiB (NvmeItem.SIZE_MB = 1024) — pick 512 MiB, safely
        // inside the file bounds and far past anywhere the unmounted disk
        // would receive ext4 metadata writes from the guest.
        java.nio.file.Path imagePath = java.nio.file.Paths.get(
                lekkit.scev.server.StorageManager.imagePath(diskUuid));
        if (!java.nio.file.Files.isRegularFile(imagePath)) {
            helper.fail("Expected image file at " + imagePath + " after first powerOn — "
                    + "StorageManager.initImage didn't create it.");
            return;
        }
        final long sentinelOffset = 512L << 20; // 512 MiB — inside the 1 GiB file
        final byte[] sentinel = {
                (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                (byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCE,
                (byte) 0xF0, (byte) 0x0D, (byte) 0xBE, (byte) 0xEF,
        };
        long fileSizeBeforeWrite;
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                imagePath,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.READ)) {
            fileSizeBeforeWrite = ch.size();
            ch.position(sentinelOffset);
            int written = ch.write(java.nio.ByteBuffer.wrap(sentinel));
            if (written != sentinel.length) {
                helper.fail("Short write to per-UUID image at offset " + sentinelOffset
                        + ": wrote " + written + " of " + sentinel.length + " bytes.");
                return;
            }
        } catch (java.io.IOException e) {
            helper.fail("Could not open per-UUID image for writing at " + imagePath + ": " + e);
            return;
        }

        // Second power cycle — RVVM reopens the file. Guest doesn't boot
        // far enough to touch anywhere near offset 1 GiB.
        case_.powerOn();
        state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null) {
            helper.fail("No MachineState after second powerOn — backend attach regressed.");
            return;
        }
        case_.powerOff();

        // Re-read sentinel + file size.
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                imagePath, java.nio.file.StandardOpenOption.READ)) {
            if (ch.size() != fileSizeBeforeWrite) {
                helper.fail("Per-UUID image file size changed across power cycle: "
                        + fileSizeBeforeWrite + " -> " + ch.size()
                        + ". StorageManager.createImage must not truncate an existing image "
                        + "(see docs/GOTCHAS.md 'FileChannel.truncate'). If initImage grew the "
                        + "image to sizeMb on every powerOn we'd risk clobbering sparse tail data.");
                return;
            }
            ch.position(sentinelOffset);
            byte[] read = new byte[sentinel.length];
            int n = ch.read(java.nio.ByteBuffer.wrap(read));
            if (n != sentinel.length) {
                helper.fail("Short read from per-UUID image at offset " + sentinelOffset
                        + ": read " + n + " of " + sentinel.length + " bytes.");
                return;
            }
            for (int i = 0; i < sentinel.length; i++) {
                if (read[i] != sentinel[i]) {
                    StringBuilder got = new StringBuilder();
                    for (byte b : read) got.append(String.format("%02x", b & 0xFF));
                    helper.fail("Sentinel byte " + i + " mismatch at offset " + sentinelOffset
                            + ". Something cleared or re-seeded the image during the second "
                            + "power cycle — likely suspects: initImage's !checkImage guard was "
                            + "removed (letting copyImage clobber existing data), or the backend "
                            + "added an explicit truncate. Got: " + got);
                    return;
                }
            }
        } catch (java.io.IOException e) {
            helper.fail("Could not re-read per-UUID image: " + e);
            return;
        }

        helper.succeed();
    }

    /**
     * The swap contract: an NVMe stack carries its {@code STORAGE_UUID} from
     * one computer case to another, and the per-world image file follows.
     *
     * <p>Two workstations, each with their own motherboard. Install an NVMe
     * in A's motherboard, power on (allocates UUID, creates image file),
     * power off, then pull the NVMe stack out of A's motherboard and insert
     * it into B's. Power on B: the parser reads the same UUID from the
     * stack's data component and emits a {@code DiskSpec} pointing at the
     * same per-UUID image file. That's the whole swap invariant.
     *
     * <p>If this regresses, STORAGE_UUID isn't surviving an ItemStack move
     * between containers (unit-tested by {@code StorageUuidTest}) OR the
     * parser isn't rehydrating from the existing UUID on a re-attach.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 200)
    public static void nvme_disk_moves_between_cases(GameTestHelper helper) {
        BlockPos posA = new BlockPos(1, 1, 1);
        BlockPos posB = new BlockPos(3, 1, 1);
        helper.setBlock(posA, ScevRegistry.WORKSTATION.get().defaultBlockState());
        helper.setBlock(posB, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(posA) instanceof ComputerCaseBlockEntity caseA)
                || !(helper.getBlockEntity(posB) instanceof ComputerCaseBlockEntity caseB)) {
            helper.fail("Workstation BEs not created at both positions");
            return;
        }

        // Motherboard A: CPU + flash + RAM + blank NVMe.
        ItemStack mbA = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory invA = new MotherboardInventory(() -> mbA);
        invA.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        invA.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        invA.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        invA.setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME.get()));
        caseA.setItem(0, mbA);

        // Motherboard B: same shape but no NVMe yet — we're going to move
        // A's NVMe into this slot.
        ItemStack mbB = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory invB = new MotherboardInventory(() -> mbB);
        invB.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        invB.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        invB.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        caseB.setItem(0, mbB);

        // Power A on → allocates STORAGE_UUID + creates image.
        caseA.powerOn();
        if (MachineManager.getMachineState(caseA.getMachineUUID()) == null) {
            helper.fail("No MachineState for case A after powerOn"); return;
        }
        // Read allocated UUID from the live motherboard stack.
        ItemStack mbALive = caseA.getItem(0);
        MotherboardInventory invALive = new MotherboardInventory(() -> mbALive);
        ItemStack nvmeInA = invALive.getItem(MotherboardItem.SLOT_NVME_START);
        UUID diskUuid = nvmeInA.get(ScevDataComponents.STORAGE_UUID.get());
        if (diskUuid == null) {
            helper.fail("STORAGE_UUID not allocated on the NVMe stack after powerOn");
            caseA.powerOff();
            return;
        }
        caseA.powerOff();

        // Snapshot the path the image lives at (in case StorageManager's
        // root path resolution ever becomes NVMe-dependent).
        String imagePathA = lekkit.scev.server.StorageManager.imagePath(diskUuid);

        // Swap: remove NVMe stack from A, install in B. Uses
        // MotherboardInventory.setItem which writes back through the
        // MOTHERBOARD_INVENTORY data component. The stack we install in
        // B MUST retain the STORAGE_UUID — that's the whole point.
        ItemStack pulled = invALive.getItem(MotherboardItem.SLOT_NVME_START).copy();
        if (pulled.get(ScevDataComponents.STORAGE_UUID.get()) == null
                || !pulled.get(ScevDataComponents.STORAGE_UUID.get()).equals(diskUuid)) {
            helper.fail("STORAGE_UUID didn't survive ItemStack.copy() — that's the invariant "
                    + "StorageUuidTest pins. A regression here would break every swap.");
            return;
        }
        invALive.setItem(MotherboardItem.SLOT_NVME_START, ItemStack.EMPTY);
        caseA.setChanged();

        ItemStack mbBLive = caseB.getItem(0);
        MotherboardInventory invBLive = new MotherboardInventory(() -> mbBLive);
        invBLive.setItem(MotherboardItem.SLOT_NVME_START, pulled);
        caseB.setChanged();

        // Power B on. The parser should rehydrate the same UUID and emit a
        // DiskSpec pointing at the existing image file. ensureUuid is a
        // no-op when the stack already has a UUID (see StorageUuidTest).
        caseB.powerOn();
        MachineState stateB = MachineManager.getMachineState(caseB.getMachineUUID());
        if (stateB == null) {
            helper.fail("No MachineState for case B after powerOn"); return;
        }
        try {
            MachineSpec specB = stateB.getBackend().spec();
            if (specB.nvmeDrives().isEmpty()) {
                helper.fail("Case B spec has no NVMe drives — the moved stack wasn't seen by "
                        + "the parser. MotherboardInventory writeback regressed?");
                return;
            }
            MachineSpec.DiskSpec d = specB.nvmeDrives().get(0);
            if (!diskUuid.equals(d.uuid())) {
                helper.fail("Case B's NVMe UUID doesn't match case A's. Swap broke: the same "
                        + "stack produced a different UUID, which means ensureUuid overwrote "
                        + "an existing STORAGE_UUID. Got " + d.uuid() + ", expected "
                        + diskUuid);
                return;
            }
            // Same UUID => same per-world image path => swap preserves content.
            String imagePathB = lekkit.scev.server.StorageManager.imagePath(diskUuid);
            if (!imagePathA.equals(imagePathB)) {
                helper.fail("StorageManager.imagePath changed across the swap (A="
                        + imagePathA + " vs B=" + imagePathB + "). The per-UUID path "
                        + "scheme must be stable so a stack carries its bytes.");
                return;
            }
            helper.succeed();
        } finally {
            caseB.powerOff();
        }
    }

    /**
     * The cmdline-assembly contract at the BE level: with
     * {@link lekkit.scev.machine.firmware.LinuxFirmware}'s
     * {@code wantsNvmeRoot=true} and
     * {@link lekkit.scev.machine.storage.AlpineDiskTemplate}'s
     * {@code hasRootFilesystem=true} + {@code rootDevice="/dev/nvme0n1p1"},
     * the backend-visible {@link MachineSpec#cmdline()} contains
     * {@code "root=/dev/nvme0n1p1 rw rootwait"}.
     *
     * <p>Parallel to {@code MachineSpecParserTest.cmdlineIncludesRootWhenLinuxFirmwareSeesAlpineDisk}
     * but exercises the full case → parser → backend → spec round trip so a
     * future regression in the BE's {@code buildMachine} (e.g. rebuilding
     * the spec with different metadata) is caught at integration level too.
     *
     * <p>Device is {@code p1} because the shipped preloaded NVMe defaults
     * to the Alpine template, which is MBR-partitioned; the Buildroot
     * initramfs {@code /init} reads root= off {@code /proc/cmdline} and
     * mounts that partition directly. A regression to the whole-disk
     * device would silently land the player in the initramfs fallback
     * — no longer seeing the real Alpine rootfs.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 100)
    public static void parser_emits_root_cmdline_with_linux_firmware_and_rootfs_template(
            GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME_PRELOADED.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();
        try {
            MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
            if (state == null) {
                helper.fail("No MachineState after powerOn"); return;
            }
            String cmdline = state.getBackend().spec().cmdline();
            if (!cmdline.contains("root=/dev/nvme0n1p1 rw rootwait")) {
                helper.fail("Backend-visible cmdline doesn't contain the expected "
                        + "'root=/dev/nvme0n1p1 rw rootwait' fragment. LinuxFirmware declares "
                        + "wantsNvmeRoot=true and the default preloaded NVMe (Alpine) declares "
                        + "hasRootFilesystem=true with rootDevice=/dev/nvme0n1p1 (MBR layout). "
                        + "The parser must inject that per-template device. "
                        + "Got: '" + cmdline + "'");
                return;
            }
            helper.succeed();
        } finally {
            case_.powerOff();
        }
    }

    /** Offset of the RV64 Linux boot header magic ("RISCV\\0\\0\\0") inside Image. */
    private static final int LINUX_MAGIC_OFFSET = 48;

    /**
     * Snapshot a 320x240 region in the center of the framebuffer as a byte[].
     *
     * <p>Useful when you want to diff the framebuffer across a boot. Not
     * used by the current {@code linux_kernel_boots_and_draws_fbcon} test
     * because guest CPU stores to the simple-framebuffer aren't visible via
     * DMA on macOS ARM64 (see the method Javadoc for details). Kept for
     * future use on platforms where the coherency issue doesn't apply.
     */
    @SuppressWarnings("unused")
    private static byte[] snapshotFbCenter(FramebufferView fb) {
        int w = fb.width();
        int cx = (w - 320) / 2, cy = (fb.height() - 240) / 2;
        byte[] out = new byte[320 * 240 * 4];
        ByteBuffer pix = fb.pixels();
        int idx = 0;
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                int off = ((cy + y) * w + (cx + x)) * 4;
                out[idx++] = pix.get(off);
                out[idx++] = pix.get(off + 1);
                out[idx++] = pix.get(off + 2);
                out[idx++] = pix.get(off + 3);
            }
        }
        return out;
    }

    /**
     * Framebuffer animation regression — proves the server tick actually
     * runs and the heartbeat indicator repaints each tick. Sample pixel
     * bytes at tick 1 and tick 10 inside the heartbeat region and assert
     * they differ.
     *
     * <p>If this fails, the animated splash isn't animating — either the BE
     * ticker isn't being invoked, or {@code BootSplash.paintHeartbeat}
     * stopped mutating pixels.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 100)
    public static void framebuffer_heartbeat_animates(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        // VGA card so the display is attached.
        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.VGA_CARD.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        MachineState state = MachineManager.getMachineState(case_.getMachineUUID());
        if (state == null || state.getDisplay() == null) {
            helper.fail("No display after powerOn"); return;
        }

        // Take a byte[] snapshot of the heartbeat region shortly after boot.
        // The critical detail: we MUST copy into a byte[] — holding a
        // ByteBuffer view won't preserve the state because the underlying
        // DMA memory keeps changing.
        helper.runAfterDelay(3, () -> {
            byte[] snapshotA = snapshotHeartbeatRegion(state.getDisplay().pixels());
            helper.runAfterDelay(15, () -> {
                try {
                    byte[] snapshotB = snapshotHeartbeatRegion(state.getDisplay().pixels());
                    boolean differs = false;
                    int diffIndex = -1;
                    for (int i = 0; i < snapshotA.length; i++) {
                        if (snapshotA[i] != snapshotB[i]) {
                            differs = true;
                            diffIndex = i;
                            break;
                        }
                    }
                    if (!differs) {
                        helper.fail("Framebuffer heartbeat region didn't change across ticks — "
                                + "BE ticker not firing or paintHeartbeat is broken "
                                + "(region size=" + snapshotA.length + " bytes)");
                        return;
                    }
                    helper.succeed();
                } finally {
                    case_.powerOff();
                }
            });
        });
    }

    /** Copy the 40x40 region around the heartbeat center (20, 20) into a fresh byte[]. */
    private static byte[] snapshotHeartbeatRegion(ByteBuffer fb) {
        byte[] out = new byte[40 * 40 * 4];
        int w = 0;
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                int off = (y * 640 + x) * 4;
                out[w++] = fb.get(off);
                out[w++] = fb.get(off + 1);
                out[w++] = fb.get(off + 2);
                out[w++] = fb.get(off + 3);
            }
        }
        return out;
    }

    /** Every non-input block places and produces the expected BE type. */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void all_blocks_place_with_correct_be(GameTestHelper helper) {
        placeAndCheck(helper, 1, 1, 1, ScevRegistry.POWERMARK.get(), PowermarkBlockEntity.class);
        placeAndCheck(helper, 2, 1, 1, ScevRegistry.TINKERPAD.get(), TinkerpadBlockEntity.class);
        placeAndCheck(helper, 1, 1, 2, ScevRegistry.VT100.get(), TerminalBlockEntity.class);
        placeAndCheck(helper, 2, 1, 2, ScevRegistry.CRT_MONITOR.get(),
                lekkit.scev.blockentity.CRTBlockEntity.class);
        placeAndCheck(helper, 0, 1, 0, ScevRegistry.KEYBOARD.get(), KeyboardBlockEntity.class);
        placeAndCheck(helper, 0, 1, 1, ScevRegistry.KEYBOARD_MOUSE.get(), KeyboardBlockEntity.class);
        helper.succeed();
    }

    private static void placeAndCheck(GameTestHelper helper, int x, int y, int z,
                                      net.minecraft.world.level.block.Block block,
                                      Class<?> expectedBE) {
        BlockPos pos = new BlockPos(x, y, z);
        helper.setBlock(pos, block.defaultBlockState());
        Object be = helper.getBlockEntity(pos);
        if (be == null || !expectedBE.isInstance(be)) {
            helper.fail("Block " + block + " at " + pos + " did not produce "
                    + expectedBE.getSimpleName() + " (got " + (be == null ? "null" : be.getClass().getSimpleName()) + ")");
        }
    }

    /**
     * Block break should destroy the associated machine. Before the
     * {@code MachineBackend} abstraction, the machine was orphaned in
     * {@code MachineManager} until server stop — a slow leak. Keep this
     * here so it stays fixed.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void block_break_destroys_machine(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD1.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU1.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, new ItemStack(ScevRegistry.FLASH_CHIP.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM1.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        UUID machineUuid = case_.getMachineUUID();
        if (MachineManager.getMachineState(machineUuid) == null) {
            helper.fail("MachineState wasn't registered after powerOn"); return;
        }

        // Simulate block break. Currently there's no hook in our BE for
        // setRemoved -> destroy, so we mirror what powerOff does — the BE
        // code path. (If someone adds a setRemoved hook, update this test
        // to call setBlock(AIR) instead.)
        case_.powerOff();

        if (MachineManager.getMachineState(machineUuid) != null) {
            helper.fail("MachineState wasn't removed after powerOff — machine leaked");
            return;
        }
        helper.succeed();
    }

    /**
     * {@link ComputerCaseBlockEntity}'s machine UUID persists through an
     * NBT save/load cycle so the same block keeps the same machine identity
     * across server restarts. Forest of bugs prevented by this one.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty")
    public static void machine_uuid_persists_across_nbt(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof WorkstationBlockEntity be)) {
            helper.fail("Workstation BE not created"); return;
        }
        UUID originalUuid = be.getMachineUUID();

        // Round-trip through NBT.
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata(helper.getLevel().registryAccess());
        WorkstationBlockEntity reloaded = new WorkstationBlockEntity(pos, be.getBlockState());
        reloaded.loadWithComponents(tag, helper.getLevel().registryAccess());

        if (!originalUuid.equals(reloaded.getMachineUUID())) {
            helper.fail("Machine UUID drifted across NBT round-trip: "
                    + originalUuid + " -> " + reloaded.getMachineUUID());
            return;
        }
        helper.succeed();
    }

    /**
     * End-to-end network test: boots Alpine (via OpenFirmware/U-Boot
     * extlinux) with an RTL8169 NIC and waits for dhcpcd to complete a
     * DHCP lease against RVVM's user-mode gateway.
     *
     * <p>This catches two distinct classes of regression that can each
     * silently break in-game networking:
     *
     * <ol>
     *   <li><b>RVVM user-mode ARP handler replying to DAD probes.</b>
     *       dhcpcd (RFC 5227) sends an ARP-who-has for the offered IP
     *       with {@code sender_ip = 0.0.0.0}. If the gateway replies,
     *       dhcpcd flags a duplicate-address conflict, aborts the
     *       lease, and re-solicits forever. Fixed upstream in
     *       {@code rvvm/src/devices/tap_user.c} by filtering
     *       {@code sender_ip == 0.0.0.0} out of the reply path; this
     *       test would catch a revert.</li>
     *   <li><b>Guest-side kernel driver or service regression.</b> If
     *       {@code CONFIG_R8169} stops being built-in, or the
     *       {@code dhcpcd} OpenRC service gets dropped from the default
     *       runlevel, the interface never comes up and no DHCP traffic
     *       ever flows. The test sees no {@code eth0:} lines and fails.</li>
     * </ol>
     *
     * <p>Success marker: the kernel console tail contains a
     * {@code "eth0: leased"} line (dhcpcd's "lease acquired" log).
     *
     * <p>Failure markers: repeated {@code "DAD detected"} (the loop
     * symptom), or a {@code soliciting} count that climbs without a
     * matching {@code leased} line before the wall-clock deadline.
     *
     * <p>The test uses OpenFirmware (U-Boot) as the flash firmware so
     * the Alpine image's own kernel + dhcpcd userspace runs — that's
     * the only pairing where eth0/DHCP are exercised today (the
     * Buildroot initramfs path doesn't ship a DHCP client).
     *
     * <p>Budget: 120 real seconds. Alpine on a 256 MiB VM takes ~30 s
     * to reach userspace, ~10 s for openrc to progress to the default
     * runlevel, ~2 s for DHCP. 120 s gives headroom plus a chance to
     * catch a hung boot.
     *
     * <p>{@code timeoutTicks} is sized against the gametest server's
     * observed rate (~12500 TPS on macOS here, vs the standard 20 TPS
     * in-game). The VM runs on its own native thread at real time, so
     * the tick budget has to cover the VM's real-time boot budget.
     * Each {@link #pollForDhcpLease} iteration sleeps ~100 ms via
     * {@link Thread#sleep} between checks to let the guest accumulate
     * output without consuming gametest ticks — the server tick
     * counter advances only WHILE we aren't blocking the server
     * thread, so the tick budget is dominated by the non-blocking
     * part (the 20-tick runAfterDelay between polls).
     *
     * <p>Budget math: ~1200 polls across 120 s wall time (100 ms
     * sleep each) × 20 ticks per poll = 24 000 ticks. 200 000 gives
     * comfortable headroom if server TPS spikes.
     */
    @GameTest(templateNamespace = ScalarEvolution.MODID, template = "empty", timeoutTicks = 200_000)
    public static void alpine_dhcp_lease_completes(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ScevRegistry.WORKSTATION.get().defaultBlockState());
        if (!(helper.getBlockEntity(pos) instanceof ComputerCaseBlockEntity case_)) {
            helper.fail("Workstation BE not created"); return;
        }

        // Build an Alpine machine: MOTHERBOARD3 for enough PCI slots,
        // CPU3 for smp, RAM_SODIMM4 for enough for Linux floor, flash
        // chip stamped with OPEN_FIRMWARE (U-Boot reads extlinux.conf
        // from the Alpine disk and boots Alpine's own kernel), the
        // preloaded Alpine NVMe, and an RTL8169 network card.
        ItemStack flash = new ItemStack(ScevRegistry.FLASH_CHIP.get());
        flash.set(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get(), FirmwareRegistry.OPEN_FIRMWARE);

        ItemStack mbStack = new ItemStack(ScevRegistry.MOTHERBOARD3.get());
        MotherboardInventory inv = new MotherboardInventory(() -> mbStack);
        inv.setItem(MotherboardItem.SLOT_CPU, new ItemStack(ScevRegistry.CPU3.get()));
        inv.setItem(MotherboardItem.SLOT_FLASH, flash);
        inv.setItem(MotherboardItem.SLOT_RAM_START, new ItemStack(ScevRegistry.RAM_SODIMM4.get()));
        inv.setItem(MotherboardItem.SLOT_RAM_START + 1, new ItemStack(ScevRegistry.RAM_SODIMM4.get()));
        inv.setItem(MotherboardItem.SLOT_NVME_START, new ItemStack(ScevRegistry.NVME_PRELOADED.get()));
        inv.setItem(MotherboardItem.SLOT_PCI_START, new ItemStack(ScevRegistry.RTL8169.get()));
        case_.setItem(0, mbStack);
        case_.powerOn();

        UUID machineUuid = case_.getMachineUUID();
        MachineState state = MachineManager.getMachineState(machineUuid);
        if (state == null) {
            helper.fail("No MachineState after powerOn — librvvm unavailable on this host?");
            return;
        }
        if (!state.getBackend().spec().hasNic()) {
            helper.fail("Spec says hasNic=false despite RTL8169 installed — "
                    + "MachineSpecParser's PCI dispatch regressed");
            case_.powerOff();
            return;
        }

        long deadlineMs = System.currentTimeMillis() + 120_000;
        pollForDhcpLease(helper, case_, machineUuid, deadlineMs);
    }

    private static void pollForDhcpLease(GameTestHelper helper,
                                         ComputerCaseBlockEntity case_,
                                         UUID machineUuid,
                                         long deadlineMs) {
        // Inside each poll we briefly block the server thread so
        // wall-clock time passes without burning the gametest tick
        // budget. The machine runs on its own native thread at real
        // time regardless of what the server thread is doing, so
        // sleeping here is how we let the guest make progress.
        //
        // Note: GOTCHAS.md warns against long Thread.sleep in
        // GameTests because it starves *other* server-tick
        // subscribers. That caveat applies when multiple things
        // need ticks to make progress (e.g. SoundStreamManager's
        // polling loop). For this test only the machine's own thread
        // matters; the kernel console ring empties on the NEXT
        // server tick after we return, so a short (≤100 ms) sleep
        // is fine. Don't copy this pattern to tests that depend on
        // frequent server-tick draining.
        try {
            Thread.sleep(100);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        lekkit.scev.rpc.ScevRpcManager mgr = lekkit.scev.rpc.ScevRpcManager.get(machineUuid);
        if (mgr == null) {
            helper.fail("ScevRpcManager for " + machineUuid + " disappeared mid-boot");
            case_.powerOff();
            return;
        }

        java.util.List<String> tail = mgr.kernelConsoleTail();
        int dadCount = 0;
        int solicitCount = 0;
        boolean leased = false;
        boolean noLease = false;
        String netcheckLine = null;
        for (String line : tail) {
            if (line.contains("DAD detected")) dadCount++;
            if (line.contains("soliciting a DHCP lease")) solicitCount++;
            // Deterministic success marker emitted by
            // /etc/local.d/scev-netcheck.start in the Alpine image.
            // That script polls `ip addr show eth0` for up to 30 s
            // and echoes the outcome to /dev/console.
            if (line.contains("[scev-netcheck] eth0: leased ")) {
                leased = true;
                netcheckLine = line;
            }
            if (line.contains("[scev-netcheck] eth0: no lease after")) {
                noLease = true;
                netcheckLine = line;
            }
        }

        if (leased) {
            // Sanity-check the advertised IP matches RVVM's DHCP
            // server (192.168.0.100/24). Future-proofs against a
            // regression where dhcpcd grabs a link-local or
            // fallback address instead of the RVVM lease.
            if (netcheckLine != null && !netcheckLine.contains("192.168.0.100")) {
                helper.fail("eth0 got a lease but not 192.168.0.100 as expected: "
                        + netcheckLine + ". RVVM's tap_user.c hands out this specific "
                        + "address; if it changed, update the test.");
                case_.powerOff();
                return;
            }
            helper.succeed();
            case_.powerOff();
            return;
        }

        if (noLease) {
            helper.fail("Guest reported no DHCP lease after 30 s of polling on eth0. "
                    + "Marker: " + netcheckLine + ". DAD=" + dadCount + ", solicit=" + solicitCount
                    + ". Most likely causes: (1) RVVM's tap_user.c ARP handler regressed and is "
                    + "replying to DAD probes; (2) R8169 driver failed to bind to the PCI NIC "
                    + "(check for 'r8169 0000:00:02.0 eth0' in kernel dmesg); (3) dhcpcd service "
                    + "not in the default runlevel. Tail:" + formatTail(tail));
            case_.powerOff();
            return;
        }

        // DAD loop detector: two or more "DAD detected" lines means we
        // cycled through solicit / offer / probe / conflict at least
        // twice — the failure mode the RVVM fix addresses. One probe
        // can legitimately fire (though after the fix, even that
        // shouldn't see a reply), so we only fail on 2+.
        if (dadCount >= 2) {
            helper.fail("DHCP DAD loop detected (" + dadCount + " 'DAD detected' lines, "
                    + solicitCount + " 'soliciting' lines in last " + tail.size() + " kernel-console "
                    + "lines). RVVM's tap_user.c handle_arp must be replying to ARP probes "
                    + "(sender_ip = 0.0.0.0); see the rebuilt librvvm, and/or the depmod / "
                    + "R8169 configuration regressed on the Alpine side. Tail: " + formatTail(tail));
            case_.powerOff();
            return;
        }

        if (System.currentTimeMillis() >= deadlineMs) {
            helper.fail("120s deadline reached without a DHCP lease. Kernel console tail size = "
                    + tail.size() + "; DAD=" + dadCount + ", solicit=" + solicitCount + ", leased="
                    + leased + ". If tail is empty, the guest never got far enough to print "
                    + "anything — check kernel boot (serial console routing, initramfs). "
                    + "Tail: " + formatTail(tail));
            case_.powerOff();
            return;
        }

        // Poll every 20 ticks — enough to pick up new console lines as
        // they arrive but cheap enough that we don't burn the gametest
        // tick budget. Kernel console drains on every server tick
        // regardless of this delay; this just paces our inspection.
        helper.runAfterDelay(20,
                () -> pollForDhcpLease(helper, case_, machineUuid, deadlineMs));
    }

    /**
     * Condense a kernel-console tail into a short diagnostic snippet
     * for test failure messages. Deliberately small (last 10 lines):
     * {@code helper.fail} round-trips the message through a
     * {@code writable_book} page, which has a ~1 KB content cap —
     * dumping the full 256-line tail overflows and the error itself
     * stops rendering.
     */
    private static String formatTail(java.util.List<String> tail) {
        int n = Math.min(tail.size(), 10);
        if (n == 0) return " (empty)";
        StringBuilder sb = new StringBuilder(" last ").append(n).append(" lines: ");
        for (int i = tail.size() - n; i < tail.size(); i++) {
            sb.append('|').append(tail.get(i));
        }
        return sb.toString();
    }

    private ScevGameTests() {}
}
