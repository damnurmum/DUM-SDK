package ru.dum.sdk.api;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** Screen details the game keeps to itself. */
public final class Screens {

    private Screens() {
    }

    /** The window of a container screen (inventory, chest...), without the recipe book. */
    public static ScreenRectangle containerBounds(AbstractContainerScreen<?> screen) {
        return new ScreenRectangle(screen.leftPos, screen.topPos, screen.imageWidth, screen.imageHeight);
    }
}
