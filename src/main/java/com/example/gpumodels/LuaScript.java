package com.example.gpumodels;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One running script. The Lua world is a sandbox: no files, no network, no Java access, and an instruction
 * limit so an endless loop cannot freeze the game. The script can only use the functions installed here.
 *
 * The script file runs once when loaded. If it defines tick() it is called 20 times per second, and if it
 * defines render(delta) it is called every frame.
 */
public final class LuaScript {
    /** How many Lua instructions one run (load, tick or render) may use. */
    private static final int INSTRUCTION_BUDGET = 400_000;

    /** The player states an animation can be bound to. */
    public static final List<String> STATES = List.of(
            "idle", "walking", "sprinting", "sneaking", "jumping", "falling", "swimming", "flying", "riding", "sleeping");

    private interface Fn {
        Varargs call(Varargs args);
    }

    private final LoadedModel model;
    private final Consumer<String> chat;
    private final Globals globals = new Globals();
    private final ScriptLimiter limiter = new ScriptLimiter();
    private final long startNanos = System.nanoTime();

    private final Map<String, BoneControl> bones = new LinkedHashMap<>();
    private final Map<String, Integer> stateClips = new HashMap<>();
    private Integer manualClip;
    private float speed = 1f;

    private LuaValue tickFunction = LuaValue.NIL;
    private LuaValue renderFunction = LuaValue.NIL;
    private boolean failed;

    /** Which parts of vanilla Minecraft are drawn next to the model: vanilla.visible.body = false, ... */
    private static final List<String> VANILLA_PARTS = List.of("body", "armor", "elytra", "items");
    private final Map<String, Boolean> vanillaVisible =
            new HashMap<>(Map.of("body", false, "armor", true, "elytra", true, "items", true));

    /** First person: arm bones (node index, -1 = none) drawn instead of the vanilla hand. */
    private int fpRightNode = -1;
    private int fpLeftNode = -1;
    private float fpScale = 1f;
    private final Vector3f fpOffset = new Vector3f();

    public LuaScript(LoadedModel model, Consumer<String> chat) {
        this.model = model;
        this.chat = chat;

        // Only safe libraries: no io, os, luajava, coroutine. The other libraries register themselves inside
        // "package.loaded", so PackageLib has to be loaded right after BaseLib. Scripts cannot use it:
        // "package" and "require" are removed again below.
        globals.load(new BaseLib());
        globals.load(new PackageLib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        globals.load(new Bit32Lib());
        globals.load(new MathLib());
        globals.load(limiter);
        LoadState.install(globals);
        LuaC.install(globals);
        for (String name : new String[]{"dofile", "loadfile", "load", "loadstring", "collectgarbage",
                "debug", "require", "package", "module"}) {
            globals.set(name, LuaValue.NIL);
        }
        installApi();
    }

    // =========================== running ===========================

    /** Compiles and runs the script file's text. Errors go to chat. */
    public void load(String source, String chunkName) {
        run("loading the script", () -> {
            LuaValue chunk = globals.load(source, chunkName);
            chunk.call();
            tickFunction = globals.get("tick");
            renderFunction = globals.get("render");
        });
    }

    public void tick() {
        if (tickFunction.isfunction()) {
            run("running tick()", () -> tickFunction.call());
        }
    }

    public void render(float delta) {
        if (renderFunction.isfunction()) {
            run("running render()", () -> renderFunction.call(LuaValue.valueOf(delta)));
        }
    }

    private void run(String what, Runnable action) {
        if (failed) return;
        limiter.reset(INSTRUCTION_BUDGET);
        try {
            action.run();
        } catch (LuaError e) {
            fail(what, e.getMessage());
        } catch (RuntimeException e) {
            fail(what, String.valueOf(e));
        }
    }

    private void fail(String what, String message) {
        failed = true;
        chat.accept("Script error while " + what + ": " + message
                + "  (fix the script, then use /gpumodel localplayer script reload)");
    }

    // =========================== what the host reads ===========================

    public Collection<BoneControl> bones() {
        return bones.values();
    }

    /** Animation chosen with animation(name):play(), or null. */
    public Integer manualClip() {
        return manualClip;
    }

    /** Animations bound to player states with animation(name):state(state). */
    public Map<String, Integer> stateClips() {
        return stateClips;
    }

    public float speed() {
        return speed;
    }

    /** Is this vanilla part drawn? part is one of body, armor, elytra, items. */
    public boolean vanillaVisible(String part) {
        return vanillaVisible.getOrDefault(part, true);
    }

    /** Arm bone (node index) drawn in first person for this side, or -1. */
    public int firstPersonNode(boolean right) {
        return right ? fpRightNode : fpLeftNode;
    }

    public float firstPersonScale() {
        return fpScale;
    }

    public Vector3f firstPersonOffset() {
        return fpOffset;
    }

    // =========================== Lua API ===========================

    private static VarArgFunction fn(Fn body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return body.call(args);
            }
        };
    }

    /** For methods called with a colon (bone:rotate(...)): the first argument is the object itself, so skip it. */
    private static VarArgFunction method(Fn body) {
        return fn(args -> body.call(args.subargs(2)));
    }

    private static String joinArgs(Varargs args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= args.narg(); i++) {
            if (i > 1) sb.append(' ');
            sb.append(args.arg(i).tojstring());
        }
        return sb.toString();
    }

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    /** Reads table[key] as a number, or returns the fallback when it is missing. */
    private static float number(LuaValue table, String key, float fallback) {
        LuaValue v = table.get(key);
        return v.isnumber() ? (float) v.todouble() : fallback;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private void installApi() {
        globals.set("print", fn(a -> {
            chat.accept(joinArgs(a));
            return LuaValue.NONE;
        }));
        globals.set("bone", fn(a -> boneHandle(a.checkjstring(1))));
        globals.set("part", fn(a -> partHandle(a.checkjstring(1))));
        globals.set("animation", fn(a -> animationHandle(a.checkjstring(1))));
        globals.set("animationSpeed", fn(a -> {
            speed = (float) Math.max(0.0, a.checkdouble(1));
            return LuaValue.NONE;
        }));
        globals.set("time", fn(a -> LuaValue.valueOf((System.nanoTime() - startNanos) / 1.0e9)));

        globals.set("listBones", fn(a -> {
            chat.accept("Bones: " + String.join(", ", model.skeleton().readableNames()));
            return LuaValue.NONE;
        }));
        globals.set("listParts", fn(a -> {
            chat.accept("Parts: " + String.join(", ", model.gpu().partNames()));
            return LuaValue.NONE;
        }));
        globals.set("listAnimations", fn(a -> {
            chat.accept("Animations: " + String.join(", ", model.skeleton().clipNames()));
            return LuaValue.NONE;
        }));
        globals.set("vanilla", vanillaTable());
        globals.set("firstPerson", firstPersonTable());
        globals.set("player", playerTable());
    }

    // ---- bone(name) ----

    private LuaTable boneHandle(String name) {
        int node = model.skeleton().findNode(name);
        if (node < 0) {
            throw new LuaError("there is no bone named '" + name + "'. Use listBones() to see all bone names.");
        }
        BoneControl c = bones.computeIfAbsent(name, n -> new BoneControl(n, node));

        LuaTable t = new LuaTable();
        t.set("followVanilla", method(a -> {
            String text = a.checkjstring(1);
            VanillaPart part = VanillaPart.parse(text);
            if (part == null) {
                throw new LuaError("'" + text + "' is not a vanilla part. Use HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG or LEFT_LEG.");
            }
            String what = a.optjstring(2, "both").toLowerCase(Locale.ROOT);
            if (!what.equals("both") && !what.equals("rotation") && !what.equals("position")) {
                throw new LuaError("the second value must be \"both\", \"rotation\" or \"position\".");
            }
            c.follow = part;
            c.followRotation = !what.equals("position");
            c.followPosition = !what.equals("rotation");
            return LuaValue.NONE;
        }));
        t.set("stopFollowing", method(a -> {
            c.follow = null;
            return LuaValue.NONE;
        }));
        t.set("rotate", method(a -> {
            c.rotateDeg.set((float) a.optdouble(1, 0), (float) a.optdouble(2, 0), (float) a.optdouble(3, 0));
            return LuaValue.NONE;
        }));
        t.set("move", method(a -> {
            c.move.set((float) a.optdouble(1, 0), (float) a.optdouble(2, 0), (float) a.optdouble(3, 0));
            return LuaValue.NONE;
        }));
        t.set("scale", method(a -> {
            double x = a.checkdouble(1);
            c.scale.set((float) x, (float) a.optdouble(2, x), (float) a.optdouble(3, x));
            return LuaValue.NONE;
        }));
        t.set("spring", method(a -> {
            // bone("Hair"):spring({ stiffness = 0.15, damping = 0.85, gravity = 1, inertia = 1, limit = 45 })
            if (c.spring == null) c.spring = new SpringBone(model.skeleton().tipOffset(c.node));
            LuaValue o = a.arg(1);
            if (o.istable()) {
                SpringBone s = c.spring;
                s.stiffness = clamp(number(o, "stiffness", s.stiffness), 0f, 1f);
                s.damping = clamp(number(o, "damping", s.damping), 0f, 1f);
                s.gravity = clamp(number(o, "gravity", s.gravity), 0f, 10f);
                s.inertia = clamp(number(o, "inertia", s.inertia), 0f, 10f);
                s.limitDeg = clamp(number(o, "limit", s.limitDeg), 0f, 180f);
            }
            return LuaValue.NONE;
        }));
        t.set("stopSpring", method(a -> {
            c.spring = null;
            return LuaValue.NONE;
        }));
        t.set("reset", method(a -> {
            c.reset();
            return LuaValue.NONE;
        }));
        return t;
    }

    // ---- part(name) ----

    private LuaTable partHandle(String name) {
        if (!model.gpu().partNames().contains(name)) {
            throw new LuaError("there is no model part named '" + name + "'. Use listParts() to see all part names.");
        }
        LuaTable t = new LuaTable();
        t.set("show", method(a -> {
            model.gpu().setPartVisible(name, true);
            return LuaValue.NONE;
        }));
        t.set("hide", method(a -> {
            model.gpu().setPartVisible(name, false);
            return LuaValue.NONE;
        }));
        t.set("visible", method(a -> {
            model.gpu().setPartVisible(name, a.toboolean(1));
            return LuaValue.NONE;
        }));
        return t;
    }

    // ---- animation(name) ----

    private int findClip(String wanted) {
        List<String> names = model.skeleton().clipNames();
        String w = wanted.toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).toLowerCase(Locale.ROOT).equals(w)) return i;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).toLowerCase(Locale.ROOT).endsWith("|" + w)) return i;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).toLowerCase(Locale.ROOT).contains(w)) return i;
        }
        throw new LuaError("there is no animation named '" + wanted + "'. Use listAnimations() to see all animation names.");
    }

    private LuaTable animationHandle(String name) {
        int clip = findClip(name);
        LuaTable t = new LuaTable();
        t.set("play", method(a -> {
            manualClip = clip;
            return LuaValue.NONE;
        }));
        t.set("stop", method(a -> {
            if (manualClip != null && manualClip == clip) manualClip = null;
            return LuaValue.NONE;
        }));
        t.set("state", method(a -> {
            String state = a.checkjstring(1).toLowerCase(Locale.ROOT);
            if (!STATES.contains(state)) {
                throw new LuaError("'" + state + "' is not a player state. Use: " + String.join(", ", STATES) + ".");
            }
            stateClips.put(state, clip);
            return LuaValue.NONE;
        }));
        return t;
    }

    // ---- firstPerson ----

    private LuaTable firstPersonTable() {
        LuaTable t = new LuaTable();
        t.set("rightArm", fn(a -> {
            fpRightNode = armNode(a.checkjstring(1));
            return LuaValue.NONE;
        }));
        t.set("leftArm", fn(a -> {
            fpLeftNode = armNode(a.checkjstring(1));
            return LuaValue.NONE;
        }));
        t.set("scale", fn(a -> {
            fpScale = clamp((float) a.checkdouble(1), 0.05f, 20f);
            return LuaValue.NONE;
        }));
        t.set("offset", fn(a -> {
            fpOffset.set((float) a.optdouble(1, 0), (float) a.optdouble(2, 0), (float) a.optdouble(3, 0));
            return LuaValue.NONE;
        }));
        return t;
    }

    private int armNode(String name) {
        int node = model.skeleton().findNode(name);
        if (node < 0) {
            throw new LuaError("there is no bone named '" + name + "'. Use listBones() to see all bone names.");
        }
        return node;
    }

    // ---- vanilla.visible.<part> ----

    private LuaTable vanillaTable() {
        LuaTable t = new LuaTable();
        t.set("visible", visibleTable());
        return t;
    }

    /** vanilla.visible.body = false  /  print(vanilla.visible.armor) */
    private LuaTable visibleTable() {
        LuaTable visible = new LuaTable();
        LuaTable meta = new LuaTable();
        meta.set("__index", fn(a -> LuaValue.valueOf(vanillaVisible(vanillaKey(a.arg(2).tojstring())))));
        meta.set("__newindex", fn(a -> {
            vanillaVisible.put(vanillaKey(a.arg(2).tojstring()), a.arg(3).toboolean());
            return LuaValue.NONE;
        }));
        visible.setmetatable(meta);
        return visible;
    }

    private static String vanillaKey(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("tools") || n.equals("tool") || n.equals("item") || n.equals("held") || n.equals("helditems")) {
            n = "items";
        }
        if (!VANILLA_PARTS.contains(n)) {
            throw new LuaError("'" + name + "' is not a vanilla part. Use body, armor, elytra or items (tools).");
        }
        return n;
    }

    // ---- player ----

    private LuaTable playerTable() {
        LuaTable t = new LuaTable();
        t.set("isSneaking", fn(a -> LuaValue.valueOf(player() != null && player().isCrouching())));
        t.set("isSprinting", fn(a -> LuaValue.valueOf(player() != null && player().isSprinting())));
        t.set("isOnGround", fn(a -> LuaValue.valueOf(player() != null && player().onGround())));
        t.set("isInWater", fn(a -> LuaValue.valueOf(player() != null && player().isInWater())));
        t.set("isMoving", fn(a -> LuaValue.valueOf(Scripting.isMoving(player()))));
        t.set("getHealth", fn(a -> LuaValue.valueOf(player() == null ? 0.0 : player().getHealth())));
        t.set("getSpeed", fn(a -> {
            LocalPlayer p = player();
            if (p == null) return LuaValue.valueOf(0.0);
            Vec3 v = p.getDeltaMovement();
            return LuaValue.valueOf(Math.sqrt(v.x * v.x + v.z * v.z) * 20.0); // blocks per second
        }));
        t.set("getLookPitch", fn(a -> LuaValue.valueOf(player() == null ? 0.0 : player().getXRot())));
        t.set("getLookYaw", fn(a -> LuaValue.valueOf(player() == null ? 0.0 : player().getYRot())));
        t.set("getName", fn(a -> LuaValue.valueOf(player() == null ? "" : player().getName().getString())));
        t.set("getState", fn(a -> LuaValue.valueOf(Scripting.currentState(player()))));
        return t;
    }
}
