package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.dum.sdk.fabric.module.AppearanceHooks;

@Mixin(ItemModelResolver.class)
abstract class ItemModelResolverMixin {

    @ModifyVariable(method = "appendItemLayers", at = @At("HEAD"), argsOnly = true)
    private ItemStack dumsdk$item(ItemStack stack) {
        return AppearanceHooks.item(stack);
    }
}
