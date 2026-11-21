-- EXAMPLE SCRIPT
-- Save a copy of this file with the SAME NAME as your model.
-- Model "untitled.fbx"  ->  script "untitled.lua"
-- Put it next to the model, or inside the "gpu script" folder.
--
-- Lines that start with two dashes (--) are comments. Lua ignores them.
--
-- FIRST: find out what your bones are called.
-- Remove the two dashes in front of the next line, load the model, and read the chat:
-- listBones()

-- ---------------------------------------------------------------
-- 1) Bones that copy the vanilla body parts
--    bone("name") must be the exact bone name from Blender.
-- ---------------------------------------------------------------
bone("Head"):followVanilla("HEAD")            -- head looks where the player looks
bone("RightArm"):followVanilla("RIGHT_ARM")   -- arm swings like the vanilla arm
bone("LeftArm"):followVanilla("LEFT_ARM")
bone("RightLeg"):followVanilla("RIGHT_LEG")   -- legs walk like the vanilla legs
bone("LeftLeg"):followVanilla("LEFT_LEG")

-- Only copy the rotation, not the movement:
-- bone("Head"):followVanilla("HEAD", "rotation")

-- ---------------------------------------------------------------
-- 2) Extra changes on top
-- ---------------------------------------------------------------
-- bone("Head"):rotate(10, 0, 0)    -- tilt the head 10 degrees down
-- bone("Tail"):move(0, 0.1, 0)     -- lift the tail 0.1 blocks
-- bone("Hat"):scale(1.2)           -- make the hat 20% bigger

-- ---------------------------------------------------------------
-- 3) Show and hide parts of the model
--    part("name") is the name of a mesh object in Blender.
-- ---------------------------------------------------------------
-- part("Hat"):hide()
-- part("Hat"):show()

-- ---------------------------------------------------------------
-- 4) Animations
-- ---------------------------------------------------------------
-- animation("walk"):state("walking")   -- play "walk" whenever the player walks
-- animation("idle"):state("idle")      -- play "idle" when standing still
-- animation("wave"):play()             -- play "wave" right now

-- ---------------------------------------------------------------
-- 5) Things that change over time
--    tick() runs 20 times per second.
--    render(delta) runs every single frame.
-- ---------------------------------------------------------------
function tick()
    -- Example: when sneaking, lower the head a little.
    if player.isSneaking() then
        bone("Head"):rotate(15, 0, 0)
    else
        bone("Head"):rotate(0, 0, 0)
    end
end

-- function render(delta)
--     -- Example: a tail that wiggles all the time
--     bone("Tail"):rotate(0, math.sin(time() * 4) * 20, 0)
-- end
