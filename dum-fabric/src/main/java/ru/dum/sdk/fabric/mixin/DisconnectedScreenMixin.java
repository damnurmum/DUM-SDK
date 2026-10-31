package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dum.sdk.fabric.update.SdkUpdater;
import ru.dum.sdk.fabric.DumSdkClient;

/** Add a download action only when the disconnect reason points to the SDK releases. */
@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenMixin {
    @Shadow @Final private LinearLayout layout;
    @Shadow @Final private DisconnectionDetails details;
    @Unique private boolean dumsdk$recorded;

    @Inject(method = "init", at = @At("HEAD"))
    private void dumsdk$rememberFailure(CallbackInfo ci) {
        if (!dumsdk$recorded) {
            dumsdk$recorded = true;
            DumSdkClient.recordDisconnect(details.reason());
        }
    }

    // The third child in 26.2 init is the return button. Join the vanilla layout before it is arranged.
    @Inject(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/LinearLayout;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
            ordinal = 2))
    private void dumsdk$downloadButton(CallbackInfo ci) {
        if (!details.reason().getString().contains("github.com/damnurmum/DUM-SDK/releases")) return;
        layout.addChild(Button.builder(Component.literal("Перейти на GitHub"),
                ConfirmLinkScreen.confirmLink((Screen) (Object) this, SdkUpdater.DOWNLOAD_PAGE)).width(200).build());
    }
}
