package ru.dum.sdk.paper;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import ru.dum.core.api.module.Module;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Server side of the "info" client module: two accessory slots, one for a compass and one for a clock.
 * The items live in the player's data, not in the inventory, and drop on death like the rest.
 * The client only says which slot was clicked; the result is decided here from the carried item.
 */
final class AccessoriesModule extends Module<DumSdk> {

    private static final String CLIENT_MODULE = "info";
    private static final Material[] SLOT_ITEMS = {Material.COMPASS, Material.CLOCK};

    private final NamespacedKey[] keys = new NamespacedKey[SLOT_ITEMS.length];

    AccessoriesModule(DumSdk plugin) {
        super(plugin, "accessories");
        for (int i = 0; i < keys.length; i++) keys[i] = new NamespacedKey(plugin, "accessory_" + i);
    }

    @Override
    protected void onEnable() {
        plugin.onModuleMessage(plugin, CLIENT_MODULE, (player, data) -> {
            if (isEnabled()) click(player, data);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sendState(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        Player player = event.getPlayer();
        for (int i = 0; i < SLOT_ITEMS.length; i++) {
            ItemStack stack = get(player, i);
            if (stack != null) {
                event.getDrops().add(stack);
                set(player, i, null);
            }
        }
        sendState(player);
    }

    private void click(Player player, byte[] data) {
        if (data.length != 1 || data[0] < 0 || data[0] >= SLOT_ITEMS.length) return;
        // The slots are drawn only next to the survival inventory
        if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        int slot = data[0];
        ItemStack cursor = player.getItemOnCursor();
        ItemStack stored = get(player, slot);
        if (cursor.isEmpty()) {
            if (stored == null) return;
            player.setItemOnCursor(stored);
            set(player, slot, null);
        } else if (cursor.getType() == SLOT_ITEMS[slot]) {
            if (stored == null) {
                set(player, slot, cursor.asOne());
                player.setItemOnCursor(cursor.subtract());
            } else if (cursor.getAmount() == 1) {
                set(player, slot, cursor);
                player.setItemOnCursor(stored);
            } else {
                return;
            }
        } else {
            return;
        }
        sendState(player);
    }

    private @Nullable ItemStack get(Player player, int slot) {
        byte[] bytes = player.getPersistentDataContainer().get(keys[slot], PersistentDataType.BYTE_ARRAY);
        return bytes == null ? null : ItemStack.deserializeBytes(bytes);
    }

    private void set(Player player, int slot, @Nullable ItemStack stack) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (stack == null) {
            data.remove(keys[slot]);
        } else {
            data.set(keys[slot], PersistentDataType.BYTE_ARRAY, stack.serializeAsBytes());
        }
    }

    /** One item id per slot, "" when empty. The client only needs to draw it. */
    private void sendState(Player player) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (int i = 0; i < SLOT_ITEMS.length; i++) {
                ItemStack stack = get(player, i);
                out.writeUTF(stack == null ? "" : stack.getType().getKey().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        plugin.sendToModule(player, CLIENT_MODULE, bytes.toByteArray());
    }
}
