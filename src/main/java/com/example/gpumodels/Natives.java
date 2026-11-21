package com.example.gpumodels;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Assimp is a native library (assimp.dll). Minecraft does not ship it, so the mod jar carries it inside.
 * Before the first model is loaded it is copied to run/gpumodels/natives/ and loaded from there.
 * (In the development environment the DLL is on the classpath instead and nothing needs to be copied.)
 */
final class Natives {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where the DLL sits inside the mod jar (LWJGL 3.3 natives layout), most likely path first. */
    private static final String[] RESOURCES = {
            "/windows/x64/org/lwjgl/assimp/assimp.dll",
            "/windows/x64/assimp.dll",
    };

    private static boolean done;

    private Natives() {}

    static synchronized void prepareAssimp() {
        if (done) return;
        done = true;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            LOGGER.info("[gpumodels] not Windows: relying on the system's LWJGL Assimp natives");
            return;
        }

        try {
            for (String resource : RESOURCES) {
                try (InputStream in = Natives.class.getResourceAsStream(resource)) {
                    if (in == null) continue;
                    byte[] bytes = in.readAllBytes();
                    Path dir = FMLPaths.GAMEDIR.get().resolve("gpumodels").resolve("natives");
                    Files.createDirectories(dir);
                    Path target = dir.resolve("assimp.dll");
                    if (!Files.exists(target) || Files.size(target) != bytes.length) {
                        Files.write(target, bytes);
                    }
                    System.load(target.toAbsolutePath().toString());
                    LOGGER.info("[gpumodels] loaded Assimp natives from {}", target);
                    return;
                }
            }
            LOGGER.info("[gpumodels] assimp.dll is not inside the jar (development run): using the classpath natives");
        } catch (Throwable t) {
            LOGGER.error("[gpumodels] could not prepare the Assimp natives", t);
        }
    }
}
