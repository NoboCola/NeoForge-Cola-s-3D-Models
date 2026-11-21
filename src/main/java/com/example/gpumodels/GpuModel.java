package com.example.gpumodels;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * A model living entirely in GPU memory: one VAO per part (usually one part per material), each with
 * its own texture. All buffers are uploaded once (STATIC_DRAW); drawing is one glDrawElements call per
 * part. Animation costs no vertex uploads: only the bone matrices change per frame (a uniform array).
 *
 * Attributes: 0 = position, 1 = normal, 2 = uv (interleaved, 8 floats),
 *             3 = bone ids (4 ints, integer attribute), 4 = bone weights (4 floats).
 */
public final class GpuModel implements AutoCloseable {
    private static final int STRIDE = 8 * Float.BYTES;

    /** Turns a texture reference from the loader into a GPU texture. */
    public interface TextureResolver {
        GpuTexture resolve(ModelLoader.TextureRef ref);
    }

    private static final class GpuPart {
        int vao, vbo, boneVbo, weightVbo, ebo, indexCount;
        GpuTexture texture;
        float[] color;
        String name;
        boolean visible = true;
        boolean tinted; // no texture image: draw with the material color
    }

    private final List<GpuPart> parts = new ArrayList<>();
    private final Set<GpuTexture> ownedTextures = Collections.newSetFromMap(new IdentityHashMap<>());

    public GpuModel(ModelLoader.ModelData data, TextureResolver resolver) {
        // Minecraft remembers which vertex array it has bound. We must leave that binding exactly as we found it,
        // otherwise its next draw call fails with "GL_INVALID_OPERATION: Array object is not active".
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        for (ModelLoader.Part part : data.parts()) {
            GpuPart g = new GpuPart();
            g.indexCount = part.indices().length;
            g.vao = GL30.glGenVertexArrays();
            g.vbo = GL15.glGenBuffers();
            g.boneVbo = GL15.glGenBuffers();
            g.weightVbo = GL15.glGenBuffers();
            g.ebo = GL15.glGenBuffers();

            GL30.glBindVertexArray(g.vao);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, g.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, part.vertices(), GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, STRIDE, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 6L * Float.BYTES);
            GL20.glEnableVertexAttribArray(2);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, g.boneVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, part.boneIds(), GL15.GL_STATIC_DRAW);
            GL30.glVertexAttribIPointer(3, 4, GL11.GL_INT, 0, 0L); // "I" = stays an integer in the shader
            GL20.glEnableVertexAttribArray(3);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, g.weightVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, part.boneWeights(), GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(4, 4, GL11.GL_FLOAT, false, 0, 0L);
            GL20.glEnableVertexAttribArray(4);

            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, g.ebo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, part.indices(), GL15.GL_STATIC_DRAW);

            // Unbind the VAO first so the element buffer binding stays stored inside it.
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            g.texture = resolver.resolve(part.texture());
            g.color = part.color();
            g.name = part.name();
            g.tinted = g.texture.isPlaceholder();
            ownedTextures.add(g.texture);
            parts.add(g);
        }
        GL30.glBindVertexArray(previousVao);
    }

    public int partCount() {
        return parts.size();
    }

    /** Names of the parts (the node names from the file, for example "Hair"), without duplicates. */
    public java.util.List<String> partNames() {
        java.util.List<String> names = new ArrayList<>();
        for (GpuPart g : parts) {
            if (!names.contains(g.name)) names.add(g.name);
        }
        return names;
    }

    /** Shows or hides every part with this name. Returns how many parts matched. */
    public int setPartVisible(String name, boolean visible) {
        int count = 0;
        for (GpuPart g : parts) {
            if (g.name.equals(name)) {
                g.visible = visible;
                count++;
            }
        }
        return count;
    }

    public void showAllParts() {
        for (GpuPart g : parts) g.visible = true;
    }

    /** colorUniform = location of the shader's "uColor" (vec4). Parts without a texture image use their material color. */
    public void draw(int colorUniform) {
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + GpuTexture.UNIT);
        for (GpuPart g : parts) {
            if (!g.visible) continue;
            g.texture.bindOnCurrentUnit();
            if (g.tinted) {
                GL20.glUniform4f(colorUniform, g.color[0], g.color[1], g.color[2], g.color[3]);
            } else {
                GL20.glUniform4f(colorUniform, 1f, 1f, 1f, 1f);
            }
            GL30.glBindVertexArray(g.vao);
            GL11.glDrawElements(GL11.GL_TRIANGLES, g.indexCount, GL11.GL_UNSIGNED_INT, 0L);
        }
        GL30.glBindVertexArray(previousVao); // give Minecraft back the vertex array it had bound
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(prevUnit);
    }

    @Override
    public void close() {
        for (GpuPart g : parts) {
            GL30.glDeleteVertexArrays(g.vao);
            GL15.glDeleteBuffers(g.vbo);
            GL15.glDeleteBuffers(g.boneVbo);
            GL15.glDeleteBuffers(g.weightVbo);
            GL15.glDeleteBuffers(g.ebo);
        }
        for (GpuTexture t : ownedTextures) {
            t.close();
        }
        parts.clear();
        ownedTextures.clear();
    }
}
