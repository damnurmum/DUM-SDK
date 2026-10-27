package ru.dum.sdk.paper.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.BiConsumer;

/**
 * DUM-SDK for other plugins: the server side of client modules. A plugin that uses it depends on DUM-SDK
 * in its paper-plugin.yml ({@code load: BEFORE}, {@code join-classpath: true}).
 * The data format between a client module and its server side is up to the module.
 */
public interface DumSdkApi {

    static DumSdkApi get() {
        DumSdkApi api = Bukkit.getServicesManager().load(DumSdkApi.class);
        if (api == null) throw new IllegalStateException("DUM-SDK is not enabled");
        return api;
    }

    /**
     * Data that the client module with this id sends, on the main thread. One handler per module id;
     * it is removed when the owner plugin is disabled.
     * <p>
     * Treat every message as possibly forged and decide the result here (see the fair play rules in docs/modules.md).
     * DUM-SDK already drops messages from players who were not given the module, limits how many a player may send
     * (module-messages in its config) and logs what the handler throws instead of letting it out.
     */
    void onModuleMessage(Plugin owner, String module, BiConsumer<Player, byte[]> handler);

    /**
     * Sends data to the client module with this id on the player's client. Works from PlayerJoinEvent on;
     * dropped if the player was not given the module (ops-only) or it is not running there.
     */
    void sendToModule(Player player, String module, byte[] data);

    /** Whether the player joined with this client module (it is in their catalog and was installed). */
    boolean hasModule(Player player, String module);
}
