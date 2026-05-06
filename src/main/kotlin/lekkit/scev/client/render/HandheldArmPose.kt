/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render

import net.minecraft.client.model.HumanoidModel
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import net.neoforged.fml.common.asm.enumextension.EnumProxy
import net.neoforged.neoforge.client.IArmPoseTransformer

/**
 * Custom `HumanoidModel.ArmPose` for handheld computer items — third-person
 * "raised both hands holding a tablet/Game Boy" pose.
 *
 * **Why an EnumProxy.** Vanilla `ArmPose` is an enum; you can't extend an
 * enum at compile time. NeoForge patches `ArmPose` to implement
 * `IExtensibleEnum` and adds a constructor `(boolean, IArmPoseTransformer)`,
 * but new entries are added at runtime by the FML enum extender — driven
 * from `META-INF/enum_extensions.json` (path declared in `neoforge.mods.toml`).
 * The JSON references [PROXY] via `parameters: { class, field }` to feed
 * the constructor; after extension, [PROXY.getValue] returns the actual
 * enum entry that vanilla's switch in `HumanoidModel.poseRightArm`/
 * `poseLeftArm` will fall through (default branch added by NeoForge), at
 * which point [TRANSFORMER] is invoked once per arm.
 *
 * **Why both arms get the same transformer call.** NeoForge's patch
 * invokes `armPose.applyTransform(model, entity, arm)` *after* the
 * vanilla switch, separately for each arm — so [TRANSFORMER] is called
 * twice per pose tick, once with `HumanoidArm.RIGHT` and once with
 * `HumanoidArm.LEFT`. Branch on the [arm] parameter to pose each side.
 *
 * **Pose math.** Both arms lift forward to ~face level, palms inward, with
 * a slight head-pitch follow so the device tracks where the player's
 * looking. Numbers tuned to read as "holding a tablet up to read it"
 * without overlapping the player's head model from the side.
 */
object HandheldArmPose {

    /**
     * Per-arm rotation closure invoked by NeoForge's patched
     * `HumanoidModel.ArmPose.applyTransform` for the SCEV_HANDHELD entry.
     */
    @JvmField
    val TRANSFORMER: IArmPoseTransformer = IArmPoseTransformer { model, _, arm ->
        val armPart = if (arm == HumanoidArm.RIGHT) model.rightArm else model.leftArm
        val side = if (arm == HumanoidArm.RIGHT) 1.0f else -1.0f
        // Forward + slight pitch follow (clamped so extreme look-up/down
        // doesn't snap the arm through the player's body).
        armPart.xRot = -1.4f + Mth.clamp(model.head.xRot, -0.5f, 0.5f)
        // Slight inward yaw + half-strength head-yaw follow.
        armPart.yRot = side * 0.2f + model.head.yRot * 0.5f
        // Roll palms inward so the two hands meet in front.
        armPart.zRot = side * -0.2f
    }

    /**
     * Carrier for the constructor arguments NeoForge feeds to the new
     * `ArmPose` entry: `twoHanded = true`, transformer = [TRANSFORMER].
     * Referenced from `META-INF/enum_extensions.json`'s `parameters` block.
     * Post-extension, [getValue] returns the registered enum entry.
     */
    @JvmField
    val PROXY: EnumProxy<HumanoidModel.ArmPose> = EnumProxy(
        HumanoidModel.ArmPose::class.java,
        true,           // twoHanded
        TRANSFORMER,    // applyTransform target
    )

    /** The runtime-registered ArmPose enum value. Safe to call after FML enum extension runs. */
    val pose: HumanoidModel.ArmPose get() = PROXY.value
}
