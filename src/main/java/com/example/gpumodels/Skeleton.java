package com.example.gpumodels;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Node hierarchy + bones + animation clips, and the per-frame bone palette.
 *
 * The CPU only does this small job each frame: sample the animation for every node, walk the
 * hierarchy, and produce one 4x4 matrix per bone (a few dozen matrices). The expensive part,
 * moving every vertex, happens in the vertex shader on the GPU.
 *
 * palette[b] = globalInverse * globalTransform(boneNode[b]) * inverseBind[b]
 */
public final class Skeleton {
    /** Must match MAX_BONES in the vertex shader. */
    public static final int MAX_BONES = 128;

    public record Clip(String name, double durationTicks, double ticksPerSecond, Channel[] channelByNode) {}

    private static final class Scratch {
        final Vector3f t = new Vector3f();
        final Vector3f s = new Vector3f();
        final Quaternionf q = new Quaternionf();
        final Quaternionf q2 = new Quaternionf();
    }

    /** Keyframes of one node. Empty arrays mean "keep the rest pose value". */
    public static final class Channel {
        public double[] posTimes = new double[0];
        public float[] pos = new float[0];   // x,y,z per key
        public double[] rotTimes = new double[0];
        public float[] rot = new float[0];   // x,y,z,w per key
        public double[] sclTimes = new double[0];
        public float[] scl = new float[0];   // x,y,z per key

        void sample(double ticks, Matrix4f rest, Matrix4f dest, Scratch sc) {
            if (posTimes.length > 0) sampleVec(posTimes, pos, ticks, sc.t); else rest.getTranslation(sc.t);
            if (rotTimes.length > 0) sampleQuat(rotTimes, rot, ticks, sc.q, sc.q2); else rest.getNormalizedRotation(sc.q);
            if (sclTimes.length > 0) sampleVec(sclTimes, scl, ticks, sc.s); else rest.getScale(sc.s);
            dest.translationRotateScale(sc.t.x, sc.t.y, sc.t.z, sc.q.x, sc.q.y, sc.q.z, sc.q.w,
                    sc.s.x, sc.s.y, sc.s.z);
        }
    }

    /**
     * Extra movement a script adds to one node (bone), in the model's own axes. Rotation and scale happen
     * around the bone's own pivot point. Children of the bone follow along.
     */
    public static final class Adjust {
        public final Quaternionf rotation = new Quaternionf();
        public final Vector3f move = new Vector3f();
        public final Vector3f scale = new Vector3f(1f, 1f, 1f);
        public boolean active;
        /** Jiggle physics added on top of the rotation above, or null. */
        public SpringBone spring;
    }

    private final String[] nodeNames;
    private final Matrix4f rootRest;
    private final Adjust[] adjust;
    private final Matrix4f tmpGf = new Matrix4f();
    private final Matrix4f tmpM = new Matrix4f();
    private final Vector3f pivot = new Vector3f();
    private final Vector3f tip = new Vector3f();
    private final Quaternionf springRot = new Quaternionf();
    private final int nodeCount;
    private final int[] parent;
    private final Matrix4f[] restLocal;
    private final Matrix4f globalInverse;
    private final int[] boneNode;
    private final Matrix4f[] boneOffset;
    private final List<Clip> clips;

    private final Matrix4f[] global;
    private final Matrix4f[] palette;
    private final Matrix4f local = new Matrix4f();
    private final Matrix4f identity = new Matrix4f();
    private final Scratch scratch = new Scratch();

    public Skeleton(String[] nodeNames, int[] parent, Matrix4f[] restLocal, Matrix4f globalInverse,
                    int[] boneNode, Matrix4f[] boneOffset, List<Clip> clips) {
        this.nodeNames = nodeNames;
        this.rootRest = new Matrix4f(globalInverse).invert();
        this.nodeCount = parent.length;
        this.adjust = new Adjust[nodeCount];
        this.parent = parent;
        this.restLocal = restLocal;
        this.globalInverse = globalInverse;
        this.boneNode = boneNode;
        this.boneOffset = boneOffset;
        this.clips = clips;
        this.global = new Matrix4f[nodeCount];
        for (int i = 0; i < nodeCount; i++) global[i] = new Matrix4f();
        this.palette = new Matrix4f[boneNode.length];
        for (int i = 0; i < palette.length; i++) palette[i] = new Matrix4f();
    }

    /** Index of the first node (bone) with this exact name, or -1. */
    public int findNode(String name) {
        for (int i = 0; i < nodeNames.length; i++) {
            if (nodeNames[i].equals(name)) return i;
        }
        return -1;
    }

    public String[] nodeNames() {
        return nodeNames;
    }

    /**
     * The node names a script can use with bone(name), without the internal helper nodes that the FBX importer
     * adds (names containing "$AssimpFbx$"), and without duplicates.
     */
    public List<String> readableNames() {
        List<String> out = new ArrayList<>();
        for (String n : nodeNames) {
            if (n == null || n.isEmpty() || n.contains("$AssimpFbx$") || out.contains(n)) continue;
            out.add(n);
        }
        return out;
    }

    private boolean isHelper(int n) {
        return nodeNames[n] != null && nodeNames[n].contains("$AssimpFbx$");
    }

    private int firstChild(int n) {
        for (int i = 0; i < nodeCount; i++) {
            if (parent[i] == n) return i;
        }
        return -1;
    }

    /**
     * Vector from a bone's pivot to its tip, in the bone's own space (used by spring bones).
     * The tip is the first child bone. A bone without children keeps pointing away from its parent.
     */
    public Vector3f tipOffset(int node) {
        Vector3f out = new Vector3f();
        Matrix4f m = new Matrix4f();
        for (int c = 0; c < nodeCount; c++) {
            if (parent[c] != node) continue;
            m.set(restLocal[c]);
            int cur = c;
            while (isHelper(cur)) { // skip the FBX helper nodes between two real bones
                int next = firstChild(cur);
                if (next < 0) break;
                m.mul(restLocal[next]);
                cur = next;
            }
            m.getTranslation(out);
            if (out.lengthSquared() > 1e-10f) return out;
        }
        if (parent[node] >= 0) {
            Matrix4f inv = new Matrix4f(restLocal[node]).invert();
            restLocal[node].getTranslation(out);
            inv.transformDirection(out);
            if (out.lengthSquared() > 1e-10f) return out;
        }
        return out.set(0f, -1f, 0f);
    }

    /** Model-space transform of a node in the pose computed by the last pose() call. */
    public Matrix4f nodeMatrix(int node, Matrix4f out) {
        return globalInverse.mul(global[node], out);
    }

    /** Bit mask (4 ints = 128 bones) of the bones that sit on this node or on any node below it. */
    public int[] boneMaskBelow(int node) {
        boolean[] inside = new boolean[nodeCount];
        inside[node] = true;
        for (int i = 0; i < nodeCount; i++) { // parents always come before their children
            if (parent[i] >= 0 && inside[parent[i]]) inside[i] = true;
        }
        int[] mask = new int[4];
        for (int b = 0; b < boneNode.length && b < MAX_BONES; b++) {
            int n = boneNode[b];
            if (n >= 0 && inside[n]) mask[b >> 5] |= 1 << (b & 31);
        }
        return mask;
    }

    /** The adjustment slot of a node (created when first asked for). */
    public Adjust adjust(int node) {
        if (adjust[node] == null) adjust[node] = new Adjust();
        return adjust[node];
    }

    /** Removes every script adjustment (call once per frame before applying the new ones). */
    public void clearAdjusts() {
        for (Adjust a : adjust) {
            if (a == null) continue;
            a.active = false;
            a.rotation.identity();
            a.move.zero();
            a.scale.set(1f, 1f, 1f);
            a.spring = null;
        }
    }

    public int boneCount() {
        return boneNode.length;
    }

    public int clipCount() {
        return clips.size();
    }

    public List<String> clipNames() {
        List<String> names = new ArrayList<>();
        for (Clip c : clips) names.add(c.name());
        return names;
    }

    /**
     * Computes the bone palette. clipIndex outside the valid range gives the rest (bind) pose.
     * The returned array is reused by the next call, so do not keep it.
     */
    public Matrix4f[] pose(int clipIndex, double seconds) {
        Clip clip = clipIndex >= 0 && clipIndex < clips.size() ? clips.get(clipIndex) : null;
        double ticks = 0;
        if (clip != null && clip.durationTicks() > 0) {
            ticks = (seconds * clip.ticksPerSecond()) % clip.durationTicks();
        }

        for (int i = 0; i < nodeCount; i++) {
            Channel ch = clip != null ? clip.channelByNode()[i] : null;
            if (ch == null) {
                local.set(restLocal[i]);
            } else {
                ch.sample(ticks, restLocal[i], local, scratch);
            }
            if (parent[i] < 0) {
                global[i].set(local);
            } else {
                global[parent[i]].mul(local, global[i]);
            }

            Adjust a = adjust[i];
            if (a != null && a.active) {
                // work in "model space" (the space the vertices end up in), rotate/scale around the bone's own pivot
                tmpGf.set(globalInverse).mul(global[i]);
                tmpGf.getTranslation(pivot);

                Quaternionf rot = a.rotation;
                if (a.spring != null) {
                    // where does the bone point right now (parents and animation included)? the spring adds jiggle to that
                    tmpGf.transformPosition(a.spring.tipOffset(), tip);
                    tip.sub(pivot);
                    springRot.set(a.rotation);
                    a.spring.solve(tip, springRot);
                    rot = springRot;
                }

                tmpM.identity()
                        .translate(a.move)
                        .translate(pivot)
                        .rotate(rot)
                        .scale(a.scale)
                        .translate(-pivot.x, -pivot.y, -pivot.z);
                tmpM.mul(tmpGf, tmpGf);
                rootRest.mul(tmpGf, global[i]);
            }
        }

        for (int b = 0; b < palette.length; b++) {
            int n = boneNode[b];
            Matrix4f g = n >= 0 ? global[n] : identity;
            globalInverse.mul(g, palette[b]);
            palette[b].mul(boneOffset[b]);
        }
        return palette;
    }

    // ---- keyframe sampling ----

    private static int segment(double[] times, double t) {
        int n = times.length;
        if (n < 2 || t <= times[0]) return 0;
        if (t >= times[n - 1]) return n - 1;
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) lo = mid; else hi = mid;
        }
        return lo;
    }

    private static float fraction(double[] times, int i, double t) {
        double span = times[i + 1] - times[i];
        if (span <= 1e-9) return 0f;
        return (float) Math.min(1.0, Math.max(0.0, (t - times[i]) / span));
    }

    private static void sampleVec(double[] times, float[] vals, double t, Vector3f out) {
        int n = times.length;
        int i = segment(times, t);
        if (n == 1 || i >= n - 1) {
            int k = Math.min(i, n - 1) * 3;
            out.set(vals[k], vals[k + 1], vals[k + 2]);
            return;
        }
        float f = fraction(times, i, t);
        int a = i * 3, b = (i + 1) * 3;
        out.set(vals[a] + (vals[b] - vals[a]) * f,
                vals[a + 1] + (vals[b + 1] - vals[a + 1]) * f,
                vals[a + 2] + (vals[b + 2] - vals[a + 2]) * f);
    }

    private static void sampleQuat(double[] times, float[] vals, double t, Quaternionf out, Quaternionf tmp) {
        int n = times.length;
        int i = segment(times, t);
        if (n == 1 || i >= n - 1) {
            int k = Math.min(i, n - 1) * 4;
            out.set(vals[k], vals[k + 1], vals[k + 2], vals[k + 3]).normalize();
            return;
        }
        float f = fraction(times, i, t);
        int a = i * 4, b = (i + 1) * 4;
        out.set(vals[a], vals[a + 1], vals[a + 2], vals[a + 3]);
        tmp.set(vals[b], vals[b + 1], vals[b + 2], vals[b + 3]);
        out.slerp(tmp, f).normalize();
    }
}
