package com.example.gpumodels;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;

/**
 * Safety net: counts every Lua instruction and stops the script when it has used up its budget.
 * Without this, a script like "while true do end" would freeze the whole game.
 * (If this file ever causes trouble, you can delete it and remove the "globals.load(limiter)" line in LuaScript.)
 */
public final class ScriptLimiter extends DebugLib {
    private int remaining = Integer.MAX_VALUE;

    /** Gives the script a fresh budget. Called before every script run. */
    public void reset(int budget) {
        remaining = budget;
    }

    @Override
    public void onInstruction(int pc, Varargs v, int top) {
        if (--remaining < 0) {
            remaining = Integer.MAX_VALUE;
            throw new LuaError("the script ran for too long (an endless loop?)");
        }
        super.onInstruction(pc, v, top);
    }
}
