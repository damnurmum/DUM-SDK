package ru.dum.sdk.paper.api;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Marks vanilla server-owned items for a module's session-local client presentation. */
public final class ModuleItems {

    private static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("dum-sdk:item"));

    private ModuleItems() { }

    /** Clones the carrier, preserving its data. The module registers the matching module:path definition on the client. */
    public static ItemStack create(String module, String path, ItemStack carrier) {
        if (!module.matches("[a-z0-9_-]+") || !path.matches("[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid module item id");
        if (carrier.isEmpty()) throw new IllegalArgumentException("Empty module item carrier");
        ItemStack item = carrier.clone();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, module + ":" + path);
        item.setItemMeta(meta);
        return item;
    }

    /** The server should check this id and its own rules before processing a custom item action. */
    public static @Nullable String id(ItemStack item) {
        return item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING) : null;
    }
}
