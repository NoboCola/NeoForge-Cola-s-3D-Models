package com.example.gpumodels;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects the Lua script to the model of the local player.
 *
 * The script is a text file named like the model (untitled.fbx -> untitled.lua). It is looked for in
 * run/gpumodels/gpu script/ first and then next to the model.
 */
public final class Scripting {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Name of the folder (inside run/gpumodels) for script files. */
    public static final String SCRIPT_FOLDER = "gpu script";

    private static LuaScript script;
    private static LoadedModel scriptedModel;
    private static Path modelFile;
    private static Path modelsDir;

    private Scripting() {}

    // =========================== loading ===========================

    /** Called when a model is applied to the local player. Loads the matching script, if there is one. */
    public static void attach(Path file, Path dir, LoadedModel model) {
        detach();
        scriptedModel = model;
        modelFile = file;
        modelsDir = dir;
        loadScript();
    }

    /** Called when the model is removed or replaced. Undoes everything the script did. */
    public static void detach() {
        if (scriptedModel != null) {
            scriptedModel.skeleton().clearAdjusts();
            scriptedModel.gpu().showAllParts();
            scriptedModel.setSpeed(1f);
        }
        script = null;
        scriptedModel = null;
    }

    /** Reads the script file again. Returns false when no model is applied. */
    public static boolean reload() {
        if (scriptedModel == null) return false;
        scriptedModel.skeleton().clearAdjusts();
        scriptedModel.gpu().showAllParts();
        scriptedModel.setSpeed(1f);
        loadScript();
        return true;
    }

    private static void loadScript() {
        script = null;
        Path file = findScriptFile();
        if (file == null) {
            String base = baseName(modelFile);
            LOGGER.info("[gpumodels] no script for {} (put {}.lua next to the model or in '{}')", base, base, SCRIPT_FOLDER);
            return;
        }
        try {
            String source = Files.readString(file);
            LuaScript s = new LuaScript(scriptedModel, Scripting::say);
            script = s;
            s.load(source, file.getFileName().toString());
            say("Loaded script " + file.getFileName());
        } catch (IOException e) {
            say("Could not read " + file.getFileName() + ": " + e.getMessage());
        } catch (Throwable t) { // Throwable on purpose: also reports a missing Lua library
            script = null;
            LOGGER.error("[gpumodels] could not start the Lua script", t);
            say("Could not start the script: " + t);
        }
    }

    private static Path findScriptFile() {
        String base = baseName(modelFile);
        Path inFolder = modelsDir.resolve(SCRIPT_FOLDER).resolve(base + ".lua");
        if (Files.isRegularFile(inFolder)) return inFolder;
        Path next = modelFile.getParent() != null ? modelFile.getParent().resolve(base + ".lua") : null;
        if (next != null && Files.isRegularFile(next)) return next;
        return null;
    }

    private static String baseName(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Shows a message in chat (and in the log). */
    public static void say(String message) {
        LOGGER.info("[gpumodels][lua] {}", message);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal("[Lua] " + message), false);
    }

    // =========================== every tick ===========================

    public static void onClientTick(ClientTickEvent.Post event) {
        if (script != null) script.tick();
    }

    // =========================== every frame ===========================

    /**
     * Runs the script's render(), applies the bone settings to the skeleton, and chooses the animation.
     * Returns the animation index to play (-1 = rest pose), or null when the script has no opinion.
     */
    public static Integer update(LoadedModel model, LocalPlayer player, float delta, float yawOffsetDeg, float playerScale) {
        return update(model, player, delta, yawOffsetDeg, playerScale, false);
    }

    /** gui = true when the model is drawn in a screen (inventory preview): the player's movement is not measured then. */
    public static Integer update(LoadedModel model, LocalPlayer player, float delta, float yawOffsetDeg, float playerScale,
                                 boolean gui) {
        Skeleton skeleton = model.skeleton();
        skeleton.clearAdjusts();
        if (script == null || model != scriptedModel) return null;

        script.render(delta);
        model.setSpeed(script.speed());

        // How the player moved since the last frame (for jiggle physics).
        if (!gui) SpringBone.updateMotion(player, delta, yawOffsetDeg);

        // Script angles are in the "player frame". If the model is turned with the yaw command, convert them into
        // the model's own axes: rotation = Ry(-yaw) * rotation * Ry(yaw).
        float phi = (float) Math.toRadians(yawOffsetDeg);
        Quaternionf toModel = new Quaternionf().rotationY(-phi);
        Quaternionf fromModel = new Quaternionf().rotationY(phi);
        float unitsPerBlock = 1f / (model.unitScale() * playerScale);
        boolean swapXZ = Math.abs(Math.sin(phi)) > 0.7;

        for (BoneControl c : script.bones()) {
            if (!c.isActive()) continue;

            Quaternionf follow = new Quaternionf();
            Vector3f move = new Vector3f();
            if (c.follow != null && VanillaPose.isValid()) {
                if (c.followRotation) follow.set(VanillaPose.rotation(c.follow));
                if (c.followPosition) move.set(VanillaPose.offset(c.follow));
            }

            Quaternionf extra = new Quaternionf()
                    .rotateZ((float) Math.toRadians(c.rotateDeg.z))
                    .rotateY((float) Math.toRadians(c.rotateDeg.y))
                    .rotateX((float) Math.toRadians(c.rotateDeg.x));
            Quaternionf inPlayerFrame = new Quaternionf(extra).mul(follow);
            move.add(c.move);

            Quaternionf inModelFrame = new Quaternionf(toModel).mul(inPlayerFrame).mul(fromModel);
            toModel.transform(move);
            move.mul(unitsPerBlock);

            Skeleton.Adjust a = skeleton.adjust(c.node);
            a.rotation.set(inModelFrame);
            a.move.set(move);
            a.scale.set(swapXZ ? c.scale.z : c.scale.x, c.scale.y, swapXZ ? c.scale.x : c.scale.z);
            a.spring = c.spring;
            a.active = true;
        }

        return chooseClip(player);
    }

    private static Integer chooseClip(LocalPlayer player) {
        Integer manual = script.manualClip();
        if (manual != null) return manual;
        if (script.stateClips().isEmpty()) return null;

        List<String> order = new ArrayList<>();
        order.add(currentState(player));
        if (isMoving(player)) order.add("walking");
        order.add("idle");
        for (String state : order) {
            Integer clip = script.stateClips().get(state);
            if (clip != null) return clip;
        }
        return -1; // nothing is bound for this state: show the rest pose
    }

    // =========================== first-person arm ===========================

    /** Arm bone (node index) the script wants drawn in first person for this side, or -1. */
    public static int firstPersonNode(boolean right) {
        LuaScript s = script;
        return s == null ? -1 : s.firstPersonNode(right);
    }

    public static float firstPersonScale() {
        LuaScript s = script;
        return s == null ? 1f : s.firstPersonScale();
    }

    public static Vector3f firstPersonOffset() {
        LuaScript s = script;
        return s == null ? new Vector3f() : s.firstPersonOffset();
    }

    // =========================== for the GUI ===========================

    /** Is there a script file for this model file (in the 'gpu script' folder or next to the model)? */
    public static boolean scriptExists(String modelFileName) {
        Path dir = ModelRenderer.modelDir();
        Path model = ModelRenderer.resolveModelFile(modelFileName);
        if (model == null) return false;
        String base = baseName(model);
        if (Files.isRegularFile(dir.resolve(SCRIPT_FOLDER).resolve(base + ".lua"))) return true;
        Path parent = model.getParent();
        return parent != null && Files.isRegularFile(parent.resolve(base + ".lua"));
    }

    // =========================== vanilla visibility ===========================

    /**
     * Should this part of vanilla Minecraft still be drawn? part = body, armor, elytra or items.
     * Without a script: only the vanilla body is hidden (the model replaces it).
     */
    public static boolean vanillaVisible(String part) {
        LuaScript s = script;
        if (s != null) return s.vanillaVisible(part);
        return !part.equals("body");
    }

    // =========================== player state ===========================

    public static boolean isMoving(LocalPlayer p) {
        if (p == null) return false;
        return p.getDeltaMovement().horizontalDistanceSqr() > 4.0E-4;
    }

    /** One of LuaScript.STATES. The most specific state that applies right now. */
    public static String currentState(LocalPlayer p) {
        if (p == null) return "idle";
        Vec3 v = p.getDeltaMovement();
        if (p.isSleeping()) return "sleeping";
        if (p.isPassenger()) return "riding";
        if (p.isFallFlying() || p.getAbilities().flying) return "flying";
        if (p.isSwimming() || (p.isInWater() && !p.onGround())) return "swimming";
        if (!p.onGround()) return v.y > 0 ? "jumping" : "falling";
        if (p.isCrouching()) return "sneaking";
        if (p.isSprinting()) return "sprinting";
        if (isMoving(p)) return "walking";
        return "idle";
    }
}
