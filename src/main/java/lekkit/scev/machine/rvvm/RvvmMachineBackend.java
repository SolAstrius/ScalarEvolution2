/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.machine.rvvm;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lekkit.rvvm.Framebuffer;
import lekkit.rvvm.GoldfishRTC;
import lekkit.rvvm.HIDKeyboard;
import lekkit.rvvm.HIDMouse;
import lekkit.rvvm.I2CBus;
import lekkit.rvvm.NS16550A;
import lekkit.rvvm.NVMeDrive;
import lekkit.rvvm.PCIBus;
import lekkit.rvvm.PLIC;
import lekkit.rvvm.RTL8169;
import lekkit.rvvm.RVVMMachine;
import lekkit.rvvm.RVVMNative;
import lekkit.rvvm.SiFiveGPIO;
import lekkit.rvvm.Syscon;
import lekkit.scev.machine.BootSplash;
import lekkit.scev.machine.DemoBootrom;
import lekkit.scev.machine.FramebufferView;
import lekkit.scev.machine.GpioDevice;
import lekkit.scev.machine.KeyboardDevice;
import lekkit.scev.machine.MachineBackend;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MouseDevice;
import lekkit.scev.machine.firmware.FirmwareRegistry;
import lekkit.scev.machine.firmware.ScevFirmware;
import lekkit.scev.machine.storage.DiskTemplateRegistry;
import lekkit.scev.machine.storage.ScevDiskTemplate;
import lekkit.scev.server.FirmwareAssets;
import lekkit.scev.server.NativeLoader;
import lekkit.scev.server.StorageManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Production {@link MachineBackend} backed by RVVM via JNI.
 *
 * <p>This is the bug-fix target for "dark screen on power on". Key lifecycle
 * differences from the previous hand-rolled {@code MachineState}:
 *
 * <ol>
 *   <li>Loads a real M-mode firmware blob (shipped via
 *       {@link lekkit.scev.server.FirmwareAssets}) via
 *       {@link RVVMMachine#loadBootrom} when a flash chip is installed, so
 *       power-on lands in an interactive firmware shell. When no firmware is
 *       present, falls back to a tiny {@link DemoBootrom} so the CPU at
 *       least executes something.</li>
 *   <li>Optionally loads an S-mode kernel payload via
 *       {@link RVVMMachine#loadKernel} at RVVM's kernel offset
 *       ({@code mem_base + 0x200000} for RV64). Pairs with an OpenSBI-only
 *       firmware ({@code fw_jump.bin}) to boot a Linux kernel directly; RVVM
 *       emits a {@code simple-framebuffer} DTB node so a kernel built with
 *       {@code CONFIG_FB_SIMPLE + CONFIG_FRAMEBUFFER_CONSOLE} renders its
 *       console onto our framebuffer.</li>
 *   <li>Paints a {@link BootSplash} into the framebuffer on initialize, so even
 *       before firmware draws its first frame the user sees a visible
 *       "POWER ON" pattern. Running firmware overwrites it on first frame.</li>
 *   <li>Explicit initialize / start / pause / reset / close contract — tests
 *       drive the same API via {@link lekkit.scev.test.machine.FakeMachineBackend}.</li>
 * </ol>
 */
public final class RvvmMachineBackend implements MachineBackend {
    private static final Logger LOG = LogUtils.getLogger();

    private @Nullable MachineSpec spec;

    private @Nullable RVVMMachine machine;
    private @Nullable RvvmFramebuffer framebuffer;
    private @Nullable RvvmKeyboard keyboard;
    private @Nullable RvvmMouse mouse;
    private @Nullable RTL8169 nic;
    private @Nullable RvvmGpio gpio;
    private final List<NVMeDrive> nvmeDrives = new ArrayList<>();

    /**
     * Temp files written for custom-firmware raw-bytes loading.
     * {@link RVVMMachine#loadBootrom} takes a path, not a buffer, so we
     * spill the blob to disk once per boot and clean up on {@link #close}.
     * Small — one entry per bring-your-own-bytes flash chip.
     */
    private final List<Path> tempFirmwareFiles = new ArrayList<>();

    private boolean closed;

    @Override
    public synchronized boolean initialize(MachineSpec spec) {
        if (this.spec != null || closed) return false;
        this.spec = spec;

        if (!NativeLoader.ensureLoaded() || !RVVMNative.isLoaded()) {
            LOG.warn("RVVM native not loaded; cannot initialize machine {}", spec.uuid());
            return false;
        }

        RVVMMachine m = new RVVMMachine(spec.memMb(), spec.smp(), spec.isa());
        if (!m.isValid()) {
            LOG.warn("RVVMMachine.isValid() == false after construction for {}", spec.uuid());
            return false;
        }
        this.machine = m;
        m.setOption(RVVMMachine.RVVM_OPT_HW_IMITATE, 1);
        m.appendCmdline(spec.cmdline());

        new PLIC(m);
        new PCIBus(m);
        new I2CBus(m);
        new GoldfishRTC(m);
        new Syscon(m);
        new NS16550A(m);

        HIDKeyboard hidKb = new HIDKeyboard(m);
        keyboard = new RvvmKeyboard(hidKb);
        mouse = new RvvmMouse(new HIDMouse(m));
        if (!hidKb.isValid()) {
            // rvvm_hid_keyboard_init_auto returned 0 — HID keyboard wasn't
            // attached to the machine. Subsequent press/release calls become
            // silent no-ops (HIDKeyboard checks isValid() before each native
            // call), so the player types on the MachineScreen and nothing
            // happens in the VM. This is the observable symptom of "keyboard
            // input doesn't work" bugs; when it fires, check whether librvvm
            // is built with USB / HID support and whether the staging ABI
            // changed the init signature.
            LOG.warn("HID keyboard failed to attach for machine {} — key events will be silently dropped. "
                    + "Check librvvm build flags and RVVMNative.hid_keyboard_init_auto().",
                    spec.uuid());
        }

        // -- Display --------------------------------------------------------
        if (spec.hasDisplay()) {
            int w = spec.display().width();
            int h = spec.display().height();
            Framebuffer fb = new Framebuffer(m, w, h, Framebuffer.BPP_A8R8G8B8);
            framebuffer = new RvvmFramebuffer(fb, w, h);
            mouse.inner.resolution(w, h);

            // Paint a visible splash so the user sees SOMETHING before firmware boots.
            // Firmware (if any) will overwrite on the first frame. Without this, a
            // machine with no bootrom (empty fw_payload.bin) presented as a dark
            // screen — the original "nothing happens when I power on" bug.
            BootSplash.paint(framebuffer);
        }

        // -- Firmware --------------------------------------------------------
        // Firmware bytes come from one of two sources, in this order of
        // preference:
        //
        //   (a) registry: spec.firmware().firmwareId() references a
        //       {@link ScevFirmware} in {@link FirmwareRegistry}. The firmware
        //       entry declares one or more payloads — each either a BOOTROM
        //       (loaded via loadBootrom at 0x80000000) or a KERNEL (loaded via
        //       loadKernel at 0x80200000). A Linux-capable firmware like
        //       {@code linux} declares both. Production flash chips route
        //       through this path.
        //
        //   (b) direct origin: spec.firmware().origin() is a raw classpath
        //       asset name. The backend loads it as a BOOTROM via the
        //       per-UUID {@link StorageManager} image pipeline. Kept for
        //       tests and power-user NBT overrides; a separate KernelSpec
        //       still applies on top of this.
        //
        // NOTE: We intentionally do NOT also attach the image as an MTDFlash
        // MMIO device. Both MTDFlash.reset() and rvvm_load_firmware copy the
        // image into RAM at the reset vector — attaching both means two copy
        // paths racing over the same bytes. For "firmware is the reset code"
        // the current design is correct; add an mtdFlash spec field if we
        // ever ship firmware that needs a separate MMIO flash (e.g. U-Boot
        // env storage).
        boolean firmwareLoaded = false;
        if (spec.hasFirmware()) {
            MachineSpec.FirmwareSpec fw = spec.firmware();
            // Precedence: raw bytes > registry id > direct origin. Raw bytes
            // is the custom-flash path and must win over any fallback ids
            // the parser also emitted.
            if (fw.hasRawBytes()) {
                firmwareLoaded = loadRawBytesFirmware(m, spec, fw);
            } else if (fw.hasRegistryRef()) {
                firmwareLoaded = loadRegistryFirmware(m, spec, fw);
            } else if (fw.origin() != null) {
                firmwareLoaded = loadDirectFirmware(m, spec, fw);
            } else {
                LOG.warn("FirmwareSpec for machine {} has neither firmwareId nor origin nor rawBytes — ignored",
                        spec.uuid());
            }
        }

        // -- Explicit KernelSpec (legacy / layered) -------------------------
        // A caller can still attach a KernelSpec independently — most useful
        // when using OPENSBI_ONLY firmware and bringing your own kernel.
        // Registry-driven LINUX firmware already loads its kernel via the
        // payload list; attaching a redundant KernelSpec here would stack
        // (later payload overwrites earlier). Tests + power-user NBT paths
        // use this.
        if (spec.hasKernel()) {
            MachineSpec.KernelSpec k = spec.kernel();
            Path kernelPath = FirmwareAssets.ensureExtracted(k.origin());
            if (kernelPath == null) {
                LOG.warn("Kernel asset {} not available (neither bundled nor on disk) for machine {}",
                        k.origin(), spec.uuid());
            } else if (!m.loadKernel(kernelPath.toString())) {
                LOG.warn("rvvm_load_kernel rejected {} for machine {}", kernelPath, spec.uuid());
            } else {
                if (k.cmdline() != null && !k.cmdline().isEmpty()) {
                    m.appendCmdline(k.cmdline());
                }
                LOG.info("Loaded kernel for machine {} from {} (cmdline append: {})",
                        spec.uuid(), kernelPath, k.cmdline());
            }
        }

        // -- NVMe drives ----------------------------------------------------
        // Two paths for deciding the "origin" asset used to seed a fresh
        // per-UUID image:
        //
        //   (a) DiskSpec has a templateId -> resolve a ScevDiskTemplate
        //       from the registry; use template.assetName + template.sizeMb.
        //       Preloaded NVMe items route through this (e.g. the shipped
        //       Buildroot Linux rootfs).
        //   (b) DiskSpec has only an origin -> use it directly. Blank
        //       NvmeItem / HDD fall through this path; origin may name a
        //       classpath asset or may not exist at all (StorageManager
        //       then creates a blank sparse image).
        //
        // Either way, once the per-UUID image exists it's handed to
        // RVVM's NVMe device as a read/write block store — the template
        // affects only first-boot content.
        for (MachineSpec.DiskSpec disk : spec.nvmeDrives()) {
            String effectiveOrigin;
            long effectiveSizeMb;
            String source;
            if (disk.hasTemplateRef()) {
                ScevDiskTemplate template = DiskTemplateRegistry.get(disk.templateId());
                if (template == null) {
                    LOG.warn("Disk template {} not registered — falling back to blank image for {}",
                            disk.templateId(), disk.uuid());
                    effectiveOrigin = null;
                    effectiveSizeMb = disk.sizeMb();
                    source = "template-not-registered";
                } else {
                    effectiveOrigin = template.assetName();
                    effectiveSizeMb = template.sizeMb();
                    source = "template:" + disk.templateId();
                }
            } else {
                effectiveOrigin = disk.origin();
                effectiveSizeMb = disk.sizeMb();
                source = "origin:" + disk.origin();
            }
            if (StorageManager.initImage(disk.uuid(), effectiveSizeMb, effectiveOrigin)) {
                nvmeDrives.add(new NVMeDrive(m, StorageManager.imagePath(disk.uuid()), true));
                LOG.info("Initialized NVMe image {} ({}) for machine {}",
                        disk.uuid(), source, spec.uuid());
            } else {
                LOG.warn("Failed to initialize NVMe image {} ({})", disk.uuid(), source);
            }
        }

        // -- NIC ------------------------------------------------------------
        if (spec.hasNic()) {
            nic = new RTL8169(m);
        }

        // -- GPIO -----------------------------------------------------------
        if (spec.hasGpio()) {
            gpio = new RvvmGpio(new SiFiveGPIO(m));
        }

        // -- Bootrom fallback -----------------------------------------------
        // If firmware wasn't loaded (no flash chip installed, bundled asset
        // missing, or explicitly DEMO_ONLY mode), fall back to the tiny 16-byte
        // DemoBootrom. This keeps the "CPU is alive" regression invariant:
        // tests without a flash chip still get CPU execution, and the POWER ON
        // splash isn't a lie.
        //
        // Write the bytes to a temp file and call RVVM's proper load_bootrom
        // API rather than DMA-writing the bytes directly, because RVVM's JIT
        // may cache zero-initialised RAM before we get a chance to mutate it
        // — JIT cache invalidation happens during load_bootrom but not for
        // arbitrary DMA writes.
        boolean shouldLoadDemo = switch (spec.bootromMode()) {
            case DEMO_ONLY -> true;                      // Tests want the demo, always.
            case FIRMWARE_ELSE_DEMO -> !firmwareLoaded;  // Only if real firmware isn't present.
            case NONE -> false;                          // Explicit opt-out.
        };
        if (shouldLoadDemo) {
            try {
                Path bootromFile = DemoBootrom.writeToTempFile();
                if (m.loadBootrom(bootromFile.toString())) {
                    LOG.info("Loaded demo bootrom into machine {} (path={})", spec.uuid(), bootromFile);
                } else {
                    LOG.warn("loadBootrom returned false for demo bootrom in machine {}", spec.uuid());
                }
            } catch (IOException e) {
                LOG.warn("Failed to write demo bootrom to temp file", e);
            }
        }

        return true;
    }

    /**
     * Resolve a registry-referenced firmware and load all its payloads.
     *
     * <p>BOOTROM payloads go through {@link StorageManager} so the first
     * one materializes as the per-UUID flash image (that's how a flash
     * chip keeps its "programmed" contents persistent across power cycles).
     * Additional BOOTROM payloads — uncommon — also go through
     * {@code StorageManager} to maintain the per-UUID invariant.
     *
     * <p>KERNEL payloads are read-only and shared: we pass the extracted
     * asset path straight to {@code loadKernel} (no per-UUID copy), same
     * as the legacy {@link MachineSpec.KernelSpec} path.
     *
     * <p>Also appends the firmware's declared cmdline fragment
     * ({@link ScevFirmware#cmdlineAppend}) so e.g. the Linux firmware can route the
     * kernel console to fbcon.
     *
     * @return true iff at least one BOOTROM payload loaded successfully —
     *         that's the invariant the {@code firmwareLoaded} flag above
     *         needs (it controls whether DemoBootrom fallback kicks in).
     */
    private boolean loadRegistryFirmware(RVVMMachine m, MachineSpec spec, MachineSpec.FirmwareSpec fw) {
        ScevFirmware firmware = FirmwareRegistry.get(fw.firmwareId());
        if (firmware == null) {
            LOG.warn("Firmware {} not registered — falling back for machine {}",
                    fw.firmwareId(), spec.uuid());
            return false;
        }

        boolean anyBootromLoaded = false;
        boolean bootromSeen = false;
        for (ScevFirmware.Payload p : firmware.payloads()) {
            switch (p.kind()) {
                case BOOTROM -> {
                    if (bootromSeen) {
                        // Subsequent BOOTROM payloads aren't common but
                        // shouldn't silently clobber the flash image. Log
                        // and skip — firmware authors should use multiple
                        // address regions via a richer MMIO spec if they
                        // really need multiple bootrom blobs.
                        LOG.warn("Firmware {} declares multiple BOOTROM payloads; only the "
                                + "first is persisted as the flash image. Ignoring {}.",
                                fw.firmwareId(), p.asset());
                        continue;
                    }
                    bootromSeen = true;
                    if (StorageManager.initImage(fw.uuid(), fw.sizeMb(), p.asset())) {
                        String path = StorageManager.imagePath(fw.uuid());
                        if (m.loadBootrom(path)) {
                            LOG.info("Loaded firmware '{}' BOOTROM for machine {} from {} (asset={})",
                                    fw.firmwareId(), spec.uuid(), path, p.asset());
                            anyBootromLoaded = true;
                        } else {
                            LOG.warn("rvvm_load_firmware rejected {} for machine {}", path, spec.uuid());
                        }
                    } else {
                        LOG.warn("Failed to initialize firmware image for {} (asset={})",
                                fw.uuid(), p.asset());
                    }
                }
                case KERNEL -> {
                    Path kernelPath = FirmwareAssets.ensureExtracted(p.asset());
                    if (kernelPath == null) {
                        LOG.warn("Firmware '{}' KERNEL asset {} not available for machine {}",
                                fw.firmwareId(), p.asset(), spec.uuid());
                    } else if (!m.loadKernel(kernelPath.toString())) {
                        LOG.warn("rvvm_load_kernel rejected {} for machine {}", kernelPath, spec.uuid());
                    } else {
                        LOG.info("Loaded firmware '{}' KERNEL for machine {} from {} (asset={})",
                                fw.firmwareId(), spec.uuid(), kernelPath, p.asset());
                    }
                }
            }
        }

        // Let the firmware contribute to the kernel cmdline (e.g. console routing).
        String append = firmware.cmdlineAppend();
        if (append != null && !append.isEmpty()) {
            m.appendCmdline(append);
        }

        return anyBootromLoaded;
    }

    /**
     * Legacy-shaped firmware load: {@code fw.origin()} is a raw asset name,
     * loaded as a single BOOTROM via the per-UUID image pipeline. Used by
     * tests and NBT power-user paths where no registry entry is involved.
     */
    private boolean loadDirectFirmware(RVVMMachine m, MachineSpec spec, MachineSpec.FirmwareSpec fw) {
        if (StorageManager.initImage(fw.uuid(), fw.sizeMb(), fw.origin())) {
            String path = StorageManager.imagePath(fw.uuid());
            if (m.loadBootrom(path)) {
                LOG.info("Loaded firmware bootrom for machine {} from {} (origin={})",
                        spec.uuid(), path, fw.origin());
                return true;
            }
            LOG.warn("rvvm_load_firmware rejected {} for machine {}", path, spec.uuid());
        } else {
            LOG.warn("Failed to initialize firmware image for {} (origin={})", fw.uuid(), fw.origin());
        }
        return false;
    }

    /**
     * Custom-firmware path: flash chip carries literal bytes in its data
     * component. We spill them to a temp file (RVVM's loader takes a path,
     * not a buffer) and hand the path to {@code rvvm_load_firmware}.
     *
     * <p>The file is tracked in {@link #tempFirmwareFiles} and deleted on
     * {@link #close} so we don't leak {@code /tmp} entries across machine
     * power cycles. Using a per-machine prefix + {@link Files#createTempFile}
     * avoids clashes between concurrent machines sharing the same host.
     *
     * <p>This is the path player-authored programs travel when flashed
     * into a chip via the future Programmer block.
     */
    private boolean loadRawBytesFirmware(RVVMMachine m, MachineSpec spec, MachineSpec.FirmwareSpec fw) {
        byte[] bytes = fw.rawBytes().bytes();
        try {
            Path tmp = Files.createTempFile("scev-fw-" + fw.uuid() + "-", ".bin");
            Files.write(tmp, bytes);
            tempFirmwareFiles.add(tmp);
            if (m.loadBootrom(tmp.toString())) {
                LOG.info("Loaded custom firmware ({} bytes) for machine {} from {}",
                        bytes.length, spec.uuid(), tmp);
                return true;
            }
            LOG.warn("rvvm_load_firmware rejected custom blob at {} for machine {}", tmp, spec.uuid());
        } catch (IOException e) {
            LOG.error("Failed to spill custom firmware to temp file for machine {}", spec.uuid(), e);
        }
        return false;
    }

    @Override
    public synchronized boolean start() {
        if (machine == null || closed) return false;
        return machine.start();
    }

    @Override
    public synchronized boolean pause() {
        if (machine == null || closed) return false;
        return machine.pause();
    }

    @Override
    public synchronized boolean reset() {
        if (machine == null || closed) return false;
        return machine.reset();
    }

    @Override
    public synchronized boolean isRunning() {
        return machine != null && !closed && machine.isPowered();
    }

    @Override
    public synchronized boolean isValid() {
        return machine != null && !closed && machine.isValid();
    }

    @Override
    public MachineSpec spec() {
        if (spec == null) throw new IllegalStateException("not initialized");
        return spec;
    }

    @Override public @Nullable FramebufferView framebuffer() { return closed ? null : framebuffer; }
    @Override public @Nullable KeyboardDevice keyboard()    { return closed ? null : keyboard; }
    @Override public @Nullable MouseDevice mouse()          { return closed ? null : mouse; }
    @Override public @Nullable GpioDevice gpio()            { return closed ? null : gpio; }

    @Override
    public synchronized @Nullable java.nio.ByteBuffer readMemory(long addr, long size) {
        if (closed || machine == null || !machine.isValid()) return null;
        return machine.getDmaBuffer(addr, size);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (machine != null) {
            machine.pause();
            machine.free();
            machine = null;
        }
        framebuffer = null;
        keyboard = null;
        mouse = null;
        nic = null;
        gpio = null;
        nvmeDrives.clear();
        // Clean up any custom-firmware temp files we spilled. Not critical
        // if deletion fails (the OS reaps /tmp eventually) but worth trying.
        for (Path tmp : tempFirmwareFiles) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
        tempFirmwareFiles.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Adapters wrapping the JNI classes in our device interfaces.         */
    /* ------------------------------------------------------------------ */

    private static final class RvvmFramebuffer implements FramebufferView {
        private final Framebuffer inner;
        private final int w, h;

        RvvmFramebuffer(Framebuffer inner, int w, int h) {
            this.inner = inner;
            this.w = w;
            this.h = h;
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public java.nio.ByteBuffer pixels() {
            java.nio.ByteBuffer b = inner.getBuffer();
            b.rewind();
            return b;
        }
    }

    private static final class RvvmKeyboard implements KeyboardDevice {
        private final HIDKeyboard inner;
        RvvmKeyboard(HIDKeyboard inner) { this.inner = inner; }
        @Override public void press(byte key)   { inner.press(key); }
        @Override public void release(byte key) { inner.release(key); }
    }

    private static final class RvvmMouse implements MouseDevice {
        final HIDMouse inner;
        RvvmMouse(HIDMouse inner) { this.inner = inner; }
        @Override public void resolution(int x, int y) { inner.resolution(x, y); }
        @Override public void place(int x, int y)     { inner.place(x, y); }
        @Override public void move(int dx, int dy)    { inner.move(dx, dy); }
        @Override public void press(byte btn)         { inner.press(btn); }
        @Override public void release(byte btn)       { inner.release(btn); }
        @Override public void scroll(byte d)          { inner.scroll((int) d); }
    }

    private static final class RvvmGpio implements GpioDevice {
        private final SiFiveGPIO inner;
        RvvmGpio(SiFiveGPIO inner) { this.inner = inner; }
        @Override public int readPins() { return inner.read_pins() & 0x3F; }
        @Override public void writePins(int pins) { inner.write_pins(pins & 0x3F); }
    }
}
