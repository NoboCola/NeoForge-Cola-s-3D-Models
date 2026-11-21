package com.example.gpumodels;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Adds the "Cola's GPU models" button to the pause menu. */
public final class PauseMenuButton {
    private PauseMenuButton() {}

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof PauseScreen)) return;

        // find the lowest button of the pause menu
        int lowestBottom = -1;
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof AbstractWidget w) {
                lowestBottom = Math.max(lowestBottom, w.getY() + w.getHeight());
            }
        }

        int w = 204;
        int h = 20;
        int x = screen.width / 2 - w / 2;
        int y = lowestBottom + 4;
        if (lowestBottom < 0 || y + h > screen.height - 4) { // no room (or no pause buttons): top-left corner
            x = 6;
            y = 6;
            w = 140;
        }

        Button button = Button.builder(Component.literal("Cola's GPU models"),
                        b -> Minecraft.getInstance().setScreen(new GpuModelsScreen(screen)))
                .bounds(x, y, w, h)
                .build();
        event.addListener(button);
    }
}
