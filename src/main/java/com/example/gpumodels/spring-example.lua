-- Your existing rig (unchanged)
bone("Hip"):followVanilla("BODY", "rotation")
bone("Head"):followVanilla("HEAD", "rotation")
bone("Leg_Left"):followVanilla("LEFT_LEG", "rotation")
bone("Leg_Right"):followVanilla("RIGHT_LEG", "rotation")
bone("Arm_Left"):followVanilla("LEFT_ARM", "rotation")
bone("Arm_Right"):followVanilla("RIGHT_ARM", "rotation")

-- Jiggle bones. Replace the names with your own bones (run listBones() to see them).
-- A chain like Hair_1 -> Hair_2 -> Hair_3 needs one spring call per bone; the lower ones follow their parent.
-- stiffness: 0 = floppy ... 1 = rigid      damping: 0 = bounces long ... 1 = settles fast
-- gravity:   0 = none ... 1 = a sideways bone sags ~30 degrees      inertia: how strongly movement pushes it
-- limit:     max swing in degrees
bone("Hair_1"):spring({ stiffness = 0.20, damping = 0.85, gravity = 1.0, inertia = 1.0, limit = 35 })
bone("Hair_2"):spring({ stiffness = 0.15, damping = 0.80, gravity = 1.0, inertia = 1.2, limit = 45 })
bone("Tail"):spring({ stiffness = 0.10, damping = 0.75, gravity = 1.5, inertia = 1.5, limit = 60 })

-- bone("Tail"):stopSpring()   -- turns the jiggle off again

function tick()
    if player.isSneaking() then
        bone("Leg_Left"):rotate(-40, 0, 0)
        bone("Arm_Left"):rotate(-40, 0, 0)
        bone("Arm_Right"):rotate(-40, 0, 0)
        bone("Leg_Right"):rotate(-40, 0, 0)
    else
        bone("Arm_Left"):rotate(0, 0, 0)
        bone("Arm_Right"):rotate(0, 0, 0)
        bone("Leg_Left"):rotate(0, 0, 0)
        bone("Leg_Right"):rotate(0, 0, 0)
    end
end
