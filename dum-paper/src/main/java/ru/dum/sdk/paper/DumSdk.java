package ru.dum.sdk.paper;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import ru.dum.core.api.module.ModuleHost;
import ru.dum.core.api.module.ModuleManager;
import ru.dum.core.config.Messages;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;
import ru.dum.sdk.paper.api.DumSdkApi;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * DUM-SDK server side: talks to the DUM-SDK client mod over dum:sdk and carries data between client modules
 * and their server sides. Every feature is a module with its own config section.
 */
public final class DumSdk extends JavaPlugin implements ModuleHost, PluginMessageListener, DumSdkApi, Listener {

    private record Handler(Plugin owner, BiConsumer<Player, byte[]> handler) {
    }

    private final Map<String, Handler> moduleHandlers = new ConcurrentHashMap<>();
    private Messages messages;
    private ModuleManager<DumSdk> modules;
    private final ModuleCatalog catalog = new ModuleCatalog();
    private final ModuleAccess access = new ModuleAccess();
    private ModulePublication publication;
    private volatile long catalogEpoch;
    private volatile boolean reloadInProgress;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();

        Messenger messenger = getServer().getMessenger();
        messenger.registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
        messenger.registerOutgoingPluginChannel(this, Protocol.CHANNEL);

        getServer().getServicesManager().register(DumSdkApi.class, this, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        publication = new ModulePublication(getDataPath());
        try {
            publication.load(catalog, getSLF4JLogger());
        } catch (IOException e) {
            getSLF4JLogger().error("Cannot load published modules", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        modules = new ModuleManager<>(this, List.of(new HandshakeModule(this)));
        modules.enableAll();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(Commands.literal("dumsdk")
                .requires(source -> source.getSender().hasPermission("dumsdk.admin"))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(messages.get("core.info",
                            Placeholder.unparsed("version", getPluginMeta().getVersion()),
                            Placeholder.unparsed("protocol", String.valueOf(Protocol.VERSION)),
                            Placeholder.unparsed("modules", String.join(", ", catalog.modules().stream().map(m -> m.id() + " " + m.version()).toList()))));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload").executes(ctx -> {
                    reloadInProgress = true;
                    boolean published = false;
                    try {
                        int count = publication.publish(catalog, getSLF4JLogger());
                        catalogEpoch++;
                        published = true;
                        reloadConfig();
                        messages.load();
                        modules.reload();
                        ctx.getSource().getSender().sendMessage(messages.get("core.published", Placeholder.unparsed("count", String.valueOf(count))));
                    } catch (IOException e) {
                        getSLF4JLogger().error("Cannot publish staged modules", e);
                        ctx.getSource().getSender().sendMessage(messages.get("core.publish-failed"));
                    } finally {
                        if (published) {
                            for (Player player : List.copyOf(getServer().getOnlinePlayers())) {
                                player.kick(messages.get("core.reconnect"));
                            }
                        }
                        reloadInProgress = false;
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build(), "Управление DUM-SDK", List.of()));
    }

    @Override
    public void onDisable() {
        if (modules != null) modules.disableAll();
    }

    @Override
    public Messages messages() {
        return messages;
    }

    ModuleCatalog catalog() {
        return catalog;
    }

    long catalogEpoch() {
        return catalogEpoch;
    }

    boolean reloadInProgress() {
        return reloadInProgress;
    }

    /** Configuration phase: only the handshake. Paper calls this for the play phase too, then the Player variant. */
    @Override
    public void onPluginMessageReceived(String channel, PlayerConnection connection, byte[] message) {
        if (reloadInProgress && connection instanceof PlayerConfigurationConnection) {
            connection.disconnect(messages.get("core.reconnect"));
            return;
        }
        if (connection instanceof PlayerConfigurationConnection configuration && modules.isEnabled("handshake")) {
            modules.get(HandshakeModule.class).received(configuration, message);
        }
    }

    /**
     * A client module talks to its server side. Treated as possibly forged: only for a module this player was given,
     * at a limited rate, and whatever the handler throws on it stays here.
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        Message decoded;
        try {
            decoded = Protocol.decode(message);
        } catch (IllegalArgumentException e) {
            getSLF4JLogger().warn("Malformed message from {}: {}", player.getName(), e.getMessage());
            return;
        }
        if (!(decoded instanceof Message.Module(String module, byte[] data))) return;
        Handler handler = moduleHandlers.get(module);
        if (handler == null || !access.has(player.getUniqueId(), module, catalogEpoch)) return;
        var limits = getConfig();
        switch (access.take(player.getUniqueId(), module, limits.getDouble("module-messages.per-second", 20),
                limits.getInt("module-messages.burst", 40), limits.getInt("module-messages.disconnect-after", 100), System.nanoTime())) {
            case DROP -> {
                return;
            }
            case DISCONNECT -> {
                getSLF4JLogger().warn("{} flooded module {} with messages, disconnecting", player.getName(), module);
                player.kick(messages.get("limits.flood", Placeholder.unparsed("module", module)));
                return;
            }
            case DELIVER -> {
            }
        }
        try {
            handler.handler.accept(player, data);
        } catch (RuntimeException e) {
            getSLF4JLogger().warn("The server side of module {} ({}) failed on a message from {}: {}",
                    module, handler.owner.getName(), player.getName(), e.toString());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!access.current(event.getPlayer().getUniqueId(), catalogEpoch)) {
            event.getPlayer().kick(messages.get("core.reconnect"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        access.forget(event.getPlayer().getUniqueId());
    }

    ModuleAccess access() {
        return access;
    }

    /** The catalog one player gets: modules listed in ops-only go to operators only. */
    List<Message.ModuleInfo> modulesFor(UUID player) {
        return catalog.modulesFor(getServer().getOfflinePlayer(player).isOp(), getConfig().getStringList("ops-only"));
    }

    @Override
    public boolean hasModule(Player player, String module) {
        return access.has(player.getUniqueId(), module, catalogEpoch);
    }

    @Override
    public void onModuleMessage(Plugin owner, String module, BiConsumer<Player, byte[]> handler) {
        moduleHandlers.put(module, new Handler(owner, handler));
    }

    /** A plugin's handlers go with it. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        moduleHandlers.values().removeIf(handler -> handler.owner == event.getPlugin());
    }

    /**
     * Works from PlayerJoinEvent on: the client registers dum:sdk during configuration and Paper carries
     * the registration over to the play phase (so PlayerRegisterChannelEvent does not fire for it then).
     */
    @Override
    public void sendToModule(Player player, String module, byte[] data) {
        // Nothing goes to a player who was not given the module: an ops-only module's data stays with operators
        if (!access.has(player.getUniqueId(), module, catalogEpoch)) return;
        player.sendPluginMessage(this, Protocol.CHANNEL, Protocol.encode(new Message.Module(module, data)));
    }
}
