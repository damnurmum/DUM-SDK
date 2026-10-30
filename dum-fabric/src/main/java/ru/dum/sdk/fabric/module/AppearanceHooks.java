package ru.dum.sdk.fabric.module;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import ru.dum.sdk.api.EquipmentRenderLayer;

/** The SDK's permanent mixins delegate only to the current session's module registrations. */
public final class AppearanceHooks {

    static volatile @Nullable ModuleManager manager;

    private AppearanceHooks() { }

    public static ItemStack equipment(EquipmentRenderLayer layer, ItemStack equipped) {
        ModuleManager current = manager;
        return current == null ? equipped : current.equipment(layer, equipped);
    }

    public static ItemStack item(ItemStack stack) {
        ModuleManager current = manager;
        return current == null ? stack : current.itemAppearance(stack);
    }

    public static Component name(ItemStack stack, Component original) {
        ModuleManager current = manager;
        return current == null ? original : current.itemName(stack, original);
    }
}
