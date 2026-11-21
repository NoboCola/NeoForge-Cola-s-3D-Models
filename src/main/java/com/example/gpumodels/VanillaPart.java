package com.example.gpumodels;

import java.util.Locale;

/** The six body parts of the vanilla player model. A bone in your model can follow any of them. */
public enum VanillaPart {
    HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG;

    /**
     * Understands "HEAD", "head", "right_arm", "RightArm", "right arm" and so on.
     * Returns null when the text is not a vanilla part.
     */
    public static VanillaPart parse(String text) {
        if (text == null) return null;
        String key = text.trim().toUpperCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", "");
        for (VanillaPart part : values()) {
            if (part.name().replace("_", "").equals(key)) return part;
        }
        return null;
    }
}
