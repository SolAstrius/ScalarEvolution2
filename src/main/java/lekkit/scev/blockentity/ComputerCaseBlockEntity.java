/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.blockentity;

import java.nio.ByteBuffer;
import java.util.UUID;
import lekkit.scev.blocks.DirectionalBlock;
import lekkit.scev.bus.PeripheralBus;
import lekkit.scev.bus.PeripheralBusController;
import lekkit.scev.codec.BgraYuv;
import lekkit.scev.codec.H264Encoder;
import lekkit.scev.common.MachineClock;
import lekkit.scev.items.MotherboardItem;
import lekkit.scev.machine.BootSplash;
import lekkit.scev.machine.FramebufferView;
import lekkit.scev.machine.GpioDevice;
import lekkit.scev.machine.GpioPinMap;
import lekkit.scev.machine.MachineSpec;
import lekkit.scev.machine.MachineSpecParser;
import lekkit.scev.network.DisplayPayload;
import lekkit.scev.server.IMachineHandle;
import lekkit.scev.server.MachineManager;
import lekkit.scev.server.MachineState;
import lekkit.scev.server.VideoKeyframeRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Minimal computer-case block entity. Implements {@link Container} so that menu
 * {@link net.minecraft.world.inventory.Slot}s can attach to it directly.
 */
public abstract class ComputerCaseBlockEntity extends ScevBlockEntity
        implements IMachineHandle, Container {

    protected final int caseSlots;
    protected final int maxMotherboardLevel;
    protected NonNullList<ItemStack> items;
    protected UUID machineUuid;
    protected boolean unloaded;

    /** Incremented every serverTick, used for animated splash / diagnostics. */
    protected int tickCount;

    /**
     * Per-machine H.264 encoder for the framebuffer broadcast path.
     * Lazy-initialized on the first frame of a given dimension; re-
     * created if the framebuffer resizes. Destroyed on {@link #powerOff}
     * and {@link #setRemoved}. {@code null} until first use.
     */
    @org.jetbrains.annotations.Nullable
    private H264Encoder h264Encoder;
    private int h264EncoderWidth = -1;
    private int h264EncoderHeight = -1;

    /**
     * Reusable scratch buffers for the encode path. YUV I420 needs
     * {@code width * height * 3 / 2} bytes; sized on first use to match
     * the encoder dimensions.
     */
    @org.jetbrains.annotations.Nullable
    private byte[] h264YuvScratch;

    /**
     * Frames emitted since the last forced IDR. Drives the periodic-
     * keyframe heuristic: we force an IDR every {@link #IDR_INTERVAL_FRAMES}
     * so a late-joining client's decoder recovers within bounded time
     * without needing a client→server keyframe-request protocol.
     *
     * A proper "new watcher detected" trigger (à la oc2r's
     * ProjectorLoadBalancer) would be tighter — 0 ms recovery for the
     * specific new client, no bandwidth cost for the steady state —
     * but requires keep-alive pings and per-watcher state we don't
     * have yet.
     */
    private int h264FramesSinceIdr;
    private static final int IDR_INTERVAL_FRAMES = 40; // 2 s at 20 Hz

    /**
     * Peripheral bus controller — lazily created on first tick so we have
     * a valid level reference. Null until then. See {@link PeripheralBus}
     * for the scan model.
     */
    protected @org.jetbrains.annotations.Nullable PeripheralBusController peripheralBus;

    protected ComputerCaseBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                      int maxMotherboardLevel, int extensionSlots) {
        super(type, pos, state);
        this.maxMotherboardLevel = maxMotherboardLevel;
        this.caseSlots = extensionSlots + 1; // +1 motherboard slot
        this.items = NonNullList.withSize(caseSlots, ItemStack.EMPTY);
        this.machineUuid = UUID.randomUUID();
    }

    /* ---------------- IMachineHandle ---------------- */

    @Override
    public UUID getMachineUUID() { return machineUuid; }

    @Override
    public boolean isValid() {
        return !isRemoved() && level != null
                && level.getBlockEntity(worldPosition) == this;
    }

    @Override
    public void powerOn() {
        MachineState state = initMachineState();
        if (state != null) {
            // Hand the machine its world location so systems that broadcast
            // per-machine packets (e.g. SoundStreamManager) know where to
            // send them. Only the server-side ServerLevel is meaningful for
            // packet dispatch; on the client-side integrated server both
            // sides agree and this is still ServerLevel.
            if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                state.setLocation(sl, worldPosition);
            }
            state.start();
        }
    }

    @Override
    public void powerOff() {
        UUID uuid = getMachineUUID();
        MachineManager.destroyMachineState(uuid);
        closeH264Encoder();
        // Tell nearby clients to evict their cached DisplayState so they stop
        // rendering the last frame of the now-gone VM. Zero dimensions act as
        // the dispose sentinel — see DisplayManager#acceptRemote.
        broadcastDisplayDispose(uuid);
    }

    @Override
    public void power() {
        if (isPowered()) powerOff(); else powerOn();
    }

    @Override
    public void reset() {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state != null) state.reset();
    }

    @Override
    public boolean isPowered() {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        return state != null && state.isPowered();
    }

    /* ---------------- Redstone / GPIO ---------------- */

    /** Accessor for the peripheral bus scan result. Null until first tick. */
    public @org.jetbrains.annotations.Nullable PeripheralBus peripheralBus() {
        return peripheralBus != null ? peripheralBus.getBus() : null;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // Release bus bindings on all elements so peripherals don't keep
        // pointing at a dead computer. Idempotent — safe on chunk unload.
        if (peripheralBus != null) peripheralBus.dispose();
        // Also free the per-machine H.264 encoder if we had one —
        // otherwise a chunk-unload-while-running leaks the native
        // encoder state until destroyMachineState runs.
        closeH264Encoder();
    }

    /**
     * Notify the bus controller that something nearby changed (block place,
     * break, rotate). Hooked from {@link DirectionalBlock#neighborChanged}.
     */
    public void invalidatePeripheralBus() {
        if (peripheralBus != null) peripheralBus.invalidate();
    }

    @Override
    public void onNeighborBlockChanged(BlockPos fromPos) {
        invalidatePeripheralBus();
    }

    /**
     * Forward a packed 6-bit redstone input to the GPIO card (if installed).
     *
     * <p>{@code signals} arrives from the block's neighbour-update path in
     * world-oriented form: bit N = {@link Direction#ordinal()} N. The VM
     * however expects block-relative pins (FRONT/BACK/LEFT/RIGHT/TOP/BOTTOM)
     * so that firmware authors get a stable port layout regardless of which
     * way the case was placed. Remap via {@link GpioPinMap#worldToRelative}
     * before handing to {@link GpioDevice#writePins}.
     */
    @Override
    public void onRedstoneInput(int signals) {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state == null) return;
        GpioDevice gpio = state.getGPIO();
        if (gpio == null) return;
        gpio.writePins(GpioPinMap.worldToRelative(signals & GpioPinMap.PIN_MASK, facing()));
    }

    /**
     * Block's current horizontal facing. Falls back to NORTH when the block
     * state has no FACING property (defensive — every ScevBlock is a
     * {@link DirectionalBlock} today, but guard the assumption so a future
     * non-directional case doesn't NPE here).
     */
    private Direction facing() {
        BlockState bs = getBlockState();
        if (bs.hasProperty(DirectionalBlock.FACING)) {
            return bs.getValue(DirectionalBlock.FACING);
        }
        return Direction.NORTH;
    }

    /**
     * Ticker:
     * <ol>
     *   <li>Poll the GPIO card, push changes out as redstone signals so wires /
     *       lamps / comparators see them.</li>
     *   <li>Paint the animated {@link BootSplash} heartbeat into the framebuffer
     *       so the user has visible proof the server is ticking this machine —
     *       even when no firmware is rendering. Skipped when no display is
     *       attached. Firmware-written pixels outside the heartbeat region are
     *       left alone.</li>
     * </ol>
     * Skipped entirely when no machine is running.
     */
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;

        // Peripheral-bus scan runs every tick (cheap — the controller
        // short-circuits when the bus is clean) regardless of VM state.
        // We want keyboards / displays to discover the computer whether
        // it's powered or not; the attachment is the "you belong to this
        // machine" pointer, not an "I'm running" pointer.
        if (peripheralBus == null) {
            peripheralBus = new PeripheralBusController(level, pos, getMachineUUID());
        }
        peripheralBus.tick();

        MachineState machineState = MachineManager.getMachineState(getMachineUUID());
        if (machineState == null) return;
        tickCount++;

        // Redstone output sync. The GPIO device speaks block-relative pins;
        // project back to world-oriented before storing (setOutRedstoneSignals
        // expects bit N = Direction.ordinal() N, since that's what
        // DirectionalBlock#getSignal indexes into per-face).
        GpioDevice gpio = machineState.getGPIO();
        if (gpio == null) {
            if (getOutRedstoneSignals() != 0) setOutRedstoneSignals(0);
        } else {
            int relPins = gpio.readPins() & GpioPinMap.PIN_MASK;
            int worldPins = GpioPinMap.relativeToWorld(relPins, facing());
            if (worldPins != getOutRedstoneSignals()) setOutRedstoneSignals(worldPins);
        }

        // Animated splash heartbeat.
        FramebufferView fb = machineState.getDisplay();
        if (fb != null) {
            BootSplash.paintHeartbeat(fb, tickCount);
            // Broadcast every server tick (20 Hz). The prior 5 Hz gate
            // existed because raw-BGRA was ~1.2 MB per 640×480 frame;
            // H.264 at our bitrate is ~60 KB/s per listener, so rate is
            // now CPU-bound by the encoder (cheap at this rate) rather
            // than network-bound. Singleplayer also broadcasts because
            // DisplayManager.OPTIMIZE_SINGLEPLAYER is off while the
            // codec stabilises — the integrated server's memory-pipe
            // transport keeps this near-free regardless.
            if (level instanceof ServerLevel sl) {
                broadcastFramebuffer(sl, fb, machineState.getClock());
            }
        }
    }

    /**
     * Pack the framebuffer pixels into a {@link DisplayPayload} and
     * send to every {@link net.minecraft.server.level.ServerPlayer}
     * within {@link #DISPLAY_BROADCAST_RADIUS} of this block via
     * {@link PacketDistributor#sendToPlayersNear}.
     */
    private void broadcastFramebuffer(ServerLevel sl, FramebufferView fb, MachineClock clock) {
        int width = fb.width();
        int height = fb.height();

        // H.264 requires even dimensions for 4:2:0 chroma subsampling.
        // Skip the frame rather than pad — a framebuffer with odd
        // dimensions is a misconfiguration worth noticing rather than
        // silently compensating for.
        if ((width & 1) != 0 || (height & 1) != 0) return;

        int len = fb.byteSize();
        byte[] pixels = new byte[len];
        ByteBuffer src = fb.pixels();
        // Defensive: pixels() resets position to 0 on each call; stable length.
        src.get(pixels, 0, Math.min(len, src.remaining()));

        // Encode BGRA -> YUV I420 -> H.264 NAL units. Encoder is
        // per-machine, held as a field; re-created only if the
        // framebuffer dimensions change between frames (VM switched
        // graphics mode, etc.).
        if (h264Encoder == null || h264EncoderWidth != width || h264EncoderHeight != height) {
            if (h264Encoder != null) h264Encoder.close();
            h264Encoder = new H264Encoder(width, height,
                    H264Encoder.DEFAULT_BITRATE_BPS, /* fps */ 20);
            h264EncoderWidth = width;
            h264EncoderHeight = height;
            h264YuvScratch = new byte[width * height * 3 / 2];
            // Newly-created encoder naturally emits its first frame as
            // an IDR, so reset the counter so we don't redundantly
            // force a second IDR on frame 1.
            h264FramesSinceIdr = 0;
        } else if (VideoKeyframeRequests.consume(getMachineUUID())) {
            // A client asked for a keyframe (late-joiner opening the
            // screen, post-desync recovery) — force IDR immediately
            // so the next emitted frame resyncs their decoder.
            h264Encoder.forceIdr();
            h264FramesSinceIdr = 0;
        } else if (++h264FramesSinceIdr >= IDR_INTERVAL_FRAMES) {
            // Periodic forced IDR as a safety net for cases the
            // client's explicit request couldn't cover — dropped
            // request packet, decoder state drift, client-server
            // message-order corner cases. Hot path is the consume()
            // branch above; this only fires when no one's asked
            // recently.
            h264Encoder.forceIdr();
            h264FramesSinceIdr = 0;
        }

        BgraYuv.bgraToI420(pixels, width, height, h264YuvScratch);
        byte[] nal = h264Encoder.encode(h264YuvScratch);
        if (nal.length == 0) return;  // encoder skipped this frame

        // PTS read at capture time, from the same clock the audio path
        // uses. Video frames on the client are presented against the
        // current MediaClock position, which the audio stream naturally
        // drives. The Long-returning accessor is the Java-interop path
        // for the Micros value class — see MachineClock#nowPtsMicrosLong.
        //
        // DisplayPayload.pixels now carries H.264 NAL bytes, not raw
        // BGRA. Width/height still describe the decoded frame so the
        // client can allocate its DisplayState correctly.
        DisplayPayload payload = DisplayPayload.create(
                getMachineUUID(),
                clock.nowPtsMicrosLong(),
                (short) width,
                (short) height,
                nal);
        sendToNearby(sl, payload);
    }

    /**
     * Release the per-machine H.264 encoder. Called on power-off and
     * BE removal. No-op if the encoder was never allocated.
     */
    private void closeH264Encoder() {
        if (h264Encoder != null) {
            h264Encoder.close();
            h264Encoder = null;
            h264EncoderWidth = -1;
            h264EncoderHeight = -1;
            h264YuvScratch = null;
        }
    }

    /**
     * Dispose sentinel: width=0, height=0, no pixels. Client evicts
     * DisplayState. Sent only on multi-player / LAN so the singleplayer host
     * (which already evicts via DisplayManager.get's stale check) isn't
     * pinged for nothing.
     */
    private void broadcastDisplayDispose(UUID uuid) {
        if (!(level instanceof ServerLevel sl) || sl.getServer().isSingleplayer()) return;
        // Dispose is a sentinel — width=0/height=0 tells the client to
        // evict its cached DisplayState. PTS is irrelevant (the client
        // short-circuits on size 0 before looking at the clock).
        sendToNearby(sl, DisplayPayload.create(uuid, 0L, (short) 0, (short) 0, new byte[0]));
    }

    /** Radius (blocks) a {@link DisplayPayload} is broadcast within. */
    private static final int DISPLAY_BROADCAST_RADIUS = 16;

    private void sendToNearby(ServerLevel sl, DisplayPayload payload) {
        PacketDistributor.sendToPlayersNear(sl, null,
                worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5,
                DISPLAY_BROADCAST_RADIUS,
                payload);
    }

    /** Direction-specific outgoing signal (0 or 15), for block.getSignal. */
    public int getRedstoneSignalFor(Direction dir) {
        return getOutRedstoneSignal(dir);
    }

    @Override
    public MotherboardItem getMotherboardItem() {
        ItemStack mb = items.get(0);
        return mb.getItem() instanceof MotherboardItem m ? m : null;
    }

    @Override
    public int getCaseSlotCount() { return caseSlots; }

    @Override
    public int getMaxMotherboardLevel() { return maxMotherboardLevel; }

    /* ---------------- Machine state ---------------- */

    /**
     * If true, the machine always gets a display attached, even without a VGA
     * PCI card (laptops / tinkerpads ship with a built-in screen). Default:
     * false. Subclasses override to flip the flag.
     */
    protected boolean forceBuiltInDisplay() { return false; }

    /** Return the existing machine or build a new one from the current motherboard. */
    protected MachineState initMachineState() {
        MachineState state = MachineManager.getMachineState(getMachineUUID());
        if (state == null) state = buildMachine();
        return state;
    }

    /**
     * Build the MachineState by parsing the motherboard + components into a
     * {@link MachineSpec}, then asking {@link MachineManager} to instantiate
     * a backend. Returns {@code null} if no motherboard is installed or
     * backend creation fails.
     */
    protected MachineState buildMachine() {
        ItemStack mbStack = items.get(0);
        MachineSpec spec = MachineSpecParser.fromMotherboard(
                getMachineUUID(), mbStack, forceBuiltInDisplay());
        if (spec == null) return null;
        // Parser may have mutated sub-stacks inside the motherboard's
        // MOTHERBOARD_INVENTORY data component (STORAGE_UUID allocation on
        // flash / NVMe). Persist those mutations by flagging the BE dirty
        // so the next save round-trip captures them. Without this,
        // allocated UUIDs get re-generated every boot, template bytes get
        // re-copied, and player-written data is orphaned under a stale id.
        setChanged();
        return MachineManager.createMachineState(spec);
    }

    /* ---------------- NBT ---------------- */

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(caseSlots, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        if (tag.hasUUID("MachineUUID")) {
            machineUuid = tag.getUUID("MachineUUID");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putUUID("MachineUUID", machineUuid);
    }

    /* ---------------- Container ---------------- */

    @Override
    public int getContainerSize() { return items.size(); }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) if (!stack.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.size()) {
            items.set(slot, stack);
            if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
            setChanged();
        }
    }

    @Override
    public int getMaxStackSize() { return 1; }

    @Override
    public void setChanged() {
        super.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return isValid() && player.distanceToSqr(
                worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == 0) {
            return stack.getItem() instanceof MotherboardItem mb
                    && mb.getLevel() <= maxMotherboardLevel;
        }
        return true;
    }
}
