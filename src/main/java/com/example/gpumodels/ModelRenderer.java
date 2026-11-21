package com.example.gpumodels;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Draws GPU-resident, skinned models in the world with our own shader program:
 *  - the test model placed with /gpumodel place
 *  - a replacement model for the local player (/gpumodel localplayer model apply <file>)
 *
 * Per frame the CPU computes one matrix per bone (LoadedModel.pose) and uploads that small array.
 * The vertex shader then moves every vertex by blending up to four of those matrices.
 *
 * Vanilla player rendering is NOT cancelled any more. What is drawn is chosen by the script:
 *   vanilla.visible.body / armor / elytra / items = true or false
 *
 * Iris shader packs: when a pack is active the model is drawn after the pack has finished
 * (RenderLevelStageEvent.Stage.AFTER_LEVEL) straight into the main framebuffer, because the pack's
 * G-buffers do not know our shader. See setLateWithShaders / shadersActive.
 */
public final class ModelRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Supported extensions (also tried when a name is given without extension). */
    private static final String[] EXTENSIONS = {"glb", "gltf", "fbx", "obj", "dae", "3ds", "stl", "ply"};

    private static final String VSH = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            layout(location = 3) in ivec4 aBoneIds;
            layout(location = 4) in vec4 aWeights;
            const int MAX_BONES = 128;
            uniform mat4 uBones[MAX_BONES];
            uniform mat4 uMVP;
            uniform mat3 uNormalMat;
            out vec3 vNormal;
            out vec2 vUv;
            out float vKeep;
            uniform uint uMask[4];
            uniform int uUseMask;
            float keepBone(int id) {
                return float((uMask[id >> 5] >> uint(id & 31)) & 1u);
            }
            void main() {
                float wsum = aWeights.x + aWeights.y + aWeights.z + aWeights.w;
                mat4 skin = mat4(1.0);
                if (wsum > 0.001) {
                    skin = aWeights.x * uBones[aBoneIds.x]
                         + aWeights.y * uBones[aBoneIds.y]
                         + aWeights.z * uBones[aBoneIds.z]
                         + aWeights.w * uBones[aBoneIds.w];
                }
                vNormal = uNormalMat * (mat3(skin) * aNormal);
                vUv = aUv;
                vKeep = 1.0;
                if (uUseMask != 0) {
                    vKeep = aWeights.x * keepBone(aBoneIds.x) + aWeights.y * keepBone(aBoneIds.y)
                          + aWeights.z * keepBone(aBoneIds.z) + aWeights.w * keepBone(aBoneIds.w);
                }
                gl_Position = uMVP * (skin * vec4(aPos, 1.0));
            }
            """;

    private static final String FSH = """
            #version 330 core
            in vec3 vNormal;
            in vec2 vUv;
            in float vKeep;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform vec4 uColor;
            uniform vec3 uLight;
            void main() {
                if (vKeep < 0.5) discard;
                vec4 c = texture(uTex, vUv) * uColor;
                if (c.a < 0.1) discard; // alpha cutout (hair, leaves, ...)
                vec3 lightDir = normalize(vec3(0.4, 0.8, 0.5));
                float d = max(dot(normalize(vNormal), lightDir), 0.0);
                fragColor = vec4(c.rgb * (0.35 + 0.65 * d) * uLight, 1.0);
            }
            """;

    // ---- test model placed in the world ----
    private static volatile Vec3 placed;
    private static volatile boolean reloadRequested = true;
    private static volatile String requestedFile; // null = auto-detect test.<ext>
    private static LoadedModel placedModel;

    // ---- local player replacement ----
    private static volatile String playerApplyName;
    private static volatile boolean playerRemoveRequested;
    private static volatile float playerYawOffsetDeg = 0f;
    private static volatile float playerScale = 1.0f; // 1.0 = real size from the file (1 Blender meter = 1 block)
    private static LoadedModel playerModel;

    // ---- vanilla items that are hidden while vanilla draws the player (see hideVanillaItems) ----
    private static boolean swapped;
    private static boolean itemsSwapped;
    private static LocalPlayer swappedPlayer;
    private static final ItemStack[] savedArmor = new ItemStack[4];
    private static ItemStack savedMain = ItemStack.EMPTY;
    private static ItemStack savedOff = ItemStack.EMPTY;
    private static int savedSlot;

    // ---- shader ----
    private static int program;
    private static boolean programFailed;
    private static int uMvp;
    private static int uNormalMat;
    private static int uTex;
    private static int uBones;
    private static int uColor;
    private static int uMask;
    private static int uUseMask;
    private static float lastPartialTick = 1f;
    private static int uLight;
    private static final Vector3f drawLight = new Vector3f(1f, 1f, 1f);   // light colour used by the next draw()
    private static final Vector3f playerLight = new Vector3f(1f, 1f, 1f); // smoothed light at the local player
    private static final Vector3f targetLight = new Vector3f();
    private static boolean playerLightStarted;
    private static long playerLightNanos;

    // ---- Iris shader packs ----
    private static volatile boolean lateWithShaders = true; // true = draw after the pack, false = inside the pack's pass
    private static java.lang.reflect.Method irisInUse;
    private static Object irisApi;
    private static boolean irisChecked;

    private ModelRenderer() {}

    // ======================= Iris =======================

    /** With a shader pack on: true = draw the model after the pack (default), false = in the normal pass. */
    public static void setLateWithShaders(boolean late) {
        lateWithShaders = late;
    }

    /** Is an Iris shader pack in use right now? (Iris is not a compile dependency, so it is called by reflection.) */
    private static boolean shadersActive() {
        if (!irisChecked) {
            irisChecked = true;
            try {
                if (ModList.get().isLoaded("iris")) {
                    Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = c.getMethod("getInstance").invoke(null);
                    irisInUse = c.getMethod("isShaderPackInUse");
                }
            } catch (Throwable t) {
                LOGGER.warn("[gpumodels] could not hook Iris", t);
            }
        }
        if (irisInUse == null) return false;
        try {
            return (boolean) irisInUse.invoke(irisApi);
        } catch (Throwable t) {
            return false;
        }
    }

    // ======================= commands (all called on the client thread) =======================

    public static void placeAt(Vec3 position) {
        placed = position;
    }

    public static void requestReload() {
        reloadRequested = true;
    }

    /** Chooses a file inside run/gpumodels for the placed test model and reloads it. */
    public static void load(String fileName) {
        requestedFile = fileName;
        reloadRequested = true;
    }

    public static List<String> animationNames() {
        return placedModel == null ? List.of() : placedModel.skeleton().clipNames();
    }

    /** Plays a clip on the placed test model. index -1 (or out of range) shows the rest pose. */
    public static void playAnimation(int index) {
        if (placedModel != null) placedModel.setClip(index);
    }

    /** Returns false when no such file exists. The model itself loads on the next rendered frame. */
    public static boolean applyToLocalPlayer(String name) {
        if (resolveModelFile(name) == null) return false;
        playerApplyName = name;
        playerRemoveRequested = false;
        return true;
    }

    public static void removeFromLocalPlayer() {
        playerApplyName = null;
        playerRemoveRequested = true;
    }

    public static void setPlayerYawOffset(float degrees) {
        playerYawOffsetDeg = degrees;
    }

    public static void setPlayerScale(float scale) {
        playerScale = scale;
    }

    /** Text lines that describe the model applied to the player: its bones, parts and animations. */
    public static List<String> describePlayerModel() {
        List<String> lines = new ArrayList<>();
        if (playerModel == null) {
            lines.add("No model is applied. Use /gpumodel localplayer model apply <file> first.");
            return lines;
        }
        List<String> bones = playerModel.skeleton().readableNames();
        List<String> parts = playerModel.gpu().partNames();
        List<String> clips = playerModel.skeleton().clipNames();

        lines.add("Model " + playerModel.name() + " - bones (" + bones.size() + "): " + String.join(", ", bones));
        lines.add("Parts (" + parts.size() + "): " + String.join(", ", parts));
        StringBuilder anims = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            if (i > 0) anims.append(", ");
            anims.append(i).append(" = ").append(clips.get(i));
        }
        lines.add("Animations (" + clips.size() + "): " + anims);
        return lines;
    }

    /** File names in run/gpumodels that look like models (used for tab completion). */
    public static List<String> listModelFiles() {
        List<String> out = new ArrayList<>();
        Path dir = modelDir();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                String lower = n.toLowerCase(Locale.ROOT);
                for (String ext : EXTENSIONS) {
                    if (lower.endsWith("." + ext)) {
                        out.add(n);
                        break;
                    }
                }
            });
        } catch (IOException ignored) {
            // empty list is fine
        }
        return out;
    }

    // ======================= GUI support =======================

    /** File name of the model applied to the local player, or null. */
    public static String appliedPlayerModelName() {
        return playerModel == null ? null : playerModel.name();
    }

    /**
     * Draws a model into a rectangle of the screen (used by the GUI preview).
     * x, y, w, h are in window pixels with y counted from the BOTTOM (OpenGL style).
     */
    public static void drawPreview(LoadedModel m, int x, int y, int w, int h, float yawDeg, float pitchDeg, float zoom) {
        if (w <= 0 || h <= 0 || !ensureProgram()) return;
        Minecraft mc = Minecraft.getInstance();
        int fullW = mc.getWindow().getWidth();
        int fullH = mc.getWindow().getHeight();

        RenderSystem.enableScissor(x, y, w, h);
        RenderSystem.clear(256, Minecraft.ON_OSX); // depth only (GL_DEPTH_BUFFER_BIT), limited by the scissor
        RenderSystem.viewport(x, y, w, h);

        float maxDim = Math.max(0.1f, Math.max(m.height(), Math.max(m.width(), m.depth())));
        float fov = (float) Math.toRadians(30.0);
        float dist = maxDim * 0.5f / (float) Math.tan(fov / 2.0) * 1.35f / zoom;
        Matrix4f projection = new Matrix4f().perspective(fov, (float) w / (float) h, 0.05f, 500f);
        Matrix4f mv = new Matrix4f()
                .translate(0f, 0f, -dist)
                .rotateX((float) Math.toRadians(pitchDeg))
                .rotateY((float) Math.toRadians(yawDeg))
                .translate(0f, -m.height() * 0.5f, 0f);
        drawLight.set(1f, 1f, 1f); // the model list preview is fully lit
        draw(m, projection, mv);

        RenderSystem.viewport(0, 0, fullW, fullH);
        RenderSystem.disableScissor();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    // ======================= vanilla player visibility =======================

    /** True while the replacement model is active, so vanilla parts may be hidden (world and inventory preview). */
    private static boolean controllingVanilla() {
        return playerModel != null;
    }

    /** True while the GUI is being drawn (it uses an orthographic projection, the world uses a perspective one). */
    private static boolean isGuiProjection() {
        return Math.abs(RenderSystem.getProjectionMatrix().m33() - 1f) < 1.0e-4f;
    }

    /**
     * Vanilla now draws the player as usual. Before it does, hide what the script switched off:
     * armor, elytra and held items are hidden by emptying their inventory slots until the render is over
     * (restored in onRenderPlayerPost). The vanilla body is hidden in onRenderLiving.
     */
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        restoreVanillaItems();
        if (controllingVanilla() && event.getEntity() == Minecraft.getInstance().player) {
            hideVanillaItems(Minecraft.getInstance().player);
            // Let the vanilla model work out its pose for this frame (walking, looking, sneaking, ...) so scripts can
            // copy it onto bones. The event is NOT cancelled: vanilla keeps drawing what the script left visible.
            VanillaPose.capture(event.getRenderer(), (AbstractClientPlayer) event.getEntity(), event.getPartialTick());
        }
    }

    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        restoreVanillaItems();
        // The player preview in the inventory / crafting screen: draw our model there too.
        if (playerModel != null && event.getEntity() == Minecraft.getInstance().player
                && isGuiProjection() && ensureProgram()) {
            LocalPlayer player = Minecraft.getInstance().player;
            float pt = event.getPartialTick();
            float bodyYaw = Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
            Integer scripted = Scripting.update(playerModel, player, pt, playerYawOffsetDeg, playerScale, true);
            playerModel.setClip(scripted != null ? scripted : -1);

            // The pose stack already holds the position, size and mouse rotation of the preview.
            Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix())
                    .mul(event.getPoseStack().last().pose())
                    .rotateY((float) Math.toRadians(-bodyYaw + playerYawOffsetDeg))
                    .scale(playerScale);
            drawLight.set(1f, 1f, 1f); // the vanilla inventory preview is fully lit
            draw(playerModel, RenderSystem.getProjectionMatrix(), mv);
        }
    }

    /** Hides the vanilla body parts (head, torso, arms, legs and their second layers) when the script says so. */
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (!controllingVanilla() || event.getEntity() != Minecraft.getInstance().player) return;
        if (Scripting.vanillaVisible("body")) return;
        Object model = event.getRenderer().getModel();
        if (model instanceof PlayerModel<?> playerModelParts) {
            playerModelParts.setAllVisible(false); // vanilla sets these again before every player render
        }
    }

    private static void hideVanillaItems(LocalPlayer p) {
        boolean hideArmor = !Scripting.vanillaVisible("armor");
        boolean hideElytra = !Scripting.vanillaVisible("elytra");
        boolean hideItems = !Scripting.vanillaVisible("items");
        if (!hideArmor && !hideElytra && !hideItems) return;

        Inventory inv = p.getInventory();
        swapped = true;
        swappedPlayer = p;
        for (int i = 0; i < 4; i++) {
            ItemStack stack = inv.armor.get(i);
            savedArmor[i] = stack;
            boolean isElytra = stack.getItem() instanceof ElytraItem;
            if (isElytra ? hideElytra : hideArmor) inv.armor.set(i, ItemStack.EMPTY);
        }
        if (hideItems) {
            savedSlot = inv.selected;
            savedMain = inv.items.get(savedSlot);
            savedOff = inv.offhand.get(0);
            inv.items.set(savedSlot, ItemStack.EMPTY);
            inv.offhand.set(0, ItemStack.EMPTY);
            itemsSwapped = true;
        }
    }

    /** Puts every hidden item back. Safe to call any time; it does nothing when nothing is hidden. */
    private static void restoreVanillaItems() {
        if (!swapped) return;
        swapped = false;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p == swappedPlayer) {
            Inventory inv = p.getInventory();
            for (int i = 0; i < 4; i++) {
                if (savedArmor[i] != null) inv.armor.set(i, savedArmor[i]);
            }
            if (itemsSwapped) {
                inv.items.set(savedSlot, savedMain);
                inv.offhand.set(0, savedOff);
            }
        }
        itemsSwapped = false;
        swappedPlayer = null;
    }

    // ======================= lighting =======================

    /** Minecraft's light at a spot as a colour: sky light follows day, night and rain; block light is warm. */
    private static void computeLight(ClientLevel level, Vec3 pos, float pt, Vector3f out) {
        BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);
        int sky = level.getBrightness(LightLayer.SKY, blockPos);
        int block = level.getBrightness(LightLayer.BLOCK, blockPos);

        // the same day/night curve vanilla uses for its light map
        float t = level.getTimeOfDay(pt);
        float darken = 1f - (Mth.cos(t * ((float) Math.PI * 2f)) * 2f + 0.2f);
        darken = 1f - Mth.clamp(darken, 0f, 1f);
        darken *= 1f - level.getRainLevel(pt) * 5f / 16f;
        darken *= 1f - level.getThunderLevel(pt) * 5f / 16f;
        darken = darken * 0.8f + 0.2f;

        float s = lightCurve(sky / 15f) * darken;
        float b = lightCurve(block / 15f);
        float floor = 0.05f + level.dimensionType().ambientLight() * 0.6f;
        out.set(Math.min(1f, b + s * 0.95f + floor),
                Math.min(1f, b * 0.88f + s * 0.97f + floor),
                Math.min(1f, b * 0.70f + s + floor));
    }

    private static float lightCurve(float x) {
        return x / (4f - 3f * x);
    }

    /** Measures the light at the player's head and eases towards it, so the model does not flash when light changes. */
    private static void updatePlayerLight(LocalPlayer player, float pt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        computeLight(level, player.getEyePosition(pt), pt, targetLight);
        long now = System.nanoTime();
        if (!playerLightStarted) {
            playerLightStarted = true;
            playerLight.set(targetLight);
        } else {
            float dt = Math.min(0.25f, (now - playerLightNanos) / 1.0e9f);
            playerLight.lerp(targetLight, 1f - (float) Math.exp(-dt * 8f));
        }
        playerLightNanos = now;
    }

    // ======================= first-person arm =======================

    /**
     * Draws the model's own arm instead of the vanilla first-person hand. The script picks the arm bones:
     * firstPerson.rightArm("Arm_Right") and firstPerson.leftArm("Arm_Left"). That bone and every bone below it
     * are drawn (nothing else), placed where the vanilla arm would be.
     */
    public static void onRenderArm(RenderArmEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (playerModel == null || player == null || event.getPlayer() != player) return;
        boolean right = event.getArm() == HumanoidArm.RIGHT;
        int node = Scripting.firstPersonNode(right);
        if (node < 0 || !ensureProgram()) return;

        // pose the model like in the world (script bones, animation)
        Integer scripted = Scripting.update(playerModel, player, lastPartialTick, playerYawOffsetDeg, playerScale, true);
        playerModel.setClip(scripted != null ? scripted : -1);
        playerModel.pose(); // fills in the bone positions read below

        Skeleton skeleton = playerModel.skeleton();
        Matrix4f nodeMatrix = skeleton.nodeMatrix(node, new Matrix4f());
        Vector3f pivot = nodeMatrix.getTranslation(new Vector3f());
        Vector3f dir = nodeMatrix.transformPosition(skeleton.tipOffset(node), new Vector3f()).sub(pivot);
        if (dir.lengthSquared() < 1.0e-12f) return;
        dir.normalize();

        // our model space (y up, front +Z) -> vanilla arm space (y down, front -Z), then turn the arm so it
        // points down the vanilla arm (which hangs along +y)
        Quaternionf flip = new Quaternionf().rotateX((float) Math.PI);
        Quaternionf toVanilla = new Quaternionf()
                .rotationTo(flip.transform(new Vector3f(dir)), new Vector3f(0f, 1f, 0f))
                .mul(flip);

        PlayerRenderer renderer = (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
        PlayerModel<AbstractClientPlayer> vanillaModel = renderer.getModel();
        ModelPart arm = right ? vanillaModel.rightArm : vanillaModel.leftArm;
        float unit = playerModel.unitScale();
        float size = Scripting.firstPersonScale() * playerScale;

        // the pose stack is where vanilla would draw the arm; the arm's shoulder joint is offset by the part position
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix())
                .mul(event.getPoseStack().last().pose())
                .translate(arm.x / 16f, arm.y / 16f, arm.z / 16f)
                .translate(Scripting.firstPersonOffset())
                .rotate(toVanilla)
                .scale(size)
                .translate(-pivot.x * unit, -pivot.y * unit, -pivot.z * unit); // draw() scales by unit after this
        drawLight.set(playerLight);
        draw(playerModel, RenderSystem.getProjectionMatrix(), mv, skeleton.boneMaskBelow(node));

        if (!Scripting.vanillaVisible("body")) event.setCanceled(true); // hide the vanilla hand
    }

    // ======================= events =======================

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            restoreVanillaItems(); // safety net: entities are drawn by now, nothing may stay hidden
        }

        // Without a shader pack: draw after the entities. With an Iris shader pack: draw after the whole level,
        // when the pack has finished, so its G-buffers never see our model (that made it invisible + a ghost outline).
        boolean shaders = shadersActive();
        boolean late = shaders && lateWithShaders;
        RenderLevelStageEvent.Stage drawStage = late
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL
                : RenderLevelStageEvent.Stage.AFTER_ENTITIES;
        if (event.getStage() != drawStage) return;

        lastPartialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        if (!ensureProgram()) return;
        processPendingLoads();

        Minecraft mc = Minecraft.getInstance();
        if (late) mc.getMainRenderTarget().bindWrite(true); // draw into the real screen buffer

        Vec3 cam = event.getCamera().getPosition();
        Matrix4f view = event.getModelViewMatrix();
        Matrix4f projection = event.getProjectionMatrix();

        if (mc.player != null && mc.level != null) updatePlayerLight(mc.player, lastPartialTick);

        Vec3 at = placed;
        if (placedModel != null && at != null) {
            Matrix4f mv = new Matrix4f(view)
                    .translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
            if (mc.level != null) computeLight(mc.level, at.add(0.0, 1.0, 0.0), lastPartialTick, drawLight);
            draw(placedModel, projection, mv);
        }

        LocalPlayer player = mc.player;
        if (playerModel != null && player != null && !mc.options.getCameraType().isFirstPerson()) {
            float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
            Vec3 p = player.getPosition(pt);
            float bodyYaw = Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
            Integer scripted = Scripting.update(playerModel, player, pt, playerYawOffsetDeg, playerScale);
            // No animation plays unless the script asks for one (animation(...):state(...) or :play()).
            playerModel.setClip(scripted != null ? scripted : -1);

            // Minecraft yaw grows clockwise. rotateY(-yaw) turns a model whose front is +Z to face the player's direction.
            Matrix4f mv = new Matrix4f(view)
                    .translate((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
                    .rotateY((float) Math.toRadians(-bodyYaw + playerYawOffsetDeg))
                    .scale(playerScale);
            drawLight.set(playerLight);
            draw(playerModel, projection, mv);
        }
    }

    // ======================= drawing =======================

    private static void draw(LoadedModel m, Matrix4f projection, Matrix4f modelView) {
        draw(m, projection, modelView, null);
    }

    /** boneMask = 4 ints (128 bits) of the bones to draw, or null to draw everything. */
    private static void draw(LoadedModel m, Matrix4f projection, Matrix4f modelView, int[] boneMask) {
        Matrix4f mv = new Matrix4f(modelView).scale(m.unitScale()); // 1 unit in the file = 1 block (FBX in cm: 0.01)
        Matrix4f mvp = new Matrix4f(projection).mul(mv);
        Matrix3f normalMat = new Matrix3f(mv).invert().transpose();
        Matrix4f[] palette = m.pose();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        int previousProgram = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM); // give Minecraft its shader back afterwards
        GlStateManager._glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer m4 = stack.mallocFloat(16);
            mvp.get(m4);
            GL20.glUniformMatrix4fv(uMvp, false, m4);
            FloatBuffer m3 = stack.mallocFloat(9);
            normalMat.get(m3);
            GL20.glUniformMatrix3fv(uNormalMat, false, m3);

            int boneCount = Math.min(palette.length, Skeleton.MAX_BONES);
            if (boneCount > 0) {
                FloatBuffer bones = stack.mallocFloat(boneCount * 16);
                for (int i = 0; i < boneCount; i++) {
                    palette[i].get(i * 16, bones);
                }
                GL20.glUniformMatrix4fv(uBones, false, bones);
            }
        }
        GL20.glUniform1i(uTex, GpuTexture.UNIT);
        GL20.glUniform1i(uUseMask, boneMask != null ? 1 : 0);
        GL20.glUniform3f(uLight, drawLight.x, drawLight.y, drawLight.z);
        if (boneMask != null) GL30.glUniform1uiv(uMask, boneMask);

        m.gpu().draw(uColor);

        GlStateManager._glUseProgram(previousProgram);
        RenderSystem.enableCull();
    }

    // ======================= loading =======================

    private static void processPendingLoads() {
        Path dir = modelDir();

        if (reloadRequested) {
            reloadRequested = false;
            closeQuietly(placedModel);
            placedModel = null;
            Path file = findPlacedFile(dir);
            if (file == null) {
                LOGGER.warn("[gpumodels] no model found. Put test.glb / test.fbx / test.obj in {} or use /gpumodel load <file>", dir);
            } else {
                placedModel = tryLoad(file, dir);
                if (placedModel != null) {
                    placedModel.setClip(placedModel.skeleton().clipCount() > 0 ? 0 : -1);
                }
            }
        }

        String applyName = playerApplyName;
        if (applyName != null) {
            playerApplyName = null;
            Path file = resolveModelFile(applyName);
            if (file != null) {
                LoadedModel loaded = tryLoad(file, dir);
                if (loaded != null) {
                    Scripting.detach();
                    closeQuietly(playerModel);
                    playerModel = loaded;
                    Scripting.attach(file, dir, loaded);
                }
            }
        }

        if (playerRemoveRequested) {
            playerRemoveRequested = false;
            Scripting.detach();
            closeQuietly(playerModel);
            playerModel = null;
        }
    }

    private static LoadedModel tryLoad(Path file, Path dir) {
        try {
            return LoadedModel.load(file, dir);
        } catch (Throwable t) { // Throwable on purpose: also reports missing Assimp classes or natives
            LOGGER.error("[gpumodels] failed to load " + file.getFileName(), t);
            return null;
        }
    }

    private static void closeQuietly(LoadedModel m) {
        if (m != null) m.close();
    }

    public static Path modelDir() {
        return FMLPaths.GAMEDIR.get().resolve("gpumodels").toAbsolutePath().normalize();
    }

    private static Path findPlacedFile(Path dir) {
        String requested = requestedFile;
        if (requested != null) {
            return resolveModelFile(requested);
        }
        for (String ext : EXTENSIONS) {
            Path p = dir.resolve("test." + ext);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    /** Finds a model inside run/gpumodels by exact file name, or by name without extension. */
    public static Path resolveModelFile(String name) {
        Path dir = modelDir();
        Path direct = dir.resolve(name).normalize();
        if (direct.startsWith(dir) && Files.isRegularFile(direct)) return direct;
        for (String ext : EXTENSIONS) {
            Path p = dir.resolve(name + "." + ext).normalize();
            if (p.startsWith(dir) && Files.isRegularFile(p)) return p;
        }
        return null;
    }

    // ======================= shader =======================

    private static boolean ensureProgram() {
        if (program != 0) return true;
        if (programFailed) return false;
        try {
            program = buildProgram();
            uMvp = GL20.glGetUniformLocation(program, "uMVP");
            uNormalMat = GL20.glGetUniformLocation(program, "uNormalMat");
            uTex = GL20.glGetUniformLocation(program, "uTex");
            uBones = GL20.glGetUniformLocation(program, "uBones");
            uColor = GL20.glGetUniformLocation(program, "uColor");
            uMask = GL20.glGetUniformLocation(program, "uMask");
            uUseMask = GL20.glGetUniformLocation(program, "uUseMask");
            uLight = GL20.glGetUniformLocation(program, "uLight");
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("[gpumodels] shader build failed", e);
            programFailed = true;
            program = 0;
            return false;
        }
    }

    private static int buildProgram() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VSH);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FSH);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(p);
            GL20.glDeleteProgram(p);
            throw new RuntimeException("Program link failed: " + log);
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(s);
            GL20.glDeleteShader(s);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }
}
