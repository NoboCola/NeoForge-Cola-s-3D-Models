package com.example.gpumodels;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How far each vanilla body part has moved away from its resting position this frame.
 *
 * The values are given in the "player frame" that scripts use:
 *   x = to the player's left, y = up, z = forward (in blocks), and rotations around those axes.
 * They are measured against the resting pose, so a standing player gives "no movement".
 *
 * Minecraft's own model space has y pointing down and the model looking toward -z, so the vanilla numbers are
 * converted here. Rotation:  Rz(-zRot) * Ry(-yRot) * Rx(xRot).  Position: (dx, -dy, -dz) / 16.
 */
public final class VanillaPose {
    private static final int PARTS = VanillaPart.values().length;
    private static final Quaternionf[] ROTATION = new Quaternionf[PARTS];
    private static final Vector3f[] OFFSET = new Vector3f[PARTS];
    private static boolean valid;

    static {
        for (int i = 0; i < PARTS; i++) {
            ROTATION[i] = new Quaternionf();
            OFFSET[i] = new Vector3f();
        }
    }

    private VanillaPose() {}

    /** False until the vanilla model has been captured at least once (it only is in third person). */
    public static boolean isValid() {
        return valid;
    }

    public static Quaternionf rotation(VanillaPart part) {
        return ROTATION[part.ordinal()];
    }

    public static Vector3f offset(VanillaPart part) {
        return OFFSET[part.ordinal()];
    }

    /** Runs the vanilla animation code for the player and stores the result. Called from the render thread. */
    public static void capture(PlayerRenderer renderer, AbstractClientPlayer player, float partialTick) {
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        ModelPart[] parts = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};

        // remember the resting values of every part
        float[][] rest = new float[PARTS][6];
        for (int i = 0; i < PARTS; i++) {
            ModelPart p = parts[i];
            p.resetPose();
            rest[i][0] = p.x;
            rest[i][1] = p.y;
            rest[i][2] = p.z;
            rest[i][3] = p.xRot;
            rest[i][4] = p.yRot;
            rest[i][5] = p.zRot;
        }

        // the same numbers vanilla gives the model every frame
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
        float netHeadYaw = headYaw - bodyYaw;
        float headPitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
        float ageInTicks = player.tickCount + partialTick;
        float limbSpeed = Math.min(player.walkAnimation.speed(partialTick), 1.0F);
        float limbPos = player.walkAnimation.position(partialTick);

        model.attackTime = player.getAttackAnim(partialTick);
        model.crouching = player.isCrouching();
        model.riding = player.isPassenger();
        model.prepareMobModel(player, limbPos, limbSpeed, partialTick);
        model.setupAnim(player, limbPos, limbSpeed, ageInTicks, netHeadYaw, headPitch);

        for (int i = 0; i < PARTS; i++) {
            ModelPart p = parts[i];
            float dx = p.x - rest[i][0];
            float dy = p.y - rest[i][1];
            float dz = p.z - rest[i][2];
            float rx = p.xRot - rest[i][3];
            float ry = p.yRot - rest[i][4];
            float rz = p.zRot - rest[i][5];
            ROTATION[i].identity().rotateZ(-rz).rotateY(-ry).rotateX(rx);
            OFFSET[i].set(dx / 16f, -dy / 16f, -dz / 16f);
        }
        valid = true;
    }
}
