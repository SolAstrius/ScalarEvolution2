/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import io.wispforest.owo.ui.core.Surface
import lekkit.scev.client.render.CrtFxShader
import lekkit.scev.client.terminal.BootDemo
import lekkit.scev.client.terminal.GlfwToVt
import lekkit.scev.client.terminal.MltermBackend
import lekkit.scev.client.terminal.MltermNative
import lekkit.scev.client.terminal.TerminalActiveHost
import lekkit.scev.client.terminal.setup.SetupController
import lekkit.scev.client.terminal.setup.SetupModel
import lekkit.scev.client.terminal.setup.SetupRenderer
import lekkit.scev.client.terminal.setup.SetupSyncDispatcher
import lekkit.scev.menu.TerminalMenu
import lekkit.scev.network.SerialInPayload
import lekkit.scev.network.SetupEditPayload
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW

/**
 * owo-ui screen for the VT100 terminal block.
 *
 * Pipeline:
 *   1. [TerminalActiveHost] (singleton, client-side) owns the
 *      [MltermBackend] + texture for the active machine UUID. The
 *      screen acquires the host on open and releases on close.
 *   2. The host's worker thread renders into a NativeImage; the
 *      Surface lambda blits it into the cell each frame.
 *   3. The same texture is exposed to
 *      [lekkit.scev.client.render.blockentity.TerminalRenderer] for the
 *      in-world block face — so opening the GUI also lights up the
 *      block's screen face in the world (and vice versa: closing
 *      the GUI keeps the block face live).
 *
 * Bytes are pushed via charTyped + keyPressed → [GlfwToVt] →
 * [SerialInPayload] when bound to a machine, or directly into the
 * backend in unbound (BootDemo) mode.
 */
class TerminalScreen(menu: TerminalMenu, inv: Inventory, title: Component) :
    ScevDisplayScreen<TerminalMenu>(menu, inv, title) {

    /** The backend handle for this screen's machine. Acquired in init,
     *  released in [removed]. The host keeps the backend alive past
     *  release so the in-world block face stays live. */
    private val handle: TerminalActiveHost.Handle

    /** Synthetic UUID used in unbound (BootDemo) mode so the host can
     *  still key on something. The block face won't show this — there's
     *  no BE bound to a synthetic UUID. */
    private val ownerUuid: java.util.UUID

    /**
     * Local Setup model. The persistent slice mirrors the BE's NBT
     * (initialised from defaults; replaced when the server pushes a
     * [lekkit.scev.network.SetupSyncPayload] for our UUID). The view
     * slice is purely client — page / focus / answerback editor.
     *
     * Edits to the persistent slice are NOT applied optimistically —
     * we send a [SetupEditPayload] up and wait for the echo. View
     * edits ARE applied locally and only locally.
     */
    @Volatile private var setupModel: SetupModel = SetupModel()

    /** True while the player has F3'd into Setup mode. While true, the
     *  backend displays whichever Setup page is in [setupModel.view]
     *  and live kernel TX bytes are queued (not painted) until exit. */
    private var inSetupMode: Boolean = false

    init {
        check(MltermNative.ensureLoaded()) {
            "libscev_term native isn't loaded — VT100 GUI can't open"
        }

        val boundUuid = menu.machineUuid
        if (boundUuid != null) {
            ownerUuid = boundUuid
            handle = TerminalActiveHost.acquire(boundUuid, menu.kind)
            // Server replays the kernel-console buffer to us via
            // PlayerContainerEvent.Open; that ride lands in the
            // host's backend through the SerialDispatcher subscription
            // the host already installed.

            // Subscribe for Setup state pushes for THIS uuid. The
            // server fires one synchronously on PlayerContainerEvent.Open
            // (so the initial state lands here within a few ticks)
            // and one on every authoritative edit afterwards.
            SetupSyncDispatcher.subscribe(boundUuid) { state ->
                setupModel = setupModel.copy(persistent = state)
                if (inSetupMode) repaintSetup()
            }
        } else {
            // Unbound — disconnected demo mode. The host's UUID is
            // a per-screen random value so it doesn't collide with
            // any real machine; the block face won't render this
            // because no BE is bound to it.
            ownerUuid = java.util.UUID.randomUUID()
            handle = TerminalActiveHost.acquire(ownerUuid, menu.kind)
            handle.backend.feed(BootDemo.bytes())
            // No SetupSync subscribe in unbound mode — there's no BE
            // to mirror. Setup pages still render against the local
            // default model so F3 in unbound mode is fine for testing.
        }
    }

    /** Convenience accessor — the screen always asks the host for the
     *  current backend rather than caching it directly. */
    private val backend: MltermBackend get() = handle.backend

    /** Scratch buffer for draining mlterm reply bytes (DA / DSR /
     *  etc.) after each render. 256 bytes is more than enough — DA
     *  replies are ~30 bytes; we drain every ~16ms so backlog never
     *  builds up. */
    private val replyScratch: ByteArray = ByteArray(256)

    /** owo Surface lambda — write the worker's latest published
     *  frame directly into the NativeImage's backing buffer
     *  (single C-side memcpy + ARGB→RGBA byte swap), upload the
     *  texture, blit. ~0.5ms/frame total.
     *
     *  No pump here — the worker thread owns mlterm and ticks on
     *  its own ~60Hz cadence; we just read its latest publish. */
    override val displaySurface: Surface = Surface { ctx, c ->
        backend.renderToPtr(handle.nativeImagePixelsPtr, backend.pixelW)
        // Persistence (CPU-side phosphor decay) — same pass the in-
        // world TerminalRenderer applies. Doing it here keeps the GUI
        // visually identical to the in-world face when both are
        // visible (e.g., a second player viewing the block face while
        // we have the GUI open).
        handle.applyPersistence(setupModel.persistent.persistence)

        // Drain any pending reply bytes mlterm wants to send back
        // to the guest (DA, DSR, etc.). Forward via the same
        // SerialIn path keystrokes use — DA replies travel in the
        // exact same direction conceptually (terminal → guest UART
        // RX). Empty-loop fast-path: pollReply returns 0 when the
        // ring's empty, which is the common case (only a handful
        // of replies per session).
        if (menu.machineUuid != null) {
            val n = backend.pollReply(replyScratch)
            if (n > 0) {
                PacketDistributor.sendToServer(SerialInPayload(replyScratch.copyOf(n)))
            }
        }

        handle.texture.upload()

        // Stage GUI overrides for the world-level FX uniforms before
        // emitting vertices: drop curvature to 0 because barrel
        // distortion makes it hard to read the column under the
        // cursor when typing, and we don't want to fight the player
        // about that. Other effects (vignette, bloom) stay at world
        // defaults — they don't impair text legibility and look
        // good on the GUI too.
        CrtFxShader.stageEffects(
            curvature = 0f,
            vignette = CrtFxShader.JSON_DEFAULT_VIGNETTE,
            bloom = CrtFxShader.JSON_DEFAULT_BLOOM,
            apertureMask = CrtFxShader.JSON_DEFAULT_APERTURE,
        )

        // Manual vertex emit through our CRT FX RenderType so the GUI
        // surface gets the same shader effects as the in-world face
        // (phosphor / scanlines / vignette / bloom). Plain ctx.blit
        // would route through the default 2D pipeline and bypass the
        // shader entirely.
        val tint = CrtFxShader.packTint(setupModel.persistent)
        val a = (tint ushr 24) and 0xFF
        val r = (tint ushr 16) and 0xFF
        val g = (tint ushr  8) and 0xFF
        val b =  tint          and 0xFF
        val mat = ctx.pose().last().pose()
        val buf = ctx.bufferSource().getBuffer(CrtFxShader.renderType(handle.texLocation))
        val x0 = c.x().toFloat()
        val y0 = c.y().toFloat()
        val x1 = (c.x() + c.width()).toFloat()
        val y1 = (c.y() + c.height()).toFloat()
        // Quad in screen-space: BL → BR → TR → TL. UV origin is the
        // texture's top-left, which is also the GUI's top-left at our
        // 1:1 mapping. v inverted at the corners so the texture
        // doesn't render upside-down.
        buf.addVertex(mat, x0, y1, 0f).setUv(0f, 1f).setColor(r, g, b, a)
        buf.addVertex(mat, x1, y1, 0f).setUv(1f, 1f).setColor(r, g, b, a)
        buf.addVertex(mat, x1, y0, 0f).setUv(1f, 0f).setColor(r, g, b, a)
        buf.addVertex(mat, x0, y0, 0f).setUv(0f, 0f).setColor(r, g, b, a)
        // Force-flush this RenderType's batch so the quad lands on
        // screen before the GUI's other elements are drawn (otherwise
        // owo's surface buffer might not flush our custom RenderType
        // until after it's rendered other GUI bits at the same z).
        ctx.flush()
    }

    /** Fixed cell, 1:1 against the mlterm-rendered pixel surface —
     *  font cell pitch already determines what 80×24 looks like. */
    override fun computeDisplaySize(): Pair<Int, Int> = Pair(backend.pixelW, backend.pixelH)

    override fun removed() {
        // If we're closing while still in Setup mode, gracefully
        // resume the live feed so the in-world block face doesn't
        // freeze on whatever Setup overlay we last painted.
        if (inSetupMode) {
            TerminalActiveHost.resumeLiveFeed()
            inSetupMode = false
        }
        menu.machineUuid?.let { SetupSyncDispatcher.unsubscribe(it) }
        TerminalActiveHost.release()
        super.removed()
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // F3 — toggle Setup mode. Real DEC SET-UP key was a single
        // physical key (F3 on VT220+); we use F3 across all kinds.
        // Intercepted unconditionally so MC's F3 debug overlay never
        // sees the press while a TerminalScreen is open.
        if (keyCode == GLFW.GLFW_KEY_F3 && !inSetupMode) {
            enterSetupMode()
            return true
        }

        if (inSetupMode) {
            return handleSetupKey(keyCode, modifiers)
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(keyCode, scanCode, modifiers)
        }
        val bytes = GlfwToVt.encode(keyCode, modifiers)
        if (bytes != null) {
            sendInput(bytes)
        }
        // Swallow everything else (printable letters arrive via charTyped).
        // Letting super run hands the key to MC's keybind dispatch — the
        // inventory key 'E' would close the GUI mid-typing, etc.
        return true
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (inSetupMode) {
            // Setup never reacts to releases; just swallow so MC
            // doesn't get the event either.
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return super.keyReleased(keyCode, scanCode, modifiers)
        }
        return true
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (inSetupMode) {
            val a = SetupController.charTyped(setupModel, codePoint) ?: return true
            applyAction(a)
            return true
        }
        sendInput(codePoint.toString().toByteArray(Charsets.UTF_8))
        return true
    }

    /* ---------- Setup mode -------------------------------------------- */

    /**
     * Enter Setup mode: pause live kernel-TX feed so the overlay
     * doesn't fight the kernel for screen real estate, paint the
     * default Setup page (SET-UP A), commit the in-mode flag.
     */
    private fun enterSetupMode() {
        inSetupMode = true
        TerminalActiveHost.pauseLiveFeed()
        repaintSetup()
    }

    /**
     * Exit Setup mode: clear the in-mode flag and ask the host to
     * resume — which clears the backend and replays the live history,
     * leaving the player with the same display they would have had
     * if they'd never F3'd in.
     */
    private fun exitSetupMode() {
        if (!inSetupMode) return
        inSetupMode = false
        TerminalActiveHost.resumeLiveFeed()
        // Reset transient view state so a fresh F3 entry starts on
        // SET-UP A with no answerback edit half-committed.
        setupModel = setupModel.copy(view = SetupModel.ViewState())
    }

    private fun handleSetupKey(keyCode: Int, modifiers: Int): Boolean {
        val action = SetupController.keyPressed(setupModel, keyCode, modifiers)
            ?: return true              // unmapped keys still get swallowed
        applyAction(action)
        return true
    }

    private fun applyAction(action: SetupController.Action) {
        if (action.exitSetup) {
            exitSetupMode()
            return
        }
        if (action.persistentChanged && menu.machineUuid != null) {
            // Send the new persistent state to the server. Don't apply
            // optimistically — wait for the echoed SetupSyncPayload so
            // multi-viewer rooms stay consistent. UI feedback is the
            // re-render below, which uses the un-applied model so the
            // user can see their typing land on row 23 etc. even before
            // the round-trip; the persistent fields revert visually if
            // the server somehow rejects.
            PacketDistributor.sendToServer(SetupEditPayload(action.next.persistent))
        }
        // View-only updates apply instantly.
        setupModel = action.next
        repaintSetup()
    }

    /** Re-render the current Setup page into the backend. */
    private fun repaintSetup() {
        val info = SetupRenderer.Info(
            boundUuid = menu.machineUuid?.toString() ?: "00000000-0000-0000-0000-000000000000",
            term = menu.kind.termType,
        )
        backend.feed(SetupRenderer.render(setupModel, info))
    }

    /** Route typed bytes either over the wire (when bound to a
     *  machine) or directly into the local backend (when running
     *  the disconnected boot demo, so the player can still see
     *  their keystrokes echoed locally). */
    private fun sendInput(bytes: ByteArray) {
        if (menu.machineUuid != null) {
            PacketDistributor.sendToServer(SerialInPayload(bytes))
        } else {
            backend.feed(bytes)
        }
    }
}
