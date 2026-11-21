package com.example.gpumodels;

import org.joml.Vector3f;

/** Everything a script has asked one bone to do. Values are in the player frame (x = left, y = up, z = forward). */
public final class BoneControl {
    public final String name;
    public final int node;

    /** The vanilla part this bone copies, or null. */
    public VanillaPart follow;
    public boolean followRotation = true;
    public boolean followPosition = true;

    /** Extra rotation in degrees, added on top of everything else. */
    public final Vector3f rotateDeg = new Vector3f();
    /** Extra movement in blocks. */
    public final Vector3f move = new Vector3f();
    public final Vector3f scale = new Vector3f(1f, 1f, 1f);

    /** Jiggle physics for this bone, or null. */
    public SpringBone spring;

    public BoneControl(String name, int node) {
        this.name = name;
        this.node = node;
    }

    public boolean isActive() {
        return follow != null
                || spring != null
                || rotateDeg.x != 0f || rotateDeg.y != 0f || rotateDeg.z != 0f
                || move.x != 0f || move.y != 0f || move.z != 0f
                || scale.x != 1f || scale.y != 1f || scale.z != 1f;
    }

    public void reset() {
        follow = null;
        followRotation = true;
        followPosition = true;
        rotateDeg.zero();
        move.zero();
        scale.set(1f, 1f, 1f);
        spring = null;
    }
}
