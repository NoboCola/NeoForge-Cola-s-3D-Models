package com.example.gpumodels;

import com.mojang.logging.LogUtils;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One loaded model: GPU buffers + skeleton + animation state. The renderer can hold several of
 * these at once (for example the test model placed in the world and the local player's model).
 */
public final class LoadedModel implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final String name;
    private final GpuModel gpu;
    private final Skeleton skeleton;
    private final float unitScale;
    private final int idleClip;
    private final int moveClip;
    private final float[] size;

    private int clip = -1;
    private float speed = 1f;
    private long clipStartNanos = System.nanoTime();

    private LoadedModel(String name, GpuModel gpu, Skeleton skeleton, float unitScale, float[] size) {
        this.name = name;
        this.gpu = gpu;
        this.skeleton = skeleton;
        this.unitScale = unitScale;
        this.size = size;

        List<String> clips = skeleton.clipNames();
        this.idleClip = findClip(clips, "idle", "stand");
        int move = findClip(clips, "walk", "run");
        this.moveClip = move >= 0 ? move : (clips.isEmpty() ? -1 : 0);
    }

    /** Must be called on the render thread (it creates GL buffers and textures). */
    public static LoadedModel load(Path file, Path dir) throws IOException {
        Natives.prepareAssimp(); // makes sure Assimp's DLL is available when the mod runs outside the dev environment
        ModelLoader.ModelData data = ModelLoader.load(file);
        if (data.parts().isEmpty()) {
            throw new IOException(file.getFileName() + " contains no triangle meshes");
        }
        String fileName = file.getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        // How many different materials does the model have? A single material may use <model>.png.
        Set<String> materialKeys = new HashSet<>();
        for (ModelLoader.Part p : data.parts()) {
            materialKeys.add(p.texture() == null ? "none" : p.texture().key());
        }
        GpuModel gpu = new GpuModel(data, makeResolver(file.getParent(), dir, baseName, materialKeys.size() <= 1));

        int vertices = 0, triangles = 0;
        for (ModelLoader.Part p : data.parts()) {
            vertices += p.vertices().length / 8;
            triangles += p.indices().length / 3;
        }
        Skeleton skeleton = data.skeleton();
        float[] size = data.size();
        LOGGER.info("[gpumodels] loaded {} ({} parts, {} vertices, {} triangles, {} bones, {} animations {})",
                file.getFileName(), data.parts().size(), vertices, triangles,
                skeleton.boneCount(), skeleton.clipCount(), skeleton.clipNames());
        LOGGER.info("[gpumodels] real size in blocks (rest pose): {} wide, {} tall, {} deep",
                String.format(Locale.ROOT, "%.3f", size[0]),
                String.format(Locale.ROOT, "%.3f", size[1]),
                String.format(Locale.ROOT, "%.3f", size[2]));
        return new LoadedModel(file.getFileName().toString(), gpu, skeleton, data.unitScale(), size);
    }

    public String name() {
        return name;
    }

    /** Real width in blocks (rest pose). */
    public float width() {
        return size[0];
    }

    /** Real height in blocks (rest pose). */
    public float height() {
        return size[1];
    }

    /** Real depth in blocks (rest pose). */
    public float depth() {
        return size[2];
    }

    public GpuModel gpu() {
        return gpu;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    /** 1 for most models, 0.01 for FBX files that were detected as centimeters. */
    public float unitScale() {
        return unitScale;
    }

    public int idleClip() {
        return idleClip;
    }

    public int moveClip() {
        return moveClip;
    }

    /** Switches the playing clip (-1 = rest pose). The clip restarts only when it changes. */
    public void setClip(int index) {
        if (index != clip) {
            clip = index;
            clipStartNanos = System.nanoTime();
        }
    }

    /** Playback speed of the animation (1 = normal). */
    public void setSpeed(float speed) {
        this.speed = speed;
    }

    /** The bone matrices for the current clip and time. The array is reused by the next call. */
    public Matrix4f[] pose() {
        return skeleton.pose(clip, (System.nanoTime() - clipStartNanos) / 1.0e9 * speed);
    }

    @Override
    public void close() {
        gpu.close();
    }

    private static int findClip(List<String> names, String... keywords) {
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i).toLowerCase(Locale.ROOT);
            for (String kw : keywords) {
                if (n.contains(kw)) return i;
            }
        }
        return -1;
    }

    // ---- texture lookup ----

    /** Name of the folder inside run/gpumodels that holds external textures. */
    public static final String TEXTURE_FOLDER = "gpu texture";

    /** Image extensions accepted for external textures, in order of preference. */
    private static final String[] IMAGE_EXTENSIONS = {"png", "jpg", "jpeg", "tga", "bmp", "gif"};

    /**
     * Texture order, per material (each part gets the texture of its own material):
     *  1. a texture embedded in the model file;
     *  2. the image file the model names for that material;
     *  3. <model>_<material>.png, then <material>.png;
     *  4. <model>.png, only when the model has a single material;
     *  5. the material color.
     * Searched in: gpu texture/<model>/, gpu texture/, next to the model, <model folder>/<model>/ and
     * <model folder>/textures/. The same texture is only created once.
     */
    private static GpuModel.TextureResolver makeResolver(Path modelFolder, Path dir, String baseName,
                                                         boolean allowModelNamed) {
        Map<String, GpuTexture> cache = new HashMap<>();
        return ref -> cache.computeIfAbsent(ref == null ? "none" : ref.key(),
                k -> createTexture(modelFolder, dir, baseName, ref, allowModelNamed));
    }

    private static GpuTexture createTexture(Path modelFolder, Path dir, String baseName,
                                            ModelLoader.TextureRef ref, boolean allowModelNamed) {
        String material = ref != null ? ref.material() : "?";
        try {
            if (ref != null && ref.encoded() != null) {
                LOGGER.info("[gpumodels] material '{}': using the texture embedded in the model ({} bytes)",
                        material, ref.encoded().length);
                return GpuTexture.decode(ref.encoded(), "embedded texture");
            }
            if (ref != null && ref.rgba() != null) {
                LOGGER.info("[gpumodels] material '{}': using the raw texture embedded in the model ({}x{})",
                        material, ref.width(), ref.height());
                return GpuTexture.fromRgba(ref.rgba(), ref.width(), ref.height());
            }

            List<Path> folders = new ArrayList<>();
            Path tf = dir.resolve(TEXTURE_FOLDER);
            folders.add(tf.resolve(baseName));
            folders.add(tf);
            if (modelFolder != null) {
                folders.add(modelFolder.resolve(baseName));
                folders.add(modelFolder.resolve("textures"));
                folders.add(modelFolder);
            }

            Path found = null;
            if (ref != null && ref.file() != null) {
                found = findFile(folders, ref.file());
                if (found == null) { // same name, other image type
                    String f = ref.file();
                    found = findNamed(folders, f.contains(".") ? f.substring(0, f.lastIndexOf('.')) : f);
                }
            }
            if (found == null && ref != null && ref.material() != null) {
                found = findNamed(folders, baseName + "_" + ref.material());
                if (found == null) found = findNamed(folders, ref.material());
            }
            if (found == null && allowModelNamed) found = findNamed(folders, baseName);

            if (found != null) {
                LOGGER.info("[gpumodels] material '{}': using texture file {}", material, found);
                return GpuTexture.load(found);
            }
            LOGGER.info("[gpumodels] material '{}': no texture found (the model names '{}'), using the material color. "
                            + "Put a file called {}_{}.png in the '{}' folder to give it a texture.",
                    material, ref != null ? ref.file() : null, baseName, material, TEXTURE_FOLDER);
        } catch (Exception e) {
            LOGGER.error("[gpumodels] failed to load a texture for material " + material, e);
        }
        return GpuTexture.white();
    }

    /** Finds <base>.<image extension> in the first folder that has it. */
    private static Path findNamed(List<Path> folders, String base) {
        for (String ext : IMAGE_EXTENSIONS) {
            Path p = findFile(folders, base + "." + ext);
            if (p != null) return p;
        }
        return null;
    }

    /** Finds a file by exact name (case-insensitive) in the first folder that has it. */
    private static Path findFile(List<Path> folders, String fileName) {
        String wanted = fileName.toLowerCase(Locale.ROOT);
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) continue;
            try (Stream<Path> files = Files.list(folder)) {
                Optional<Path> hit = files
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted))
                        .findFirst();
                if (hit.isPresent()) return hit.get();
            } catch (IOException ignored) {
                // try the next folder
            }
        }
        return null;
    }
}
