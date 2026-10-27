package ru.dum.sdk.coldtest.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Proof that a mixin from a cold-loaded mod runs: the window title gets a mark. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {

    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void dumcoldtest$mark(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(cir.getReturnValue() + " + DUM cold load");
    }
}
