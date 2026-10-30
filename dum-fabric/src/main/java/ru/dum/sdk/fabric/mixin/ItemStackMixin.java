package ru.dum.sdk.fabric.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.dum.sdk.fabric.module.AppearanceHooks;

@Mixin(ItemStack.class)
abstract class ItemStackMixin {

    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void dumsdk$name(CallbackInfoReturnable<Component> result) {
        result.setReturnValue(AppearanceHooks.name((ItemStack) (Object) this, result.getReturnValue()));
    }
}
