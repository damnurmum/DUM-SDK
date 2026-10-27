package ru.dum.sdk.paper;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.jspecify.annotations.Nullable;
import ru.dum.core.api.module.Module;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The configuration phase, before the player enters the world: HELLO, WELCOME, the module catalog,
 * the downloads the client asks for, and its READY. No HELLO in time, an incompatible protocol or modules
 * the client could not install mean a disconnect with an explanation.
 */
final class HandshakeModule extends Module<DumSdk> {

    /** What one connecting client has said so far; the client may speak before the configure event waits. */
    private record Pending(CompletableFuture<byte[]> hello, CompletableFuture<Message> ready) {
    }

    // ponytail: an entry stays if a client says HELLO but disconnects before the configure event; tiny, cleared on restart
    private final Map<PlayerConnection, Pending> pending = new ConcurrentHashMap<>();

    HandshakeModule(DumSdk plugin) {
        super(plugin, "handshake");
    }

    @Override
    protected void onDisable() {
        // Players waiting right now are let in: the check is off
        pending.values().forEach(p -> {
            p.hello.cancel(false);
            p.ready.cancel(false);
        });
        pending.clear();
    }

    /** A message in the configuration phase, on the main thread. */
    void received(PlayerConfigurationConnection connection, byte[] data) {
        Pending state = pending(connection);
        Message message;
        try {
            message = Protocol.decode(data);
        } catch (IllegalArgumentException e) {
            // Probably a HELLO from a newer client; accept() reports it
            state.hello.complete(data);
            return;
        }
        switch (message) {
            case Message.Hello hello -> state.hello.complete(data);
            case Message.Request request -> send(connection, request.sha256());
            case Message.Ready ready -> state.ready.complete(ready);
            case Message.Restart restart -> state.ready.complete(restart);
            default -> plugin.getSLF4JLogger().warn("Unexpected {} during configuration", message.getClass().getSimpleName());
        }
    }

    private Pending pending(PlayerConnection connection) {
        return pending.computeIfAbsent(connection, c -> new Pending(new CompletableFuture<>(), new CompletableFuture<>()));
    }

    private void send(PlayerConfigurationConnection connection, byte[] sha256) {
        byte[] jar = plugin.catalog().jar(sha256);
        if (jar == null) return;
        for (int offset = 0; offset < jar.length; offset += Protocol.CHUNK_SIZE) {
            byte[] data = Arrays.copyOfRange(jar, offset, Math.min(jar.length, offset + Protocol.CHUNK_SIZE));
            connection.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(new Message.Chunk(sha256, offset, data)));
        }
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        String name = connection.getProfile().getName();
        Pending state = pending(connection);
        try {
            byte[] hello = state.hello.get(config().getInt("hello-timeout-seconds", 10), TimeUnit.SECONDS);
            String clientVersion = accept(connection, name, hello);
            if (clientVersion == null) return;
            connection.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(new Message.Catalog(plugin.catalog().modules())));
            switch (state.ready.get(config().getInt("install-timeout-seconds", 120), TimeUnit.SECONDS)) {
                case Message.Ready ready when ready.error().isEmpty() ->
                        plugin.getSLF4JLogger().info("{} joined with DUM-SDK {}", name, clientVersion);
                case Message.Ready ready -> {
                    plugin.getSLF4JLogger().info("{} could not install modules ({}), disconnecting", name, ready.error());
                    connection.disconnect(messages().get("handshake.install-failed", Placeholder.unparsed("reason", ready.error())));
                }
                case Message.Restart restart -> {
                    plugin.getSLF4JLogger().info("{} gets Fabric mods {} changed on restart, disconnecting", name, restart.mods());
                    connection.disconnect(messages().get("handshake.restart", Placeholder.unparsed("mods", String.join(", ", restart.mods()))));
                }
                default -> throw new IllegalStateException();
            }
        } catch (TimeoutException e) {
            if (state.hello.isDone()) {
                plugin.getSLF4JLogger().info("{} did not install modules in time, disconnecting", name);
                connection.disconnect(messages().get("handshake.install-timeout"));
            } else {
                plugin.getSLF4JLogger().info("{} has no DUM-SDK, disconnecting", name);
                connection.disconnect(messages().get("handshake.missing"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CancellationException | ExecutionException e) {
            // The module was disabled while waiting
        } finally {
            pending.remove(connection);
        }
    }

    /** Sends WELCOME and returns the client's DUM-SDK version, or disconnects and returns null. */
    private @Nullable String accept(PlayerConfigurationConnection connection, String name, byte[] data) {
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
            return null;
        }
        if (protocol != Protocol.VERSION) {
            plugin.getSLF4JLogger().info("{} has DUM-SDK {} (protocol {}, server {}), disconnecting",
                    name, clientVersion, protocol, Protocol.VERSION);
            String key = protocol < Protocol.VERSION ? "handshake.outdated-client" : "handshake.outdated-server";
            connection.disconnect(outdated(key, clientVersion, serverVersion));
            return null;
        }
        connection.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(new Message.Welcome(Protocol.VERSION, serverVersion)));
        return clientVersion;
    }

    private Component outdated(String key, String client, String server) {
        return messages().get(key, Placeholder.unparsed("client", client), Placeholder.unparsed("server", server));
    }
}
