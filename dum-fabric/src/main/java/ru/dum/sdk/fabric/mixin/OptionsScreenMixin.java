package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.gui.components.Button;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dum.sdk.fabric.DumSdkClient;

/** Append through the same row helper as vanilla and other mods, preserving its next free cell. */
@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin {
    @Inject(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
    private void dumsdk$diagnostics(CallbackInfo ci, @Local GridLayout.RowHelper rows) {
        rows.addChild(Button.builder(Component.literal("Диагностика DUM-SDK"),
                button -> DumSdkClient.openDiagnostics((Screen) (Object) this)).width(150).build());
    }
}
