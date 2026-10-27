package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dum.sdk.fabric.DumSdkClient;

/** Every way of leaving a world ends here, the player's own disconnect included (Fabric's event misses that one). */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void dumsdk$leave(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
        DumSdkClient.disconnected((Minecraft) (Object) this);
    }
}
