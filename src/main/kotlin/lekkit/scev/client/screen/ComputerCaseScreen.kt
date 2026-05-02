/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.screen

import com.mojang.blaze3d.systems.RenderSystem
import io.wispforest.owo.ui.base.BaseOwoHandledScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Positioning
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.VerticalAlignment
import io.wispforest.owo.ui.core.Surface
import lekkit.scev.client.screen.owo.verticalFlow
import lekkit.scev.client.screen.widget.IconButton
import lekkit.scev.items.MotherboardItem
import lekkit.scev.main.ScalarEvolution
import lekkit.scev.menu.ComputerCaseMenu
import lekkit.scev.menu.SlotDef
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
 * owo-ui port of the computer-case editor. Same rationale as
 * [MotherboardScreen]: the painted PCB BG carries the visual identity, so
 * we keep it as a `renderBg()` PNG and use owo just for slot lifecycle and
 * the wrapped power button widget.
 *
 * The sidebar (a 19×20 strip just to the left of the main panel with a
 * power button on it) is rendered at the same vanilla-positioned blit
 * coords as before — it's outside the imageWidth/imageHeight rect, so
 * positioning it as an owo child would require absolute screen-space math
 * that buys nothing over a one-line `g.blit`.
 */
class ComputerCaseScreen(menu: ComputerCaseMenu, inv: Inventory, title: Component) :
    BaseOwoHandledScreen<FlowLayout, ComputerCaseMenu>(menu, inv, title) {

    private var failMessage: Component? = null
    private var failMessageEndMs: Long = 0L

    private lateinit var powerButton: IconButton

    init {
        imageWidth = SlotDef.FAT_IMAGE_WIDTH
        imageHeight = SlotDef.FAT_IMAGE_HEIGHT
    }

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

    /**
     * Restore vanilla's renderBackground behavior: dim overlay + dispatch
     * to our renderBg() so the PCB BG blits. owo's BaseOwoHandledScreen
     * overrides renderBackground to a no-op which silently kills both
     * (and thus our PCB texture, slot hints, sidebar — anything painted
     * inside the renderBg path).
     */
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderTransparentBackground(g)
        renderBg(g, partialTick, mouseX, mouseY)
    }

    private fun buildPanel(): FlowLayout =
        verticalFlow(Sizing.fixed(SlotDef.FAT_IMAGE_WIDTH), Sizing.fixed(SlotDef.FAT_IMAGE_HEIGHT)).apply {
            surface(Surface.BLANK)

            // Component slots: index 0 = motherboard slot on the case BE,
            // 1..14 = motherboard inventory slots. SlotDef.COMPUTER_CASE
            // gives screen-space coords for all 15.
            for (i in SlotDef.COMPUTER_CASE.indices) {
                val def = SlotDef.COMPUTER_CASE[i]
                child(slotAsComponent(i)
                    .positioning(Positioning.absolute(def.x, def.y)))
            }

            // Player inventory + hotbar at the standard fat-shape coords.
            val firstInv = SlotDef.COMPUTER_CASE.size
            for (row in 0 until 3) {
                for (col in 0 until 9) {
                    val px = 8 + col * 18
                    val py = SlotDef.FAT_PLAYER_INV_Y + row * 18
                    child(slotAsComponent(firstInv + row * 9 + col)
                        .positioning(Positioning.absolute(px, py)))
                }
            }
            val firstHotbar = firstInv + 27
            for (col in 0 until 9) {
                val px = 8 + col * 18
                child(slotAsComponent(firstHotbar + col)
                    .positioning(Positioning.absolute(px, SlotDef.FAT_HOTBAR_Y)))
            }
        }

    override fun init() {
        super.init()

        // Sidebar power button — same coords as before, added through
        // addRenderableWidget so it stays a vanilla widget. (Would need a
        // custom owo SurfaceComponent + absolute screen-space positioning
        // to embed it in the owo tree, no real benefit over the blit.)
        val sideX = leftPos - SIDEBAR_WIDTH
        val sideY = topPos + SIDEBAR_TOP
        val btnX = sideX + 4
        val btnY = sideY + 4

        powerButton = IconButton(
            btnX, btnY, 12, 12,
            POWER_BUTTON_TEX, 42, 14,
            15, 1,    // idle (off)
             1, 1,    // hover (off)
            29, 1,    // pressed
            Component.translatable("button.scev.power"),
            ::onPowerClicked,
        ).withToggle(
            { menu.isMachinePowered() },
             1, 1,    // idle (on)
             1, 1,    // hover (on)
        )
        powerButton.tooltip = Tooltip.create(Component.translatable("tooltip.scev.power"))
        addRenderableWidget(powerButton)
    }

    private fun onPowerClicked(btn: IconButton) {
        if (menu.isMachinePowered()) {
            PacketDistributor.sendToServer(MachineResetPayload(false))
            return
        }

        val r = menu.validateForPower()
        if (r == ComputerCaseMenu.ValidationResult.OK) {
            PacketDistributor.sendToServer(MachineResetPayload(false))
            return
        }

        btn.flashFail(500)
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.8F))
        failMessage = Component.translatable(r.langKey()).withStyle(ChatFormatting.RED)
        failMessageEndMs = System.currentTimeMillis() + FAIL_MESSAGE_DURATION_MS
    }

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)

        // Sidebar backdrop first so the power button widget renders on top.
        g.blit(SIDEBAR_TEX, leftPos - SIDEBAR_WIDTH, topPos + SIDEBAR_TOP,
            0f, 0f, SIDEBAR_WIDTH, SIDEBAR_HEIGHT, SIDEBAR_WIDTH, SIDEBAR_HEIGHT)

        val mbItem = menu.caseBE.getItem(0).item as? MotherboardItem
        val bg = when (mbItem?.level ?: 0) {
            1 -> BG_MB_T1
            2 -> BG_MB_T2
            3 -> BG_MB_T3
            else -> BG_EMPTY
        }
        g.blit(bg, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)

        renderSlotHints(g, mbItem)
    }

    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Title rendered into the BG art's title strip; suppress the
        // inherited "Inventory" label.
        g.drawString(font, title, 8, 6, 0x404040, false)
    }

    private fun renderSlotHints(g: GuiGraphics, mbItem: MotherboardItem?) {
        val defs = SlotDef.COMPUTER_CASE
        for (i in defs.indices) {
            val def = defs[i]
            val hintKey = def.background ?: continue
            val sprite = SlotHints.spriteFor(hintKey) ?: continue

            val slot = menu.slots[i]
            if (slot.hasItem()) continue

            // Motherboard slot (index 0) always renders its hint when empty.
            // Component slots (1..14) only when a motherboard is seated AND
            // their underlying motherboard slot is enabled for that tier.
            if (def.index != 0) {
                if (mbItem == null) continue
                if (!mbItem.isSlotEnabled(def.index - 1)) continue
            }
            SlotHints.draw(g, sprite, leftPos + slot.x, topPos + slot.y)
        }
    }

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
        val y = topPos - (9 + padY * 2) - 6

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
        private val BG_EMPTY: ResourceLocation = ScalarEvolution.rl("textures/gui/computer_case_empty.png")
        private val BG_MB_T1: ResourceLocation = ScalarEvolution.rl("textures/gui/computer_case_motherboard1.png")
        private val BG_MB_T2: ResourceLocation = ScalarEvolution.rl("textures/gui/computer_case_motherboard2.png")
        private val BG_MB_T3: ResourceLocation = ScalarEvolution.rl("textures/gui/computer_case_motherboard3.png")

        private val SIDEBAR_TEX: ResourceLocation = ScalarEvolution.rl("textures/gui/widget/sidebar_1.png")
        private val POWER_BUTTON_TEX: ResourceLocation = ScalarEvolution.rl("textures/gui/widget/power_button.png")

        private const val SIDEBAR_WIDTH: Int = 19
        private const val SIDEBAR_HEIGHT: Int = 20
        private const val SIDEBAR_TOP: Int = 8

        private const val FAIL_MESSAGE_DURATION_MS: Long = 2500L
    }
}
