/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.test.machine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lekkit.scev.machine.FramebufferView;
import lekkit.scev.machine.GpioDevice;
import lekkit.scev.machine.KeyboardDevice;
import lekkit.scev.machine.MachineBackend;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MouseDevice;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory {@link MachineBackend} for unit tests. Records every call so
 * assertions can verify exactly what the code under test did, and exposes
 * device fakes that track their own state (pressed keys, mouse coords, GPIO
 * pins, framebuffer pixels).
 *
 * <p>Designed to catch regressions in the code that builds a spec, applies it
 * to a backend, and forwards input / framebuffer events — without requiring
 * a native RVVM library on the test machine.
 *
 * <p>Example:
 * <pre>
 *   FakeMachineBackend b = new FakeMachineBackend();
 *   MachineSpec spec = MachineSpec.builder(UUID.randomUUID()).defaultDisplay().build();
 *   assertTrue(b.initialize(spec));
 *   assertTrue(b.start());
 *   b.keyboard().press(HID_KEY_A);
 *   assertTrue(b.keyboardOps().contains("press:4"));
 * </pre>
 */
public final class FakeMachineBackend implements MachineBackend {
    private @Nullable MachineSpec spec;
    private @Nullable FakeFramebuffer framebuffer;
    private @Nullable FakeKeyboard keyboard;
    private @Nullable FakeMouse mouse;
    private @Nullable FakeGpio gpio;
    private boolean initialized;
    private boolean closed;
    private boolean running;

    /** Every high-level call observed, in order, e.g. {@code "initialize"}, {@code "start"}. */
    public final List<String> lifecycleOps = new ArrayList<>();

    /**
     * Fake memory: 16 KiB per mapped region, keyed by base address. Returned
     * by {@link #readMemory}. Grown on demand so tests can install a bootrom
     * at 0x80000000 and verify a side-effect at 0x80010000 in the same
     * backend.
     */
    private final Map<Long, ByteBuffer> memoryRegions = new HashMap<>();

    @Override
    public boolean initialize(MachineSpec spec) {
        if (initialized || closed) return false;
        this.spec = spec;
        this.keyboard = new FakeKeyboard();
        this.mouse = new FakeMouse();
        if (spec.hasDisplay()) {
            this.framebuffer = new FakeFramebuffer(spec.display().width(), spec.display().height());
            this.mouse.resolution(spec.display().width(), spec.display().height());
        }
        if (spec.hasGpio()) {
            this.gpio = new FakeGpio();
        }
        initialized = true;
        lifecycleOps.add("initialize");
        return true;
    }

    @Override
    public boolean start() {
        if (!initialized || closed) return false;
        running = true;
        lifecycleOps.add("start");
        return true;
    }

    @Override
    public boolean pause() {
        if (!initialized || closed) return false;
        // Match RVVM's semantic: pause halts the emulation thread but the
        // machine is still "powered on". Only close() flips the running flag.
        // If the fake also flipped running on pause, tryResume would never
        // re-start, which doesn't match production behavior.
        lifecycleOps.add("pause");
        return true;
    }

    @Override
    public boolean reset() {
        if (!initialized || closed) return false;
        // Reset semantics: run state unchanged; caller is responsible for re-starting.
        lifecycleOps.add("reset");
        return true;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isValid() { return initialized && !closed; }

    @Override
    public MachineSpec spec() {
        if (spec == null) throw new IllegalStateException("not initialized");
        return spec;
    }

    @Override public @Nullable FramebufferView framebuffer() { return closed ? null : framebuffer; }
    @Override public @Nullable KeyboardDevice keyboard()    { return closed ? null : keyboard; }
    @Override public @Nullable MouseDevice mouse()          { return closed ? null : mouse; }
    @Override public @Nullable GpioDevice gpio()            { return closed ? null : gpio; }

    /**
     * Fake DMA: returns a ByteBuffer view over a per-backend map of memory
     * pages. The first call for any address allocates a fresh zero-filled
     * page of {@code size} bytes; subsequent calls for the same (addr, size)
     * return the same page. Writes through the returned buffer are visible
     * to later reads — that's how tests simulate a CPU write-then-verify
     * round-trip without a real VM.
     */
    @Override
    public synchronized @Nullable ByteBuffer readMemory(long addr, long size) {
        if (closed) return null;
        if (size <= 0 || size > Integer.MAX_VALUE) return null;
        ByteBuffer buf = memoryRegions.get(addr);
        if (buf == null || buf.capacity() < (int) size) {
            buf = ByteBuffer.allocate((int) size);
            memoryRegions.put(addr, buf);
        }
        buf.rewind();
        return buf;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        running = false;
        lifecycleOps.add("close");
    }

    /* ----------- Test-only accessors for recorded device calls ---------- */

    public @Nullable FakeFramebuffer fbufRaw() { return framebuffer; }
    public @Nullable FakeKeyboard keyboardRaw() { return keyboard; }
    public @Nullable FakeMouse mouseRaw() { return mouse; }
    public @Nullable FakeGpio gpioRaw() { return gpio; }

    public static final class FakeFramebuffer implements FramebufferView {
        private final int w, h;
        private final ByteBuffer buf;

        FakeFramebuffer(int w, int h) {
            this.w = w;
            this.h = h;
            this.buf = ByteBuffer.allocate(w * h * 4);
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }

        @Override public ByteBuffer pixels() { buf.rewind(); return buf; }

        /** Test helper: write an ARGB pixel in the native BGRA byte order. */
        public void writePixel(int x, int y, int a, int r, int g, int b) {
            int off = (y * w + x) * 4;
            buf.put(off,     (byte) b);
            buf.put(off + 1, (byte) g);
            buf.put(off + 2, (byte) r);
            buf.put(off + 3, (byte) a);
        }

        /** Test helper: fill the whole framebuffer with a solid color. */
        public void fill(int a, int r, int g, int b) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) writePixel(x, y, a, r, g, b);
        }
    }

    public static final class FakeKeyboard implements KeyboardDevice {
        public final List<String> ops = new ArrayList<>();
        @Override public void press(byte key)   { ops.add("press:" + (key & 0xFF)); }
        @Override public void release(byte key) { ops.add("release:" + (key & 0xFF)); }
    }

    public static final class FakeMouse implements MouseDevice {
        public final List<String> ops = new ArrayList<>();
        public int resX, resY, curX, curY;
        public int pressedMask = 0;

        @Override public void resolution(int x, int y) { resX = x; resY = y; ops.add("res:" + x + "x" + y); }
        @Override public void place(int x, int y)     { curX = x; curY = y; ops.add("place:" + x + "," + y); }
        @Override public void move(int dx, int dy)    { curX += dx; curY += dy; ops.add("move:" + dx + "," + dy); }
        @Override public void press(byte btn)         { pressedMask |= (btn & 0xFF); ops.add("press:" + (btn & 0xFF)); }
        @Override public void release(byte btn)       { pressedMask &= ~(btn & 0xFF); ops.add("release:" + (btn & 0xFF)); }
        @Override public void scroll(byte d)          { ops.add("scroll:" + (int) d); }
    }

    public static final class FakeGpio implements GpioDevice {
        public int readValue = 0;
        public int lastWrite = 0;
        public final List<Integer> writes = new ArrayList<>();

        @Override public int readPins() { return readValue & 0x3F; }
        @Override public void writePins(int pins) { lastWrite = pins & 0x3F; writes.add(lastWrite); }
    }
}
