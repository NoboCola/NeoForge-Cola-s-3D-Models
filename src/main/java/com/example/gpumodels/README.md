# GPU Models – Lua scripting guide

This mod puts your own 3D model (FBX, GLB, OBJ ...) on your Minecraft player.
The model is drawn by the **GPU**, so it stays fast even with many polygons.

A **Lua script** lets you decide what the model's bones do.
For example: "the Head bone should copy the vanilla head" or "hide the hat".

The script does not move any vertices itself. It only gives a few simple orders
(like "this bone follows the vanilla arm"). The GPU does the heavy work.


| File | What it does (short) |
|---|---|
| `GpuModels.java` | The mod itself. Registers the commands. |
| `ModelRenderer.java` | Draws the models. Hides the vanilla player body. |
| `ModelLoader.java` | Reads FBX / GLB / OBJ files (bones, animations, materials). |
| `LoadedModel.java` | One loaded model, its textures and its animation state. |
| `GpuModel.java` | Keeps the model in GPU memory. Can show or hide parts. |
| `GpuTexture.java` | Keeps one texture in GPU memory. |
| `Skeleton.java` | Bones and animations. Applies the script's bone changes. |
| `VanillaPart.java` | The six vanilla body parts a bone can copy. |
| `VanillaPose.java` | Reads how the vanilla body moves in every frame. |
| `BoneControl.java` | Remembers what the script wants one bone to do. |
| `LuaScript.java` | The Lua engine and all functions a script can use. |
| `ScriptLimiter.java` | Stops endless loops so the game cannot freeze. |
| `Scripting.java` | Finds the script, runs it, and connects it to your model. |

---

## 2. Folders

Everything lives inside `run/gpumodels/` (in a normal game: `.minecraft/gpumodels/`).

```
gpumodels/
  untitled.fbx          <- your model (any name)
  untitled.lua          <- your script (SAME name as the model)
  gpu texture/
    untitled.png        <- your texture (SAME name as the model)
  gpu script/
    untitled.lua        <- scripts can also live here
```

**Names must match.** The model `goku.fbx` uses the script `goku.lua`
and the texture `goku.png` (or .jpg, .tga, .bmp).

or use glb file to skip png step

Texture rules, in this order:
1. A texture inside the model file is used first.
2. Otherwise a picture named like the model, in `gpu texture/` or next to the model. ( you can skip this step if your Model is in GLB Format )
3. Otherwise the model uses its material colors.

---

## 3. Quick start

1. Put `goku.fbx` into `gpumodels/` or goku.glb.
2. Make a text file `goku.lua` next to it. Start with the example script (`example.lua`).
3. In the game, type: `/gpumodel localplayer model apply goku.fbx <-- fbx is a Format`
4. Press **F5** (third person). Your model is there.
5. Change the script, save it, and type: `/gpumodel localplayer script reload`

Not sure what your bones are called? Type `/gpumodel localplayer model info`.
It shows all bone, part and animation names in chat, and it works without a script.
(You can also write `listBones()` at the top of a script.)

---

## 4. How a script runs

- The whole file runs **once** when the model is applied or the script is reloaded.
  Put your normal orders here, like `bone("Head"):followVanilla("HEAD")`.
- If the script has a function called `tick()`, it runs **20 times per second**.
- If the script has a function called `render(delta)`, it runs **every frame**.
  `delta` is a number from 0 to 1 that says how far we are between two ticks.

```lua
bone("Head"):followVanilla("HEAD")      -- runs once

function tick()                          -- runs 20 times per second
  if player.isSneaking() then
    bone("Head"):rotate(15, 0, 0)
  else
    bone("Head"):rotate(0, 0, 0)
  end
end
```

If the script has an error, the game does not crash.
You get a red message in chat, the script stops, and you can fix it and reload.

---

## 5. Directions and units

All numbers in a script use the **player's own directions**,
so you never need to think about how the model was exported:

| Axis | Meaning |
|---|---|
| x | to the player's **left** |
| y | **up** |
| z | **forward** (where the player's chest faces) |

- Rotations are in **degrees**.
- Movements are in **blocks** (1 block = 1 meter in Blender).
- `rotate(x, y, z)`: x nods the head down when positive, y turns it to the left when positive, z tilts it sideways.

Rotations and scaling always happen around the **bone's own pivot point**
(the place where the bone starts in Blender). Everything attached to the bone moves with it.

---

## 6. Function reference

### 6.1 `bone(name)`

Gets one bone of your model. `name` must be written exactly like in Blender.
If there is no such bone, you get an error. Use `listBones()` to see the names.

Every bone has these functions (write them with a colon, like `bone("Head"):rotate(...)`):

| Function | What it does |
|---|---|
| `:followVanilla(part)` | The bone copies the **rotation and position** of a vanilla body part. |
| `:followVanilla(part, "rotation")` | Copy only the rotation. |
| `:followVanilla(part, "position")` | Copy only the position. |
| `:stopFollowing()` | The bone stops copying the vanilla part. |
| `:rotate(x, y, z)` | Adds an extra rotation (degrees). It is added **on top** of the animation and the vanilla copy. Use `rotate(0, 0, 0)` to remove it. |
| `:move(x, y, z)` | Adds an extra movement (blocks). |
| `:scale(s)` | Makes the bone bigger or smaller. `scale(1.5)` = 50% bigger. `scale(0)` makes it vanish. |
| `:scale(x, y, z)` | Scales each direction by itself. |
| `:reset()` | Removes everything the script did to this bone. |

The vanilla parts you can copy (upper or lower case is fine):

| Name | Body part |
|---|---|
| `HEAD` | the head |
| `BODY` | the body |
| `RIGHT_ARM` | the right arm |
| `LEFT_ARM` | the left arm |
| `RIGHT_LEG` | the right leg |
| `LEFT_LEG` | the left leg |

"Follow" means: **how far the vanilla part moved away from standing still**.
Standing still gives no change, so your model's own pose stays the same.
Walking swings the arms and legs, looking up and down turns the head,
and sneaking leans the body.

```lua
bone("Head"):followVanilla("HEAD")
bone("RightArm"):followVanilla("RIGHT_ARM")
bone("Tail"):followVanilla("BODY", "rotation")   -- tail follows how the body leans
```

### 6.2 `part(name)`

Gets one **part** of the model. A part is a mesh object from Blender (for example "Hat").
Use `listParts()` to see the names.

| Function | What it does |
|---|---|
| `:hide()` | Makes the part invisible. |
| `:show()` | Makes the part visible again. |
| `:visible(true or false)` | Shows or hides the part. |

```lua
part("Hat"):hide()

function tick()
  part("Cape"):visible(not player.isSneaking())
end
```

Bones move things. Parts show or hide things. These are two different lists.

### 6.3 `animation(name)`

Gets one animation of your model.
The name can be the full name or just a piece of it. `"walk"` finds `"Armature|walk"`.
Use `listAnimations()` to see the names.

| Function | What it does |
|---|---|
| `:state(state)` | Plays this animation automatically whenever the player is in that state. |
| `:play()` | Plays this animation right now and keeps playing it. |
| `:stop()` | Stops an animation that was started with `:play()`. |

Player states:

| State | When |
|---|---|
| `idle` | standing still |
| `walking` | walking |
| `sprinting` | sprinting |
| `sneaking` | sneaking |
| `jumping` | in the air, going up |
| `falling` | in the air, going down |
| `swimming` | swimming |
| `flying` | flying with elytra or in creative flight |
| `riding` | sitting on a horse, boat or minecart |
| `sleeping` | in a bed |

**No animation plays by itself.** Without a script, or without an `animation(...)` line, the model stands in its
resting pose. Bones that follow the vanilla body (`followVanilla`) still move.

If nothing is set for the current state, the script tries `walking` (when moving) and then `idle`.
If nothing fits at all, the model shows its resting pose.
A running `:play()` always wins over states.

```lua
animation("idle"):state("idle")
animation("walk"):state("walking")
animation("run"):state("sprinting")
animation("wave"):play()
```

### 6.4 `animationSpeed(number)`

Changes how fast animations play. `1` is normal, `2` is twice as fast, `0.5` is half speed.

### 6.5 `player`

Asks the game about the player. Nothing here changes the player.

| Function | Gives back |
|---|---|
| `player.isSneaking()` | true or false |
| `player.isSprinting()` | true or false |
| `player.isOnGround()` | true or false |
| `player.isInWater()` | true or false |
| `player.isMoving()` | true or false |
| `player.getHealth()` | health in half hearts (20 = full) |
| `player.getSpeed()` | walking speed in blocks per second |
| `player.getLookPitch()` | looking up or down in degrees (negative = up) |
| `player.getLookYaw()` | looking direction in degrees |
| `player.getName()` | the player's name |
| `player.getState()` | one of the state names above, like `"walking"` |

### 6.6 Helpers

| Function | What it does |
|---|---|
| `print(...)` | Shows text in chat. Good for finding mistakes. |
| `time()` | Seconds since the script was loaded. Use it for waves: `math.sin(time() * 3)`. |
| `listBones()` | Shows all bone names in chat. |
| `listParts()` | Shows all part names in chat. |
| `listAnimations()` | Shows all animation names in chat. |

Lua's normal tools work too: `math` (like `math.sin`, `math.floor`), `string`, `table`,
`if`, `for`, `while`, and your own functions.

---

## 7. Commands

Both `/gpumodel` and `/gpumodels` work. Press **Tab** after `apply` to list your model files.

| Command | What it does |
|---|---|
| `/gpumodel localplayer model apply <file>` | Puts the model on your player and loads its script. |
| `/gpumodel localplayer model info` | Shows every **bone**, **part** and **animation** name of your model in chat. No script needed. |
| `/gpumodel localplayer model remove` | Brings back the vanilla player model. |
| `/gpumodel localplayer model yaw <degrees>` | Turns the model if it looks the wrong way. Try `180`. |
| `/gpumodel localplayer model scale <number>` | Makes the whole model bigger or smaller. `1` = real size. |
| `/gpumodel localplayer script reload` | Reads the script file again. |
| `/gpumodel place` | Puts the test model where you stand. |
| `/gpumodel load <file>` | Chooses the test model. |
| `/gpumodel reload` | Loads the test model again. |
| `/gpumodel anim list` | Lists the animations of the test model. |
| `/gpumodel anim <number>` | Plays animation number N on the test model. `-1` = resting pose. |

---

## 8. Model size

A model keeps its real size: 1 meter in Blender = 1 block.
FBX files that are clearly in centimeters (bigger than 20 units) are made 100 times smaller automatically.
GLB files are always in meters. GLB is the safest format.

---

## 9. Safety

The script runs in a **sandbox**:
- It cannot read or write files.
- It cannot use the internet.
- It cannot call Java code or run other programs.
- It has a limit on how much work it may do each time.
  An endless loop is stopped with an error message instead of freezing the game.

Very large strings or tables made on purpose can still use a lot of memory.
Only run scripts you trust.

---

## 10. Problems and fixes

| Problem | Fix |
|---|---|
| `there is no bone named ...` | The name is wrong. Use `listBones()` and copy the name exactly (upper/lower case matters). |
| Nothing happens in first person | Press F5. In first person the vanilla body is not drawn, so the model is not drawn either. |
| The model looks backwards or the head turns the wrong way | Use `/gpumodel localplayer model yaw 180`. |
| The bones follow, but the model looks bent or stretched | The bone has no weight on the mesh in Blender. Check the weight paint. |
| `unknown vanilla part` | Use HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG or LEFT_LEG. |
| `Script error ... ran for too long` | A `while` loop never ends. Fix the loop. |
| The script is not found | The file must have the **same name as the model** and end with `.lua`. Check the folder names. |
| Changes do not show | Type `/gpumodel localplayer script reload` after saving the file. |
| Game crashes with `NoClassDefFoundError: org.luaj` | The two Lua lines in `build.gradle` are missing. Add them and click Load Gradle changes. |

---

## 11. What does not work yet

- Only the **local player** (you) gets the custom model. Other players do not see it.
- Vanilla armor, held items, elytra and capes are not drawn while a model is applied. (Custom armor comes next.)
- Arms do not raise for held items, shields or bows yet.
- Animations switch instantly (no smooth blending yet). Only one animation plays at a time.
- No head turning while riding.
- The model uses a fixed light, not Minecraft's light level.
