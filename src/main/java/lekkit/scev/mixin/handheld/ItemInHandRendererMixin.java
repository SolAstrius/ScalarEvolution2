/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.mixin.handheld;

import com.mojang.blaze3d.vertex.PoseStack;
import lekkit.scev.client.render.item.HandheldFirstPersonRenderer;
import lekkit.scev.client.render.item.PrintoutFirstPersonRenderer;
import lekkit.scev.items.IHandheldComputer;
import lekkit.scev.items.PrintoutItem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "two-handed (or one-handed) raised pose" first-person render
 * branch for items implementing {@link IHandheldComputer}, mirroring
 * vanilla's hardcoded {@code Items.FILLED_MAP} branch in
 * {@link ItemInHandRenderer#renderArmWithItem}.
 *
 * <p>Vanilla map rendering is item-identity-checked (no extension point),
 * so a Mixin {@code @Inject(at = HEAD, cancellable = true)} is the
 * cleanest way to interpose. We dispatch to
 * {@link HandheldFirstPersonRenderer} which mirrors vanilla's two-handed
 * /one-handed map math but draws the chassis BakedModel + framebuffer
 * overlay instead of the map quad.
 *
 * <p><b>Why before the FILLED_MAP check.</b> A handheld is never going
 * to also be FILLED_MAP, so injection ordering is irrelevant for
 * correctness — but injecting at HEAD lets us skip every later branch
 * (FILLED_MAP, CROSSBOW, BOW, SHIELD, brush, eat, etc.) by cancelling
 * the callback, which is what we want: handhelds use *only* the raised
 * pose and never the use-animations.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    /** The local player's offhand stack, captured by vanilla each render. */
    @Shadow
    @Final
    private ItemStack offHandItem;

    @Inject(
        method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void scev$renderHandheld(
        AbstractClientPlayer player,
        float partialTick,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight,
        CallbackInfo ci
    ) {
        if (player.isScoping()) return;

        boolean isMain = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = isMain ? player.getMainArm() : player.getMainArm().getOpposite();
        boolean twoHanded = isMain && this.offHandItem.isEmpty();

        if (stack.getItem() instanceof IHandheldComputer) {
            poseStack.pushPose();
            if (twoHanded) {
                HandheldFirstPersonRenderer.renderTwoHanded(
                    stack, pitch, swingProgress, equipProgress,
                    poseStack, buffers, packedLight
                );
            } else {
                HandheldFirstPersonRenderer.renderOneHanded(
                    stack, equipProgress, arm, swingProgress,
                    poseStack, buffers, packedLight
                );
            }
            poseStack.popPose();
            ci.cancel();
        } else if (stack.getItem() instanceof PrintoutItem) {
            // Printouts get the same map-like raised pose. Distinct
            // dispatch from handhelds so each renderer can evolve
            // independently — chassis vs. flat-page rendering is
            // different enough downstream that a shared draw-callback
            // would be more complexity than it saves.
            poseStack.pushPose();
            if (twoHanded) {
                PrintoutFirstPersonRenderer.renderTwoHanded(
                    stack, pitch, swingProgress, equipProgress,
                    poseStack, buffers, packedLight
                );
            } else {
                PrintoutFirstPersonRenderer.renderOneHanded(
                    stack, equipProgress, arm, swingProgress,
                    poseStack, buffers, packedLight
                );
            }
            poseStack.popPose();
            ci.cancel();
        }
    }
}
