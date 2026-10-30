package ru.dum.sdk.api;

import net.minecraft.world.item.ItemStack;

/** Selects the item a vanilla equipment layer renders. This never changes actual equipped items. */
@FunctionalInterface
public interface EquipmentAppearance {

    /** Return the input unchanged to pass through, EMPTY to hide, or another stack to render. Do not mutate the input. */
    ItemStack resolve(EquipmentRenderLayer layer, ItemStack equipped);
}
