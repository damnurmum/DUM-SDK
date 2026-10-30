package ru.dum.sdk.paper;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.jspecify.annotations.Nullable;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;
import ru.dum.sdk.network.Signing;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The configuration phase, before the player enters the world: HELLO, WELCOME, the module catalog,
 * the downloads the client asks for, and its READY. No HELLO in time, an incompatible protocol or modules
 * the client could not install mean a disconnect with an explanation.
 */
final class Handshake implements Listener {

    private final DumSdk plugin;
    private volatile boolean enabled;
    private int helloTimeout;
    private int installTimeout;

    /** What one connecting client has said so far; the client may speak before the configure event waits. */
    private static final class Pending {
        final CompletableFuture<byte[]> hello = new CompletableFuture<>();
        final CompletableFuture<Message> ready = new CompletableFuture<>();
        /** Hashes of the jars this player was offered: only these may be downloaded. */
        volatile Set<String> offered = Set.of();
    }

    // ponytail: an entry stays if a client says HELLO but disconnects before the configure event; tiny, cleared on restart
    private final Map<PlayerConnection, Pending> pending = new ConcurrentHashMap<>();

    Handshake(DumSdk plugin) {
        this.plugin = plugin;
    }

    void enable() {
        if (enabled || !plugin.getConfig().getBoolean("handshake.enabled", true)) return;
        helloTimeout = plugin.getConfig().getInt("handshake.hello-timeout-seconds", 10);
        installTimeout = plugin.getConfig().getInt("handshake.install-timeout-seconds", 120);
        enabled = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    boolean enabled() {
        return enabled;
    }

    void disable() {
        enabled = false;
        HandlerList.unregisterAll(this);
        // A connection offered the old catalog must not enter after a reload.
        pending.forEach((connection, p) -> {
            connection.disconnect(plugin.messages().get("core.reconnect"));
            p.hello.cancel(false);
            p.ready.cancel(false);
        });
        pending.clear();
    }

    /** A message in the configuration phase, on the main thread. */
    void received(PlayerConfigurationConnection connection, byte[] data) {
        if (!enabled) return;
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
            case Message.Request request -> {
                // Only what this player was offered: an ops-only module stays on the server for everyone else
                if (state.offered.contains(Signing.hex(request.sha256()))) {
                    send(connection, request.sha256());
                } else {
                    plugin.getSLF4JLogger().warn("{} asked for a module it was not offered", connection.getProfile().getName());
                }
            }
            case Message.Ready ready -> state.ready.complete(ready);
            case Message.Restart restart -> state.ready.complete(restart);
            default -> plugin.getSLF4JLogger().warn("Unexpected {} during configuration", message.getClass().getSimpleName());
        }
    }

    private Pending pending(PlayerConnection connection) {
        return pending.computeIfAbsent(connection, c -> new Pending());
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
        long epoch = plugin.catalogEpoch();
        if (!current(connection, epoch)) return;
        Pending state = pending(connection);
        try {
            byte[] hello = state.hello.get(helloTimeout, TimeUnit.SECONDS);
            if (!current(connection, epoch)) return;
            String clientVersion = accept(connection, name, hello);
            if (clientVersion == null) return;
            if (!current(connection, epoch)) return;
            UUID id = connection.getProfile().getId();
            List<Message.ModuleInfo> modules = plugin.modulesFor(id);
            state.offered = modules.stream().map(module -> Signing.hex(module.sha256())).collect(Collectors.toUnmodifiableSet());
            connection.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(new Message.Catalog(modules)));
            Message response = state.ready.get(installTimeout, TimeUnit.SECONDS);
            if (!current(connection, epoch)) return;
            switch (response) {
                case Message.Ready ready when ready.error().isEmpty() -> {
                    plugin.access().grant(id, modules.stream().map(Message.ModuleInfo::id).toList(), epoch);
                    plugin.getSLF4JLogger().info("{} joined with DUM-SDK {}", name, clientVersion);
                }
                case Message.Ready ready -> {
                    plugin.getSLF4JLogger().info("{} could not install modules ({}), disconnecting", name, ready.error());
                    connection.disconnect(plugin.messages().get("handshake.install-failed", Placeholder.unparsed("reason", ready.error())));
                }
                case Message.Restart restart -> {
                    plugin.getSLF4JLogger().info("{} gets Fabric mods {} changed on restart, disconnecting", name, restart.mods());
                    connection.disconnect(plugin.messages().get("handshake.restart", Placeholder.unparsed("mods", String.join(", ", restart.mods()))));
                }
                default -> throw new IllegalStateException();
            }
        } catch (TimeoutException e) {
            if (state.hello.isDone()) {
                plugin.getSLF4JLogger().info("{} did not install modules in time, disconnecting", name);
                connection.disconnect(plugin.messages().get("handshake.install-timeout"));
            } else {
                plugin.getSLF4JLogger().info("{} has no DUM-SDK, disconnecting", name);
                connection.disconnect(plugin.messages().get("handshake.missing"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CancellationException | ExecutionException e) {
            // The handshake was disabled while waiting
        } finally {
            pending.remove(connection);
        }
    }

    private boolean current(PlayerConfigurationConnection connection, long epoch) {
        if (enabled && !plugin.reloadInProgress() && epoch == plugin.catalogEpoch()) return true;
        connection.disconnect(plugin.messages().get("core.reconnect"));
        return false;
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
        return plugin.messages().get(key, Placeholder.unparsed("client", client), Placeholder.unparsed("server", server));
    }
}
