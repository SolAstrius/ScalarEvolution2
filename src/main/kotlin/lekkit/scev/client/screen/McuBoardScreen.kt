/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import io.wispforest.owo.ui.base.BaseOwoHandledScreen
import io.wispforest.owo.ui.component.BoxComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Component as OwoComponent
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import lekkit.scev.blockentity.McuBoardBlockEntity
import lekkit.scev.client.screen.owo.ScevSurfaces
import lekkit.scev.client.screen.owo.fixed
import lekkit.scev.client.screen.owo.horizontalFlow
import lekkit.scev.client.screen.owo.translatable
import lekkit.scev.client.screen.owo.verticalFlow
import lekkit.scev.client.screen.widget.IconButton
import lekkit.scev.items.FlashFirmware
import lekkit.scev.items.FlashItem
import lekkit.scev.items.SocItem
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.main.ScevDataComponents
import lekkit.scev.menu.McuBoardMenu
import lekkit.scev.network.MachineResetPayload
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor

/**
 * owo-ui port of the MCU board screen — fully declarative, no BG asset.
 * Layout:
 *
 *   ┌── ScevSurfaces.PANEL ───────────────────────────┐
 *   │  ┌─ console (black) ─┐  [SoC] [Flash]           │
 *   │  │ halted            │                          │
 *   │  │ fw: -             │                          │
 *   │  │ rv64imafdc 1x ... │                          │
 *   │  └───────────────────┘                          │
 *   │  [⏻ pwr]  ●●●●●●  ← GPIO LEDs                    │
 *   │  ┌─────────── inventory grid ───────────┐       │
 *   │  └──────────────────────────────────────┘       │
 *   │  ┌─────────── hotbar ───────────────────┐       │
 *   │  └──────────────────────────────────────┘       │
 *   └──────────────────────────────────────────────────┘
 *
 * Console + LED visuals are owo components whose state is refreshed in
 * [containerTick]; the existing [IconButton] sprite is reused for the
 * power widget via [Components.wrapVanillaWidget] so we don't lose its
 * three-frame idle/hover/pressed animation + fail-flash overlay.
 */
class McuBoardScreen(menu: McuBoardMenu, inv: Inventory, title: Component) :
    BaseOwoHandledScreen<FlowLayout, McuBoardMenu>(menu, inv, title) {

    private var failMessage: Component? = null
    private var failMessageEndMs: Long = 0L

    // Console state labels — created in build(), text updated in containerTick().
    private lateinit var statusLabel: LabelComponent
    private lateinit var fwLabel: LabelComponent
    private lateinit var specLabel: LabelComponent

    // Six GPIO indicator boxes — colors flipped in containerTick() based on
    // McuBoardMenu.getGpioOutputMask().
    private val ledBoxes: Array<BoxComponent?> = arrayOfNulls(LED_COUNT)

    // Power button kept as the existing IconButton widget so it retains its
    // sprite-sheet animation; wrapped via Components.wrapVanillaWidget below.
    private lateinit var powerButton: IconButton

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { _, _ ->
            verticalFlow(Sizing.fill(100), Sizing.fill(100))
        }

    override fun build(rootComponent: FlowLayout) {
        rootComponent.surface(Surface.BLANK)
        rootComponent.horizontalAlignment(HorizontalAlignment.CENTER)
        rootComponent.verticalAlignment(VerticalAlignment.CENTER)

        rootComponent.child(buildPanel())
    }

    private fun buildPanel(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            surface(ScevSurfaces.PANEL)
            padding(Insets.of(PANEL_PAD))
            gap(SECTION_GAP)
            horizontalAlignment(HorizontalAlignment.CENTER)

            child(buildTopRow())
            child(buildPowerAndGpioRow())
            child(buildInventoryGrid())
            child(buildHotbarRow())
        }

    /** Console panel on the left, two-slot column on the right. */
    private fun buildTopRow(): FlowLayout =
        horizontalFlow(Sizing.content(), Sizing.content()).apply {
            verticalAlignment(VerticalAlignment.CENTER)
            gap(8)
            child(buildConsole())
            child(buildSlotColumn())
        }

    /**
     * Black "console" panel with three live-readout text rows. We initialize
     * each label's text from current menu state so the screen renders right
     * on its first frame; [containerTick] rewrites them each server tick.
     */
    private fun buildConsole(): FlowLayout =
        verticalFlow(CONSOLE_W.fixed, CONSOLE_H.fixed).apply {
            surface(Surface.flat(CONSOLE_BG))
            padding(Insets.of(CONSOLE_INNER_PAD))
            gap(1)

            statusLabel = Components.label(statusText()).color(Color.ofRgb(TEXT_DIM_RGB))
            fwLabel     = Components.label(fwText()).color(Color.ofRgb(TEXT_DIM_RGB))
            specLabel   = Components.label(specText()).color(Color.ofRgb(TEXT_DIM_RGB))

            child(statusLabel)
            child(fwLabel)
            child(specLabel)
        }

    private fun buildSlotColumn(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            gap(2)
            child(slotCell(0, Component.translatable("tooltip.scev.mcu.soc")))
            child(slotCell(1, Component.translatable("tooltip.scev.mcu.flash")))
        }

    /** Power button on the left, six GPIO LED indicators on the right. */
    private fun buildPowerAndGpioRow(): FlowLayout =
        horizontalFlow(Sizing.content(), Sizing.content()).apply {
            verticalAlignment(VerticalAlignment.CENTER)
            gap(10)

            // IconButton's coords get reset by Components.wrapVanillaWidget +
            // owo's layout pass — pass any (x, y) to the constructor.
            powerButton = IconButton(
                0, 0, POWER_BUTTON_SIZE, POWER_BUTTON_SIZE,
                POWER_BUTTON_TEX, 42, 14,
                15, 1,    // idle (off)
                 1, 1,    // hover (off)
                29, 1,    // pressed
                Component.translatable("button.scev.power"),
                ::onPowerClicked,
            ).withToggle({ menu.isMachinePowered() }, 1, 1, 1, 1)
            powerButton.tooltip = Tooltip.create(Component.translatable("tooltip.scev.power"))

            child(Components.wrapVanillaWidget(powerButton))

            // LED row — 6 boxes with vanilla-pitch (LED_GAP) between them.
            child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
                verticalAlignment(VerticalAlignment.CENTER)
                gap(LED_GAP)
                for (i in 0 until LED_COUNT) {
                    val box = Components.box(LED_SIZE.fixed, LED_SIZE.fixed)
                        .fill(true)
                        .color(Color.ofArgb(LED_OFF_RGB))
                    ledBoxes[i] = box
                    child(box)
                }
            })
        }

    private fun buildInventoryGrid(): FlowLayout =
        verticalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(2))
            for (row in 0 until INV_ROWS) {
                child(horizontalFlow(Sizing.content(), Sizing.content()).apply {
                    gap(0)
                    for (col in 0 until INV_COLS) {
                        child(slotCell(FIRST_INV_SLOT + row * INV_COLS + col))
                    }
                })
            }
        }

    private fun buildHotbarRow(): FlowLayout =
        horizontalFlow(Sizing.content(), Sizing.content()).apply {
            gap(0)
            margins(Insets.top(4))
            for (col in 0 until INV_COLS) {
                child(slotCell(FIRST_HOTBAR_SLOT + col))
            }
        }

    /** Shared with FlashProgrammerScreen — same bevel, same padding. */
    private fun slotCell(slotIndex: Int, tooltip: Component? = null): OwoComponent =
        Containers.verticalFlow(SLOT_SIZE.fixed, SLOT_SIZE.fixed).apply {
            surface(ScevSurfaces.INSET)
            padding(Insets.of(1))
            child(slotAsComponent(slotIndex).apply {
                if (tooltip != null) tooltip(tooltip)
            })
        }

    private fun onPowerClicked(btn: IconButton) {
        if (menu.isMachinePowered()) {
            PacketDistributor.sendToServer(MachineResetPayload(false))
            return
        }

        val r = menu.validateForPower()
        if (r == McuBoardMenu.ValidationResult.OK) {
            PacketDistributor.sendToServer(MachineResetPayload(false))
            return
        }

        btn.flashFail(500)
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.8F))
        failMessage = Component.translatable(r.langKey()).withStyle(ChatFormatting.RED)
        failMessageEndMs = System.currentTimeMillis() + FAIL_MESSAGE_DURATION_MS
    }

    /* ---------------- console + LED state refresh ---------------- */

    override fun containerTick() {
        super.containerTick()
        statusLabel.text(statusText())
        statusLabel.color(Color.ofRgb(if (menu.isMachinePowered()) TEXT_OK_RGB else TEXT_DIM_RGB))
        fwLabel.text(fwText())
        specLabel.text(specText())

        val mask = menu.getGpioOutputMask()
        for (i in 0 until LED_COUNT) {
            val on = ((mask shr i) and 1) != 0
            ledBoxes[i]?.color(Color.ofArgb(if (on) LED_ON_RGB else LED_OFF_RGB))
        }
    }

    private fun statusText(): Component =
        Component.literal(if (menu.isMachinePowered()) "running" else "halted")

    private fun fwText(): Component {
        val flash = menu.mcu.getItem(McuBoardBlockEntity.SLOT_FLASH)
        val s = when {
            flash.isEmpty || flash.item !is FlashItem -> "fw: -"
            flash.has(ScevDataComponents.FIRMWARE_BYTES.get())       -> "fw: custom"
            flash.has(ScevDataComponents.FIRMWARE_ID_OVERRIDE.get()) -> "fw: modded"
            else -> {
                val kind = flash.get(ScevDataComponents.FIRMWARE_KIND.get()) ?: FlashFirmware.LINUX
                "fw: ${kind.serializedName}"
            }
        }
        return Component.literal(s)
    }

    private fun specText(): Component {
        val socStack = menu.mcu.getItem(McuBoardBlockEntity.SLOT_SOC)
        val soc = socStack.item as? SocItem ?: return Component.literal("")
        return Component.literal("${soc.isa} ${soc.hartCount}x ${SocItem.formatRam(soc.embeddedRamKib)}")
    }

    /* ---------------- vanilla overrides + fail message ---------------- */

    /**
     * owo's renderBackground is a no-op; vanilla paints both the dim
     * overlay AND dispatches to renderBg from there in 1.21. Inline the
     * dim call. No PNG to blit — owo's PANEL surface paints the frame.
     */
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderTransparentBackground(g)
    }
    /** Skip inherited "Inventory" / title labels — console row carries identity. */
    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {}

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        renderFailMessage(g)
        renderTooltip(g, mouseX, mouseY)
    }

    private fun renderFailMessage(g: GuiGraphics) {
        val msg = failMessage ?: return
        val remaining = failMessageEndMs - System.currentTimeMillis()
        if (remaining <= 0) {
            failMessage = null
            return
        }
        val alpha = if (remaining > 300) 1.0F else remaining / 300.0F
        val alphaByte = (alpha * 0xFF).toInt()

        val text = msg.string
        val textWidth = font.width(text)
        val padX = 6
        val padY = 3
        val cx = width / 2
        val y = 8

        val bgLeft = cx - textWidth / 2 - padX
        val bgRight = cx + textWidth / 2 + padX
        val bgTop = y
        val bgBottom = y + 9 + padY * 2

        val bgAlpha = minOf(alphaByte, 0xC0)
        g.fill(bgLeft, bgTop, bgRight, bgBottom, bgAlpha shl 24)
        val border = 0xFF3030 or (alphaByte shl 24)
        g.fill(bgLeft,      bgTop,        bgRight,     bgTop + 1, border)
        g.fill(bgLeft,      bgBottom - 1, bgRight,     bgBottom,  border)
        g.fill(bgLeft,      bgTop,        bgLeft + 1,  bgBottom,  border)
        g.fill(bgRight - 1, bgTop,        bgRight,     bgBottom,  border)

        val textColor = 0xFF5050 or (alphaByte shl 24)
        g.drawString(font, text, cx - textWidth / 2, bgTop + padY, textColor, true)
    }

    companion object {
        private val POWER_BUTTON_TEX: ResourceLocation =
            ScalarEvolution.rl("textures/gui/widget/power_button.png")

        private const val PANEL_PAD: Int = 8
        private const val SECTION_GAP: Int = 6
        private const val SLOT_SIZE: Int = 18

        // Console panel.
        private const val CONSOLE_W: Int = 96
        private const val CONSOLE_H: Int = 38
        private const val CONSOLE_INNER_PAD: Int = 3
        private const val CONSOLE_BG: Int = 0xFF000000.toInt()
        private const val TEXT_OK_RGB: Int  = 0x33FF33   // green when running
        private const val TEXT_DIM_RGB: Int = 0x1F8F1F

        // Power button — 12×12 sprite from the existing power_button.png sheet.
        private const val POWER_BUTTON_SIZE: Int = 12

        // GPIO LED row.
        private const val LED_COUNT: Int = 6
        private const val LED_SIZE: Int = 8
        private const val LED_GAP: Int = 4
        private const val LED_ON_RGB: Int  = 0xFFFF4040.toInt()
        private const val LED_OFF_RGB: Int = 0xFF2A2A2A.toInt()

        // Player inventory grid.
        private const val INV_ROWS: Int = 3
        private const val INV_COLS: Int = 9
        // McuBoardMenu slot indices: 0=SoC, 1=Flash, 2..28=inv, 29..37=hotbar.
        private const val FIRST_INV_SLOT: Int = 2
        private const val FIRST_HOTBAR_SLOT: Int = FIRST_INV_SLOT + INV_ROWS * INV_COLS

        private const val FAIL_MESSAGE_DURATION_MS: Long = 2500L
    }
}
