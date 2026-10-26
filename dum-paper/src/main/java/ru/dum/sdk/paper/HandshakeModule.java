package ru.dum.sdk.paper;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import ru.dum.core.api.module.Module;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HELLO / WELCOME in the configuration phase. The player is held there until the client says HELLO;
 * no HELLO in time or an incompatible protocol means a disconnect before entering the world.
 */
final class HandshakeModule extends Module<DumSdk> {

    // HELLO can arrive before or after the configure event starts waiting, so both sides meet here.
    // ponytail: an entry stays if a client says HELLO but disconnects before the configure event; tiny, cleared on restart
    private final Map<PlayerConnection, CompletableFuture<byte[]>> hellos = new ConcurrentHashMap<>();

    HandshakeModule(DumSdk plugin) {
        super(plugin, "handshake");
    }

    @Override
    protected void onDisable() {
        // Players waiting right now are let in: the check is off
        hellos.values().forEach(hello -> hello.cancel(false));
        hellos.clear();
    }

    /** A message in the configuration phase: the client's HELLO. */
    void received(PlayerConnection connection, byte[] message) {
        hello(connection).complete(message);
    }

    private CompletableFuture<byte[]> hello(PlayerConnection connection) {
        return hellos.computeIfAbsent(connection, c -> new CompletableFuture<>());
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        String name = connection.getProfile().getName();
        try {
            byte[] data = hello(connection).get(config().getInt("hello-timeout-seconds", 10), TimeUnit.SECONDS);
            accept(connection, name, data);
        } catch (TimeoutException e) {
            plugin.getSLF4JLogger().info("{} has no DUM-SDK, disconnecting", name);
            connection.disconnect(messages().get("handshake.missing"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CancellationException | ExecutionException e) {
            // The module was disabled while waiting
        } finally {
            hellos.remove(connection);
        }
    }

    private void accept(PlayerConfigurationConnection connection, String name, byte[] data) {
        String serverVersion = plugin.getPluginMeta().getVersion();
        int protocol;
        String clientVersion;
        try {
            if (!(Protocol.decode(data) instanceof Message.Hello hello)) throw new IllegalArgumentException("not a HELLO");
            protocol = hello.protocol();
            clientVersion = hello.sdkVersion();
        } catch (IllegalArgumentException e) {
            // A HELLO this server cannot read comes from a newer client
            plugin.getSLF4JLogger().info("{} sent an unreadable HELLO ({}), disconnecting", name, e.getMessage());
            connection.disconnect(outdated("handshake.outdated-server", "?", serverVersion));
            return;
        }
        if (protocol != Protocol.VERSION) {
            plugin.getSLF4JLogger().info("{} has DUM-SDK {} (protocol {}, server {}), disconnecting",
                    name, clientVersion, protocol, Protocol.VERSION);
            String key = protocol < Protocol.VERSION ? "handshake.outdated-client" : "handshake.outdated-server";
            connection.disconnect(outdated(key, clientVersion, serverVersion));
            return;
        }
        connection.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(new Message.Welcome(Protocol.VERSION, serverVersion)));
        plugin.getSLF4JLogger().info("{} joined with DUM-SDK {}", name, clientVersion);
    }

    private Component outdated(String key, String client, String server) {
        return messages().get(key, Placeholder.unparsed("client", client), Placeholder.unparsed("server", server));
    }
}
