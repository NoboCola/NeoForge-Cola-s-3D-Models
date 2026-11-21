package com.example.gpumodels;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Jiggle physics for one bone.
 *
 * The bone's tip is a point on a unit sphere around the bone's pivot. Every frame it is pushed by:
 *  - a spring that pulls it back to where the bone would point without any jiggle,
 *  - gravity (world down),
 *  - inertia: the opposite of how the player accelerates (and the opposite of body turning).
 * The result is turned into a rotation around the bone's pivot, applied on top of whatever the script and
 * the animation already do (see Skeleton.pose). Children of the bone follow, so chains of spring bones work.
 *
 * Everything is simulated in "model space" (the space the vertices live in), where up is still world up.
 */
public final class SpringBone {
    // ---- settings, changed from Lua with bone(name):spring({...}) ----
    /** 0 = floppy, 1 = rigid. How fast the bone returns to its normal direction. */
    public float stiffness = 0.15f;
    /** 0 = bounces for a long time, 1 = settles almost at once. */
    public float damping = 0.85f;
    /** 0 = ignores gravity. 1 = a bone that points sideways sags about 30 degrees. */
    public float gravity = 1.0f;
    /** 0 = ignores player movement. 1 = normal. 2 = twice as strong. */
    public float inertia = 1.0f;
    /** The bone never swings further than this many degrees from its normal direction. */
    public float limitDeg = 45f;

    private final Vector3f tipOffset = new Vector3f(); // from the bone's pivot to its tip, in the bone's own space

    // ---- state ----
    private final Vector3f dir = new Vector3f();     // current tip direction (unit vector, model space)
    private final Vector3f vel = new Vector3f();
    private final Vector3f actual = new Vector3f();
    private final Vector3f target = new Vector3f();
    private final Vector3f acc = new Vector3f();
    private final Vector3f perp = new Vector3f();
    private final Quaternionf q = new Quaternionf();
    private boolean started;
    private long lastFrame = -1;

    public SpringBone(Vector3f tipOffset) {
        this.tipOffset.set(tipOffset);
    }

    public Vector3f tipOffset() {
        return tipOffset;
    }

    /**
     * Called by Skeleton.pose.
     * tipDirection = vector from the bone's pivot to its tip in the current pose (model space).
     * rotation = the rotation the script already applies to the bone. On return it also contains the jiggle.
     */
    void solve(Vector3f tipDirection, Quaternionf rotation) {
        if (tipDirection.lengthSquared() < 1e-12f) return;
        actual.set(tipDirection).normalize();
        rotation.transform(actual, target); // where the bone points without any jiggle

        if (!started) {
            dir.set(target);
            vel.zero();
            started = true;
            lastFrame = frameId;
        } else if (lastFrame != frameId) {
            lastFrame = frameId;
            simulate();
        }
        // same frame again: keep the current direction

        q.rotationTo(target, dir);
        rotation.premul(q);
    }

    private void simulate() {
        float dt = frameDt;
        int steps = Math.max(1, Math.min(8, (int) Math.ceil(dt / (1f / 120f))));
        float h = dt / steps;

        float ks = Mth.clamp(stiffness, 0f, 1f) * 400f;
        float c = Mth.clamp(damping, 0f, 1f) * 12f;
        float g = Math.max(0f, gravity) * (8f + 0.5f * ks);
        float in = Math.max(0f, inertia);

        // body turning: the tip stays where it is in the world, so inside the turning model it swings the other way
        dir.rotateY(yawDeltaRad);
        vel.rotateY(yawDeltaRad);

        for (int s = 0; s < steps; s++) {
            acc.set(target).sub(dir).mul(ks);   // spring back to the normal direction
            acc.add(0f, -g, 0f);                // gravity
            acc.fma(-in, accel);                // inertia
            acc.fma(-acc.dot(dir), dir);        // only sideways pushes matter, the length of the bone is fixed

            vel.fma(h, acc);
            vel.mul((float) Math.exp(-c * h));
            dir.fma(h, vel);
            if (dir.lengthSquared() < 1e-12f) dir.set(target);
            dir.normalize();
            vel.fma(-vel.dot(dir), dir);

            limit();
        }
    }

    /** Keeps the tip inside a cone of limitDeg degrees around its normal direction. */
    private void limit() {
        if (limitDeg >= 179f) return;
        float max = (float) Math.toRadians(Math.max(0f, limitDeg));
        float cos = Mth.clamp(target.dot(dir), -1f, 1f);
        float angle = (float) Math.acos(cos);
        if (angle <= max) return;

        perp.set(dir).fma(-cos, target);
        if (perp.lengthSquared() < 1e-10f) return;
        perp.normalize();
        dir.set(target).mul((float) Math.cos(max)).fma((float) Math.sin(max), perp);
        dir.normalize();

        float outward = vel.dot(perp);
        if (outward > 0f) vel.fma(-outward, perp);
    }

    // ======================================================================================
    // player motion, shared by every spring bone (updated once per frame by Scripting.update)
    // ======================================================================================

    private static long frameId;
    private static float frameDt = 1f / 60f;
    private static final Vector3f accel = new Vector3f(); // player acceleration in blocks/s^2, in model space
    private static float yawDeltaRad;

    private static boolean hasLast;
    private static long lastNanos;
    private static double lastX, lastY, lastZ;
    private static float lastYaw;
    private static float smoothVx, smoothVy, smoothVz;

    /** Measures how the player moved since the last frame. yawOffsetDeg is the model's yaw command offset. */
    public static void updateMotion(LocalPlayer player, float partialTick, float yawOffsetDeg) {
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        Vec3 p = player.getPosition(partialTick);
        long now = System.nanoTime();
        float dt = (now - lastNanos) / 1.0e9f;

        double dx = p.x - lastX, dy = p.y - lastY, dz = p.z - lastZ;
        boolean jump = dx * dx + dy * dy + dz * dz > 64.0; // teleport
        if (!hasLast || dt > 0.25f || jump) {
            hasLast = true;
            lastNanos = now;
            lastX = p.x;
            lastY = p.y;
            lastZ = p.z;
            lastYaw = bodyYaw;
            smoothVx = smoothVy = smoothVz = 0f;
            accel.zero();
            yawDeltaRad = 0f;
            frameDt = 1f / 60f;
            frameId++;
            return;
        }
        if (dt < 0.002f) return; // faster than 500 FPS: wait for more time to pass

        float vx = (float) (dx / dt), vy = (float) (dy / dt), vz = (float) (dz / dt);
        float k = 1f - (float) Math.exp(-dt * 25f); // smooth the speed a little, differentiating is noisy
        float nvx = smoothVx + (vx - smoothVx) * k;
        float nvy = smoothVy + (vy - smoothVy) * k;
        float nvz = smoothVz + (vz - smoothVz) * k;

        accel.set((nvx - smoothVx) / dt, (nvy - smoothVy) / dt, (nvz - smoothVz) / dt);
        float len = accel.length();
        if (len > 80f) accel.mul(80f / len);
        // world -> model space: the model is drawn rotated by (-bodyYaw + yawOffset) around Y
        accel.rotateY((float) Math.toRadians(bodyYaw - yawOffsetDeg));

        yawDeltaRad = (float) Math.toRadians(Mth.wrapDegrees(bodyYaw - lastYaw));

        smoothVx = nvx;
        smoothVy = nvy;
        smoothVz = nvz;
        lastNanos = now;
        lastX = p.x;
        lastY = p.y;
        lastZ = p.z;
        lastYaw = bodyYaw;
        frameDt = Math.min(dt, 0.05f);
        frameId++;
    }
}
