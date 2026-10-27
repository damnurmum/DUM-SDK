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
     */
    void onModuleMessage(Plugin owner, String module, BiConsumer<Player, byte[]> handler);

    /**
     * Sends data to the client module with this id on the player's client. Works from PlayerJoinEvent on;
     * dropped if the module is not running there.
     */
    void sendToModule(Player player, String module, byte[] data);
}
