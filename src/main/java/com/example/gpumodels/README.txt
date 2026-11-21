Vanilla visibility (body / armor / elytra / items)

Replace in src/main/java/com/example/gpumodels/ :
  ModelRenderer.java, LuaScript.java, Scripting.java
(LuaScript and Scripting already contain the spring bone changes.)

ONE LINE TO ADD in GpuModels.java, right next to the line that registers ModelRenderer::onRenderPlayer
(register it the same way that line is registered, e.g. NeoForge.EVENT_BUS.addListener(...)):

    NeoForge.EVENT_BUS.addListener(ModelRenderer::onRenderPlayerPost);
    NeoForge.EVENT_BUS.addListener(ModelRenderer::onRenderLiving);

Lua (put it at the top of your script):
    vanilla.visible.body   = false   -- vanilla head/torso/arms/legs (+ second layers)
    vanilla.visible.armor  = true    -- helmet, chestplate, leggings, boots
    vanilla.visible.elytra = true
    vanilla.visible.items  = true    -- held items ("tools" also works as a name)

Defaults without a line in the script: body hidden, everything else visible.
