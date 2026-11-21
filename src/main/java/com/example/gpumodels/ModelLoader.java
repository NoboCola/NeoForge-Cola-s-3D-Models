package com.example.gpumodels;

import com.mojang.logging.LogUtils;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIAnimation;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AINodeAnim;
import org.lwjgl.assimp.AIQuatKey;
import org.lwjgl.assimp.AIQuaternion;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexel;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVectorKey;
import org.lwjgl.assimp.AIVertexWeight;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads any format Assimp understands (FBX, GLB/glTF, OBJ, DAE, ...), including bones, skin weights
 * and animation clips.
 *
 * Per-vertex data sent to the GPU:
 *   vertices     = 8 floats per vertex: position(3), normal(3), uv(2)
 *   boneIds      = 4 ints per vertex
 *   boneWeights  = 4 floats per vertex (sum to 1, or all 0 for unweighted vertices)
 *
 * Meshes without bones get one synthetic bone (their node), so rigid animated parts work too.
 */
public final class ModelLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Where the texture of one material comes from:
     *  - embeddedIndex >= 0 with encoded bytes (PNG/JPG inside the model) or raw rgba bytes,
     *  - file = an image file name the model refers to,
     *  - neither: only the material name is known (it is then looked up by name).
     */
    public record TextureRef(String material, String file, int embeddedIndex, byte[] encoded,
                             byte[] rgba, int width, int height) {
        public String key() {
            if (embeddedIndex >= 0) return "emb:" + embeddedIndex;
            if (file != null) return "file:" + file.toLowerCase(java.util.Locale.ROOT);
            return "mat:" + material;
        }
    }

    /** color = diffuse color of the part's material (r,g,b,a). It is used when the part has no texture image. */
    public record Part(float[] vertices, int[] boneIds, float[] boneWeights, int[] indices, TextureRef texture,
                       float[] color, String name) {}

    /**
     * size = width, height, depth of the rest pose in model units. Nothing is rescaled or recentered:
     * 1 unit in the file (1 meter in Blender) is exactly 1 Minecraft block, and the model's origin
     * is placed at the entity's feet. unitScale is 1, except for FBX files that are clearly in
     * centimeters (see applyUnitGuess), where it is 0.01 and already included in size.
     */
    public record ModelData(List<Part> parts, Skeleton skeleton, float[] size, float unitScale) {}

    private ModelLoader() {}

    public static ModelData load(java.nio.file.Path file) throws IOException {
        int flags = Assimp.aiProcess_Triangulate
                | Assimp.aiProcess_JoinIdenticalVertices
                | Assimp.aiProcess_GenSmoothNormals
                | Assimp.aiProcess_SortByPType
                | Assimp.aiProcess_LimitBoneWeights; // max 4 bones per vertex

        AIScene scene = Assimp.aiImportFile(file.toAbsolutePath().toString(), flags);
        if (scene == null || scene.mRootNode() == null) {
            throw new IOException("Assimp could not read " + file.getFileName() + ": " + Assimp.aiGetErrorString());
        }
        try {
            return applyUnitGuess(file, convert(scene));
        } finally {
            Assimp.aiReleaseImport(scene); // everything needed has been copied into Java objects
        }
    }

    /**
     * FBX has no reliable unit: Blender can write meters or centimeters depending on export settings.
     * A model that measures more than 20 units is almost certainly in centimeters, so it is scaled by 0.01.
     * GLB/glTF is always in meters and is never touched. If the guess is wrong for a model,
     * /gpumodel localplayer model scale <factor> corrects it.
     */
    private static ModelData applyUnitGuess(java.nio.file.Path file, ModelData data) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        float[] size = data.size();
        float max = Math.max(size[0], Math.max(size[1], size[2]));
        if (name.endsWith(".fbx") && max > 20f) {
            LOGGER.info("[gpumodels] this FBX measures {} units across, which looks like centimeters: scaling by 0.01",
                    String.format(java.util.Locale.ROOT, "%.1f", max));
            return new ModelData(data.parts(), data.skeleton(),
                    new float[]{size[0] * 0.01f, size[1] * 0.01f, size[2] * 0.01f}, 0.01f);
        }
        return data;
    }

    // ------------------------------------------------------------------------------------------

    private static final class NodeList {
        final List<String> names = new ArrayList<>();
        final List<Integer> parents = new ArrayList<>();
        final List<Matrix4f> locals = new ArrayList<>();
        final List<int[]> meshes = new ArrayList<>();
    }

    private static void walk(AINode node, int parent, NodeList out) {
        int index = out.names.size();
        out.names.add(node.mName().dataString());
        out.parents.add(parent);
        out.locals.add(toMatrix(node.mTransformation()));
        int[] meshIds = new int[node.mNumMeshes()];
        IntBuffer mb = node.mMeshes();
        for (int i = 0; i < meshIds.length; i++) meshIds[i] = mb.get(i);
        out.meshes.add(meshIds);

        PointerBuffer children = node.mChildren();
        for (int i = 0; i < node.mNumChildren(); i++) {
            walk(AINode.create(children.get(i)), index, out);
        }
    }

    private static Matrix4f toMatrix(AIMatrix4x4 m) {
        // Assimp is row-major, JOML takes column-major constructor arguments.
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4());
    }

    private static ModelData convert(AIScene scene) {
        NodeList nl = new NodeList();
        walk(scene.mRootNode(), -1, nl);
        int nodeCount = nl.names.size();

        Map<String, Integer> nodeByName = new HashMap<>();
        for (int i = 0; i < nodeCount; i++) nodeByName.putIfAbsent(nl.names.get(i), i);

        // ---- bones and parts ----
        List<Integer> boneNode = new ArrayList<>();
        List<Matrix4f> boneOffset = new ArrayList<>();
        Map<String, Integer> boneByKey = new HashMap<>();
        Map<Integer, TextureRef> textureByMaterial = new HashMap<>();
        Map<Integer, float[]> colorByMaterial = new HashMap<>();
        List<Part> parts = new ArrayList<>();

        int meshCount = scene.mNumMeshes();
        PointerBuffer meshes = scene.mMeshes();
        PointerBuffer materials = scene.mMaterials();
        boolean[] skinnedDone = new boolean[meshCount];

        for (int n = 0; n < nodeCount; n++) {
            for (int meshIndex : nl.meshes.get(n)) {
                AIMesh mesh = AIMesh.create(meshes.get(meshIndex));
                if (mesh.mNumVertices() == 0) continue;

                int[] indices = readIndices(mesh);
                if (indices.length == 0) continue;
                float[] vertices = readVertices(mesh);
                int vertexCount = vertices.length / 8;

                TextureRef tex = textureByMaterial.computeIfAbsent(mesh.mMaterialIndex(), mi ->
                        mi >= 0 && materials != null && mi < scene.mNumMaterials()
                                ? textureOf(scene, AIMaterial.create(materials.get(mi)), mi)
                                : null);

                int[] ids = new int[vertexCount * 4];
                float[] weights = new float[vertexCount * 4];

                if (mesh.mNumBones() > 0) {
                    if (skinnedDone[meshIndex]) continue; // skinned meshes ignore their node transform
                    skinnedDone[meshIndex] = true;
                    readSkin(mesh, ids, weights, nodeByName, boneByKey, boneNode, boneOffset);
                } else {
                    // rigid mesh: follow its node through one synthetic bone
                    int bone = boneFor("node:" + n, n, new Matrix4f(), boneByKey, boneNode, boneOffset);
                    for (int v = 0; v < vertexCount; v++) {
                        ids[v * 4] = bone;
                        weights[v * 4] = 1f;
                    }
                }
                float[] color = colorByMaterial.computeIfAbsent(mesh.mMaterialIndex(), mi ->
                        mi >= 0 && materials != null && mi < scene.mNumMaterials()
                                ? colorOf(AIMaterial.create(materials.get(mi)), mi)
                                : new float[]{0.75f, 0.75f, 0.75f, 1f});
                parts.add(new Part(vertices, ids, weights, indices, tex, color, nl.names.get(n)));
            }
        }

        // ---- animation clips ----
        List<Skeleton.Clip> clips = readClips(scene, nodeCount, nodeByName);

        // ---- skeleton ----
        int[] parentArr = new int[nodeCount];
        Matrix4f[] localArr = new Matrix4f[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            parentArr[i] = nl.parents.get(i);
            localArr[i] = nl.locals.get(i);
        }
        int[] boneNodeArr = new int[boneNode.size()];
        for (int i = 0; i < boneNodeArr.length; i++) boneNodeArr[i] = boneNode.get(i);
        Skeleton skeleton = new Skeleton(nl.names.toArray(new String[0]), parentArr, localArr,
                new Matrix4f(localArr[0]).invert(),
                boneNodeArr, boneOffset.toArray(new Matrix4f[0]), clips);

        return new ModelData(parts, skeleton, computeSize(parts, skeleton), 1f);
    }

    private static int boneFor(String key, int node, Matrix4f offset, Map<String, Integer> byKey,
                               List<Integer> boneNode, List<Matrix4f> boneOffset) {
        Integer existing = byKey.get(key);
        if (existing != null) return existing;
        if (boneNode.size() >= Skeleton.MAX_BONES) {
            LOGGER.warn("[gpumodels] more than {} bones, extra bones are merged into the last one", Skeleton.MAX_BONES);
            return Skeleton.MAX_BONES - 1;
        }
        int id = boneNode.size();
        boneNode.add(node);
        boneOffset.add(offset);
        byKey.put(key, id);
        return id;
    }

    private static void readSkin(AIMesh mesh, int[] ids, float[] weights, Map<String, Integer> nodeByName,
                                 Map<String, Integer> boneByKey, List<Integer> boneNode, List<Matrix4f> boneOffset) {
        int vertexCount = ids.length / 4;
        int[] counts = new int[vertexCount];
        PointerBuffer bones = mesh.mBones();
        for (int b = 0; b < mesh.mNumBones(); b++) {
            AIBone bone = AIBone.create(bones.get(b));
            String name = bone.mName().dataString();
            Matrix4f offset = toMatrix(bone.mOffsetMatrix());
            float[] raw = new float[16];
            offset.get(raw);
            Integer node = nodeByName.get(name);
            if (node == null) LOGGER.warn("[gpumodels] bone '{}' has no matching node, it will not move", name);
            int boneId = boneFor(name + Arrays.toString(raw), node != null ? node : -1, offset,
                    boneByKey, boneNode, boneOffset);

            AIVertexWeight.Buffer ws = bone.mWeights();
            for (int k = 0; k < bone.mNumWeights(); k++) {
                AIVertexWeight w = ws.get(k);
                int v = w.mVertexId();
                if (v < 0 || v >= vertexCount || counts[v] >= 4) continue;
                ids[v * 4 + counts[v]] = boneId;
                weights[v * 4 + counts[v]] = w.mWeight();
                counts[v]++;
            }
        }
        // normalize weights so they sum to 1
        for (int v = 0; v < vertexCount; v++) {
            float sum = weights[v * 4] + weights[v * 4 + 1] + weights[v * 4 + 2] + weights[v * 4 + 3];
            if (sum > 1e-6f) {
                for (int k = 0; k < 4; k++) weights[v * 4 + k] /= sum;
            }
        }
    }

    private static float[] readVertices(AIMesh mesh) {
        int n = mesh.mNumVertices();
        AIVector3D.Buffer positions = mesh.mVertices();
        AIVector3D.Buffer normals = mesh.mNormals();          // may be null
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);        // may be null
        float[] v = new float[n * 8];
        for (int i = 0; i < n; i++) {
            AIVector3D p = positions.get(i);
            int o = i * 8;
            v[o] = p.x();
            v[o + 1] = p.y();
            v[o + 2] = p.z();
            if (normals != null) {
                AIVector3D nn = normals.get(i);
                v[o + 3] = nn.x();
                v[o + 4] = nn.y();
                v[o + 5] = nn.z();
            } else {
                v[o + 4] = 1f;
            }
            if (uvs != null) {
                AIVector3D t = uvs.get(i);
                v[o + 6] = t.x();
                v[o + 7] = 1f - t.y(); // Assimp UVs start bottom-left, images are loaded top-down
            }
        }
        return v;
    }

    private static int[] readIndices(AIMesh mesh) {
        int faceCount = mesh.mNumFaces();
        AIFace.Buffer faces = mesh.mFaces();
        int[] idx = new int[faceCount * 3];
        int k = 0;
        for (int f = 0; f < faceCount; f++) {
            AIFace face = faces.get(f);
            if (face.mNumIndices() != 3) continue; // skip points and lines
            IntBuffer ids = face.mIndices();
            idx[k++] = ids.get(0);
            idx[k++] = ids.get(1);
            idx[k++] = ids.get(2);
        }
        return k == idx.length ? idx : Arrays.copyOf(idx, k);
    }

    private static List<Skeleton.Clip> readClips(AIScene scene, int nodeCount, Map<String, Integer> nodeByName) {
        List<Skeleton.Clip> clips = new ArrayList<>();
        int animCount = scene.mNumAnimations();
        if (animCount == 0) return clips;
        PointerBuffer animations = scene.mAnimations();

        for (int a = 0; a < animCount; a++) {
            AIAnimation anim = AIAnimation.create(animations.get(a));
            Skeleton.Channel[] byNode = new Skeleton.Channel[nodeCount];
            double maxTime = 0;

            PointerBuffer channels = anim.mChannels();
            for (int c = 0; channels != null && c < anim.mNumChannels(); c++) {
                AINodeAnim na = AINodeAnim.create(channels.get(c));
                Integer ni = nodeByName.get(na.mNodeName().dataString());
                if (ni == null) continue;
                Skeleton.Channel ch = new Skeleton.Channel();

                int np = na.mNumPositionKeys();
                if (np > 0) {
                    AIVectorKey.Buffer keys = na.mPositionKeys();
                    ch.posTimes = new double[np];
                    ch.pos = new float[np * 3];
                    for (int i = 0; i < np; i++) {
                        AIVectorKey key = keys.get(i);
                        AIVector3D v = key.mValue();
                        ch.posTimes[i] = key.mTime();
                        ch.pos[i * 3] = v.x();
                        ch.pos[i * 3 + 1] = v.y();
                        ch.pos[i * 3 + 2] = v.z();
                    }
                    maxTime = Math.max(maxTime, ch.posTimes[np - 1]);
                }

                int nr = na.mNumRotationKeys();
                if (nr > 0) {
                    AIQuatKey.Buffer keys = na.mRotationKeys();
                    ch.rotTimes = new double[nr];
                    ch.rot = new float[nr * 4];
                    for (int i = 0; i < nr; i++) {
                        AIQuatKey key = keys.get(i);
                        AIQuaternion q = key.mValue();
                        ch.rotTimes[i] = key.mTime();
                        ch.rot[i * 4] = q.x();
                        ch.rot[i * 4 + 1] = q.y();
                        ch.rot[i * 4 + 2] = q.z();
                        ch.rot[i * 4 + 3] = q.w();
                    }
                    maxTime = Math.max(maxTime, ch.rotTimes[nr - 1]);
                }

                int ns = na.mNumScalingKeys();
                if (ns > 0) {
                    AIVectorKey.Buffer keys = na.mScalingKeys();
                    ch.sclTimes = new double[ns];
                    ch.scl = new float[ns * 3];
                    for (int i = 0; i < ns; i++) {
                        AIVectorKey key = keys.get(i);
                        AIVector3D v = key.mValue();
                        ch.sclTimes[i] = key.mTime();
                        ch.scl[i * 3] = v.x();
                        ch.scl[i * 3 + 1] = v.y();
                        ch.scl[i * 3 + 2] = v.z();
                    }
                    maxTime = Math.max(maxTime, ch.sclTimes[ns - 1]);
                }
                byNode[ni] = ch;
            }

            double duration = anim.mDuration() > 0 ? anim.mDuration() : maxTime;
            double tps = anim.mTicksPerSecond() > 0 ? anim.mTicksPerSecond() : 25.0;
            String name = anim.mName().dataString();
            if (name == null || name.isEmpty()) name = "clip" + a;
            clips.add(new Skeleton.Clip(name, duration, tps, byNode));
        }
        return clips;
    }

    /** Skins every vertex in the rest pose on the CPU once, to report the model's real size. */
    private static float[] computeSize(List<Part> parts, Skeleton skeleton) {
        Matrix4f[] palette = skeleton.pose(-1, 0);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        Vector4f in = new Vector4f();
        Vector4f tmp = new Vector4f();

        for (Part part : parts) {
            float[] v = part.vertices();
            for (int i = 0; i < v.length / 8; i++) {
                float px = v[i * 8], py = v[i * 8 + 1], pz = v[i * 8 + 2];
                float ax = 0, ay = 0, az = 0, wsum = 0;
                for (int k = 0; k < 4; k++) {
                    float w = part.boneWeights()[i * 4 + k];
                    if (w <= 0f) continue;
                    palette[part.boneIds()[i * 4 + k]].transform(in.set(px, py, pz, 1f), tmp);
                    ax += w * tmp.x;
                    ay += w * tmp.y;
                    az += w * tmp.z;
                    wsum += w;
                }
                if (wsum <= 1e-3f) {
                    ax = px;
                    ay = py;
                    az = pz;
                }
                any = true;
                minX = Math.min(minX, ax); maxX = Math.max(maxX, ax);
                minY = Math.min(minY, ay); maxY = Math.max(maxY, ay);
                minZ = Math.min(minZ, az); maxZ = Math.max(maxZ, az);
            }
        }
        if (!any) return new float[]{0f, 0f, 0f};
        return new float[]{maxX - minX, maxY - minY, maxZ - minZ};
    }

    /** The diffuse color of a material (what Blender shows as the material's Base Color without an image). */
    private static float[] colorOf(AIMaterial material, int materialIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AIColor4D c = AIColor4D.calloc(stack);
            int result = Assimp.aiGetMaterialColor(material, Assimp.AI_MATKEY_COLOR_DIFFUSE,
                    Assimp.aiTextureType_NONE, 0, c);
            if (result == Assimp.aiReturn_SUCCESS) {
                LOGGER.info("[gpumodels] material {} color: {} {} {}", materialIndex,
                        String.format(java.util.Locale.ROOT, "%.2f", c.r()),
                        String.format(java.util.Locale.ROOT, "%.2f", c.g()),
                        String.format(java.util.Locale.ROOT, "%.2f", c.b()));
                return new float[]{c.r(), c.g(), c.b(), 1f};
            }
        }
        return new float[]{0.75f, 0.75f, 0.75f, 1f};
    }

    // ------------------------------------------------------------------------------------------
    // textures (one TextureRef per material, so every part can have its own texture)
    // ------------------------------------------------------------------------------------------

    private static String materialName(AIMaterial material, int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AIString s = AIString.calloc(stack);
            if (Assimp.aiGetMaterialString(material, Assimp.AI_MATKEY_NAME, 0, 0, s) == Assimp.aiReturn_SUCCESS) {
                String n = s.dataString();
                if (n != null && !n.isEmpty()) return n;
            }
        }
        return "material" + index;
    }

    /** Finds the base color / diffuse texture of a material, external or embedded. Never returns null. */
    private static TextureRef textureOf(AIScene scene, AIMaterial material, int materialIndex) {
        String matName = materialName(material, materialIndex);
        TextureRef none = new TextureRef(matName, null, -1, null, null, 0, 0);

        String path = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int type : new int[]{Assimp.aiTextureType_BASE_COLOR, Assimp.aiTextureType_DIFFUSE}) {
                AIString str = AIString.calloc(stack);
                int result = Assimp.aiGetMaterialTexture(material, type, 0, str,
                        (IntBuffer) null, null, null, null, null, null);
                if (result == Assimp.aiReturn_SUCCESS) {
                    String s = str.dataString();
                    if (s != null && !s.isEmpty()) {
                        path = s;
                        break;
                    }
                }
            }
        }
        if (path == null) {
            LOGGER.info("[gpumodels] material {} '{}' has no base color / diffuse texture ({} embedded textures in the file)",
                    materialIndex, matName, scene.mNumTextures());
            return none;
        }
        LOGGER.info("[gpumodels] material {} '{}' uses texture '{}' ({} embedded textures in the file)",
                materialIndex, matName, path, scene.mNumTextures());

        if (path.startsWith("*")) { // embedded texture referenced by index, e.g. "*0"
            try {
                TextureRef r = readEmbedded(scene, Integer.parseInt(path.substring(1)), matName);
                return r != null ? r : none;
            } catch (NumberFormatException e) {
                return none;
            }
        }

        String name = path.replace('\\', '/');
        String fileName = name.substring(name.lastIndexOf('/') + 1);

        // Some files embed the image but still name it by file name: look for it among the embedded textures.
        for (int i = 0; i < scene.mNumTextures(); i++) {
            AITexture t = AITexture.create(scene.mTextures().get(i));
            String embeddedName = t.mFilename().dataString();
            if (embeddedName == null || embeddedName.isEmpty()) continue;
            String e = embeddedName.replace('\\', '/');
            e = e.substring(e.lastIndexOf('/') + 1);
            if (e.equalsIgnoreCase(fileName)) {
                TextureRef embedded = readEmbedded(scene, i, matName);
                if (embedded != null) return embedded;
            }
        }
        return new TextureRef(matName, fileName, -1, null, null, 0, 0);
    }

    private static TextureRef readEmbedded(AIScene scene, int index, String matName) {
        if (index < 0 || index >= scene.mNumTextures()) return null;
        AITexture tex = AITexture.create(scene.mTextures().get(index));
        if (tex.mHeight() == 0) { // compressed PNG/JPG bytes
            ByteBuffer data = tex.pcDataCompressed();
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            LOGGER.info("[gpumodels] found embedded texture {} ({} bytes)", index, bytes.length);
            return new TextureRef(matName, null, index, bytes, null, 0, 0);
        }
        // raw uncompressed texels
        int w = tex.mWidth(), h = tex.mHeight();
        AITexel.Buffer texels = tex.pcData();
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            AITexel t = texels.get(i);
            rgba[i * 4] = t.r();
            rgba[i * 4 + 1] = t.g();
            rgba[i * 4 + 2] = t.b();
            rgba[i * 4 + 3] = t.a();
        }
        LOGGER.info("[gpumodels] found raw embedded texture {} ({}x{})", index, w, h);
        return new TextureRef(matName, null, index, null, rgba, w, h);
    }
}
