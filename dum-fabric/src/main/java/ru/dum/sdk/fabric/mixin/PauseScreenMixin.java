package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import ru.dum.sdk.fabric.DumSdkClient;

/** Share the online Options row without adding height to the pause menu. */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin {
    @ModifyArg(method = "createPauseMenu", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;", ordinal = 0), index = 0)
    private LayoutElement dumsdk$modifications(LayoutElement original) {
        if (!DumSdkClient.serverActive() || !(original instanceof Button options)) return original;
        options.setWidth(98);
        LinearLayout row = LinearLayout.horizontal().spacing(8);
        row.addChild(options);
        row.addChild(Button.builder(Component.literal("Модификации"),
                button -> DumSdkClient.openModifications((Screen) (Object) this)).width(98).build());
        return row;
    }
}
