package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.dum.sdk.api.EquipmentRenderLayer;
import ru.dum.sdk.fabric.module.AppearanceHooks;

@Mixin(WingsLayer.class)
abstract class WingsLayerMixin {

    @Redirect(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;chestEquipment:Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack dumsdk$wings(HumanoidRenderState state) {
        return AppearanceHooks.equipment(EquipmentRenderLayer.WINGS, state.chestEquipment);
    }
}
