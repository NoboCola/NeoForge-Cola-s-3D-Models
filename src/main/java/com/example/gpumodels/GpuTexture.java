package com.example.gpumodels;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A texture living in GPU memory.
 *
 * Minecraft caches which texture is bound on which unit. To avoid confusing that cache we only
 * ever touch our own texture unit (UNIT) with raw GL calls. GpuModel.draw() activates that unit,
 * binds each part's texture with bindOnCurrentUnit(), and restores the previous unit afterwards.
 */
public final class GpuTexture implements AutoCloseable {
    /** Texture unit used by this mod. Minecraft itself uses units 0-2. */
    public static final int UNIT = 5;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int id;
    private final boolean placeholder;

    private GpuTexture(ByteBuffer pixels, int width, int height, boolean placeholder) {
        this.id = GL11.glGenTextures();
        this.placeholder = placeholder;

        if (!placeholder) {
            LOGGER.info("[gpumodels] uploading a {}x{} texture", width, height);
        }

        int prev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);

        // Minecraft leaves these "unpack" settings at whatever its last upload used. If they are not zero,
        // OpenGL reads past the end of our pixel buffer and the game crashes. Set them for our upload, then put them back.
        int oldRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int oldSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int oldSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int oldAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, oldRowLength);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, oldSkipPixels);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, oldSkipRows);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, oldAlignment);

        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(prev);
    }

    /** Loads PNG, JPG, TGA, BMP and other formats supported by stb_image. */
    public static GpuTexture load(Path file) throws IOException {
        return decode(Files.readAllBytes(file), file.getFileName().toString());
    }

    /** Decodes encoded image bytes (for example a PNG embedded inside a GLB or FBX file). */
    public static GpuTexture decode(byte[] bytes, String label) throws IOException {
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        try {
            encoded.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
                if (pixels == null) {
                    throw new IOException("stb_image could not decode " + label
                            + ": " + STBImage.stbi_failure_reason());
                }
                try {
                    return new GpuTexture(pixels, w.get(0), h.get(0), false);
                } finally {
                    STBImage.stbi_image_free(pixels);
                }
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    /** Creates a texture from raw RGBA bytes (embedded textures that are not PNG/JPG-compressed). */
    public static GpuTexture fromRgba(byte[] rgba, int width, int height) {
        ByteBuffer buf = MemoryUtil.memAlloc(rgba.length);
        try {
            buf.put(rgba).flip();
            return new GpuTexture(buf, width, height, false);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    /** 1x1 white texture, used when a part has no texture image: the material color then shows through. */
    public static GpuTexture white() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer px = stack.malloc(4);
            px.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
            return new GpuTexture(px, 1, 1, true);
        }
    }

    /** True for the plain white stand-in (no image was found for the part). */
    public boolean isPlaceholder() {
        return placeholder;
    }

    /** Binds this texture on whatever unit is currently active (GpuModel activates UNIT first). */
    public void bindOnCurrentUnit() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    @Override
    public void close() {
        GL11.glDeleteTextures(id);
    }
}
