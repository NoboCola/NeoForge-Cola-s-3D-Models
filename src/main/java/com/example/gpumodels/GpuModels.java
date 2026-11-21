package com.example.gpumodels;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * Loads models (GLB, glTF, FBX, OBJ, DAE, ...) from <gameDir>/gpumodels/, uploads them to the GPU once,
 * and draws them with our own shader. Bones and animations run on the GPU. Lua scripts steer the bones.
 *
 * Commands (both /gpumodel and /gpumodels work):
 *   /gpumodel localplayer model apply <file>   replaces your player model (third person, F5)
 *   /gpumodel localplayer model remove         back to the vanilla model
 *   /gpumodel localplayer model info           lists the bones, parts and animations of your model
 *   /gpumodel localplayer model yaw <degrees>  turn the model if it faces the wrong way (try 180)
 *   /gpumodel localplayer model scale <factor> extra size multiplier (default 1 = real size from Blender)
 *   /gpumodel localplayer script reload        read the Lua script of your model again
 *   /gpumodel shaders late | normal            how the model is drawn when an Iris shader pack is on
 *   /gpumodel place                            puts the test model where you stand
 *   /gpumodel load <file>                      loads the test model from the gpumodels folder
 *   /gpumodel reload                           reloads the test model
 *   /gpumodel anim list | <n>                  animations of the test model (-1 = rest pose)
 *
 * Remember: the modId in neoforge.mods.toml must be "gpumodels". See README.md for the Lua functions.
 */
@Mod(value = GpuModels.MODID, dist = Dist.CLIENT)
public class GpuModels {
    public static final String MODID = "gpumodels";

    public GpuModels(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(ModelRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(ModelRenderer::onRenderArm);
        NeoForge.EVENT_BUS.addListener(PauseMenuButton::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ModelRenderer::onRenderPlayer);
        NeoForge.EVENT_BUS.addListener(ModelRenderer::onRenderPlayerPost);
        NeoForge.EVENT_BUS.addListener(ModelRenderer::onRenderLiving);
        NeoForge.EVENT_BUS.addListener(Scripting::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Component.literal(text));
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(
                Commands.literal("gpumodel")

                        // ---- local player ----
                        .then(Commands.literal("localplayer")
                                .then(Commands.literal("model")
                                        .then(Commands.literal("apply")
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .suggests((ctx, builder) ->
                                                                SharedSuggestionProvider.suggest(ModelRenderer.listModelFiles(), builder))
                                                        .executes(ctx -> {
                                                            String name = StringArgumentType.getString(ctx, "name").trim();
                                                            if (ModelRenderer.applyToLocalPlayer(name)) {
                                                                say(ctx, "Applying " + name + " to your player. Switch to third person (F5) to see it.");
                                                                return Command.SINGLE_SUCCESS;
                                                            }
                                                            fail(ctx, "No model named '" + name + "' in the gpumodels folder.");
                                                            return 0;
                                                        })))
                                        .then(Commands.literal("info").executes(ctx -> {
                                            for (String line : ModelRenderer.describePlayerModel()) {
                                                say(ctx, line);
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        }))
                                        .then(Commands.literal("remove").executes(ctx -> {
                                            ModelRenderer.removeFromLocalPlayer();
                                            say(ctx, "Vanilla player model restored.");
                                            return Command.SINGLE_SUCCESS;
                                        }))
                                        .then(Commands.literal("yaw")
                                                .then(Commands.argument("degrees", FloatArgumentType.floatArg()).executes(ctx -> {
                                                    float deg = FloatArgumentType.getFloat(ctx, "degrees");
                                                    ModelRenderer.setPlayerYawOffset(deg);
                                                    say(ctx, "Model yaw offset set to " + deg + " degrees.");
                                                    return Command.SINGLE_SUCCESS;
                                                })))
                                        .then(Commands.literal("scale")
                                                .then(Commands.argument("factor", FloatArgumentType.floatArg(0.01f, 100f)).executes(ctx -> {
                                                    float f = FloatArgumentType.getFloat(ctx, "factor");
                                                    ModelRenderer.setPlayerScale(f);
                                                    say(ctx, "Model scale set to " + f + ".");
                                                    return Command.SINGLE_SUCCESS;
                                                }))))
                                .then(Commands.literal("script")
                                        .then(Commands.literal("reload").executes(ctx -> {
                                            if (Scripting.reload()) {
                                                say(ctx, "Script reloaded.");
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            fail(ctx, "No model is applied to your player. Use /gpumodel localplayer model apply <file> first.");
                                            return 0;
                                        }))))

                        // ---- Iris shader packs ----
                        .then(Commands.literal("shaders")
                                .then(Commands.literal("late").executes(ctx -> {
                                    ModelRenderer.setLateWithShaders(true);
                                    say(ctx, "With shader packs: model drawn after the pack (default).");
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(Commands.literal("normal").executes(ctx -> {
                                    ModelRenderer.setLateWithShaders(false);
                                    say(ctx, "With shader packs: model drawn inside the pack's pass.");
                                    return Command.SINGLE_SUCCESS;
                                })))

                        // ---- test model in the world ----
                        .then(Commands.literal("place").executes(ctx -> {
                            var player = Minecraft.getInstance().player;
                            if (player == null) return 0;
                            ModelRenderer.placeAt(player.position());
                            say(ctx, "GPU model placed.");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("reload").executes(ctx -> {
                            ModelRenderer.requestReload();
                            say(ctx, "GPU model will reload.");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("load")
                                .then(Commands.argument("file", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(ModelRenderer.listModelFiles(), builder))
                                        .executes(ctx -> {
                                            String file = StringArgumentType.getString(ctx, "file").trim();
                                            ModelRenderer.load(file);
                                            say(ctx, "Loading " + file + " ...");
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.literal("anim")
                                .then(Commands.literal("list").executes(ctx -> {
                                    List<String> names = ModelRenderer.animationNames();
                                    StringBuilder sb = new StringBuilder();
                                    if (names.isEmpty()) {
                                        sb.append("No animations (is the test model loaded? use /gpumodel place first).");
                                    } else {
                                        for (int i = 0; i < names.size(); i++) {
                                            if (i > 0) sb.append(", ");
                                            sb.append(i).append(" = ").append(names.get(i));
                                        }
                                    }
                                    say(ctx, sb.toString());
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(Commands.argument("index", IntegerArgumentType.integer(-1)).executes(ctx -> {
                                    int index = IntegerArgumentType.getInteger(ctx, "index");
                                    ModelRenderer.playAnimation(index);
                                    say(ctx, index < 0 ? "Showing rest pose." : "Playing animation " + index + ".");
                                    return Command.SINGLE_SUCCESS;
                                })))
        );

        // /gpumodels works exactly like /gpumodel
        event.getDispatcher().register(Commands.literal("gpumodels").redirect(root));
    }
}
