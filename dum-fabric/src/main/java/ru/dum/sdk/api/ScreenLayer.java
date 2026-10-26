package ru.dum.sdk.api;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/** Extra drawing and clicks on game screens. Called for every screen; check the screen type yourself. */
public interface ScreenLayer {

    /** Drawn right after the screen's background: under its slots, the carried item and tooltips. */
    void extract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta);

    /** Return true to consume the click: the screen does not see it, nor the matching release. */
    default boolean mouseClicked(Screen screen, MouseButtonEvent event) {
        return false;
    }
}
