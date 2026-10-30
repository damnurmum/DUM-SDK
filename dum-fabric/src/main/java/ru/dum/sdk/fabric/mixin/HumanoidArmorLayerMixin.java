package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.dum.sdk.api.EquipmentRenderLayer;
import ru.dum.sdk.fabric.module.AppearanceHooks;

@Mixin(HumanoidArmorLayer.class)
abstract class HumanoidArmorLayerMixin {

    @ModifyVariable(method = "renderArmorPiece", at = @At("HEAD"), argsOnly = true)
    private ItemStack dumsdk$armor(ItemStack equipped) {
        return AppearanceHooks.equipment(EquipmentRenderLayer.ARMOR, equipped);
    }
}
