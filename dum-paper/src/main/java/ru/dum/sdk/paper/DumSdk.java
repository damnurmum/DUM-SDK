package ru.dum.sdk.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
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
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;
import ru.dum.sdk.paper.api.DumSdkApi;
import ru.dum.sdk.paper.api.ClientModuleFailureEvent;
import ru.dum.sdk.paper.api.ClientModulesLoadedEvent;
import ru.dum.sdk.network.ErrorCode;

import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * DUM-SDK server side: talks to the DUM-SDK client mod over dum:sdk and carries data between client modules
 * and their server sides. Uses only the Paper API and SDK code.
 */
public final class DumSdk extends JavaPlugin implements PluginMessageListener, DumSdkApi, Listener {

    private record Handler(Plugin owner, BiConsumer<Player, byte[]> handler) {
    }

    private final Map<String, Handler> moduleHandlers = new ConcurrentHashMap<>();
    /** Players being disconnected for a malformed message; main thread only. */
    private final Set<UUID> malformedKicks = new HashSet<>();
    private Messages messages;
    private Handshake handshake;
    private final ModuleCatalog catalog = new ModuleCatalog();
    private final ModuleAccess access = new ModuleAccess();
    private ModulePublication publication;
    private final LiveUpdates updates = new LiveUpdates(this);
    /** The Fabric mods each player's client said it runs, as "id version", for the log and failure reports. */
    private final Map<UUID, List<String>> clientMods = new ConcurrentHashMap<>();
    private volatile long catalogEpoch;
    private volatile boolean reloadInProgress;
    /** The published catalog could not be loaded: every join is refused until a publication succeeds. */
    private volatile boolean unavailable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(getDataPath().resolve("messages.yml"), () -> getResource("messages.yml"));

        Messenger messenger = getServer().getMessenger();
        messenger.registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
        messenger.registerOutgoingPluginChannel(this, Protocol.CHANNEL);

        getServer().getServicesManager().register(DumSdkApi.class, this, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        publication = new ModulePublication(getDataPath());
        publication.allowDevSigned(getConfig().getBoolean("allow-dev-signed", false));
        try {
            messages.load();
            publication.load(catalog, getSLF4JLogger());
        } catch (IOException | RuntimeException e) {
            // A disabled plugin would let players in without the client mod and its modules, so stay closed instead
            unavailable = true;
            getSLF4JLogger().error("Cannot load SDK messages or published modules. Every join is refused until "
                    + "the staged jars are fixed and /dumsdk reload succeeds", e);
        }
        handshake = new Handshake(this);
        handshake.enable();
        if (!unavailable) restoreOnlineGrants();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(Commands.literal("dumsdk")
                .requires(source -> source.getSender().hasPermission("dumsdk.admin"))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(messages.get("core.info",
                            Placeholder.unparsed("version", getPluginMeta().getVersion()),
                            Placeholder.unparsed("protocol", String.valueOf(Protocol.VERSION)),
                            Placeholder.unparsed("modules", String.join(", ", catalog.modules().stream().map(m -> m.id() + " " + m.version()).toList()))));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload")
                        .executes(ctx -> reload(ctx.getSource().getSender(), List.of()))
                        .then(Commands.argument("modules", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemaining();
                                    var next = builder.createOffset(builder.getStart() + input.lastIndexOf(' ') + 1);
                                    var specified = Arrays.asList(input.split("\\s+"));
                                    catalog.modules().stream().map(Message.ModuleInfo::id).sorted()
                                            .filter(id -> !specified.contains(id) && id.startsWith(next.getRemaining()))
                                            .forEach(next::suggest);
                                    return next.buildFuture();
                                })
                                .executes(ctx -> reload(ctx.getSource().getSender(),
                                        Arrays.stream(StringArgumentType.getString(ctx, "modules").strip().split("\\s+")).distinct().toList()))))
                .build(), "Управление DUM-SDK", List.of()));
    }

    private int reload(CommandSender sender, List<String> selected) {
        if (reloadInProgress) {
            sender.sendMessage(messages.get("core.publish-busy"));
            return 0;
        }
        reloadInProgress = true;
        sender.sendMessage(messages.get("core.publishing"));
        reloadConfig();
        publication.allowDevSigned(getConfig().getBoolean("allow-dev-signed", false));
        // Jar copying, signatures and the persistent snapshot are prepared away from the game thread.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            ModuleCatalog prepared = new ModuleCatalog();
            try {
                if (selected.isEmpty()) publication.publish(prepared, getSLF4JLogger());
                else publication.publishSelected(prepared, getSLF4JLogger(), selected);
            } catch (IOException | RuntimeException e) {
                getSLF4JLogger().error("Cannot publish staged modules {}", selected.isEmpty() ? "(all)" : selected, e);
                if (isEnabled()) getServer().getScheduler().runTask(this, () -> {
                    reloadInProgress = false;
                    sender.sendMessage(messages.get(selected.isEmpty() ? "core.publish-failed" : "core.publish-selected-failed",
                            Placeholder.unparsed("modules", String.join(", ", selected))));
                });
                return;
            }
            if (isEnabled()) getServer().getScheduler().runTask(this, () -> {
                List<String> rollbacks = rollbacks(catalog.modules(), prepared.modules());
                catalog.replaceWith(prepared);
                catalogEpoch++;
                unavailable = false;
                try {
                    messages.load();
                } catch (IOException e) {
                    getSLF4JLogger().error("Published modules, but could not reload messages", e);
                } finally {
                    handshake.disable();
                    handshake.enable();
                    sender.sendMessage(messages.get(selected.isEmpty() ? "core.updated" : "core.updated-selected",
                            Placeholder.unparsed("count", String.valueOf(catalog.modules().size())),
                            Placeholder.unparsed("modules", String.join(", ", selected))));
                    if (!rollbacks.isEmpty()) {
                        getSLF4JLogger().warn("Published older module versions {}: clients that installed newer ones refuse them", rollbacks);
                        sender.sendMessage(messages.get("core.publish-rollback", Placeholder.unparsed("modules", String.join(", ", rollbacks))));
                    }
                    // Players in game get the new modules without reconnecting
                    updates.published(catalogEpoch);
                    reloadInProgress = false;
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Modules whose newly published version is lower than before, as "id version". */
    static List<String> rollbacks(List<Message.ModuleInfo> before, List<Message.ModuleInfo> after) {
        return after.stream().filter(module -> before.stream().anyMatch(old -> old.id().equals(module.id())
                        && ModuleCatalog.older(module.version(), old.version())))
                .map(module -> module.id() + " " + module.version()).sorted().toList();
    }

    /**
     * A plugin reload (plugman) keeps players online. They joined or were updated in game with this persistent
     * published catalog, so their grants are restored.
     */
    private void restoreOnlineGrants() {
        int restored = 0;
        for (Player player : getServer().getOnlinePlayers()) {
            if (!player.getListeningPluginChannels().contains(Protocol.CHANNEL)) continue;
            access.grantJars(player.getUniqueId(), modulesFor(player.getUniqueId()), catalogEpoch);
            restored++;
        }
        if (restored > 0) {
            getSLF4JLogger().warn("Restored module access of {} online players. Reload plugins that use DumSdkApi too: "
                    + "they still hold the previous DUM-SDK instance", restored);
        }
    }

    @Override
    public void onDisable() {
        if (publication != null) publication.cancel();
        if (handshake != null) handshake.disable();
        // Plugins still holding this instance after a plugin reload must not reach players through it
        access.clear();
    }

    /** What this player's client said it runs, "-" if unknown. */
    String clientMods(UUID player) {
        List<String> mods = clientMods.get(player);
        return mods == null || mods.isEmpty() ? "-" : String.join(", ", mods);
    }

    void clientMods(UUID player, List<String> mods) {
        clientMods.put(player, List.copyOf(mods));
    }

    Messages messages() {
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

    boolean unavailable() {
        return unavailable;
    }

    /** Configuration phase: only the handshake. Paper calls this for the play phase too, then the Player variant. */
    @Override
    public void onPluginMessageReceived(String channel, PlayerConnection connection, byte[] message) {
        if (reloadInProgress && connection instanceof PlayerConfigurationConnection) {
            connection.disconnect(messages.get("core.reconnect"));
            return;
        }
        if (connection instanceof PlayerConfigurationConnection configuration && handshake != null && handshake.enabled()) {
            handshake.received(configuration, message);
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
            // The SDK client never sends one, and a modified client must not be able to flood the log:
            // packets it already sent keep arriving until the kick completes
            if (!malformedKicks.add(player.getUniqueId())) return;
            getSLF4JLogger().warn("[MALFORMED_MESSAGE] {} sent a malformed message, disconnecting: {}", player.getName(), e.getMessage());
            player.kick(messages.get("runtime.malformed").append(InstallErrors.code(messages, ErrorCode.MALFORMED_MESSAGE)));
            return;
        }
        if (decoded instanceof Message.ModuleFailure failure) {
            if (!access.fail(player.getUniqueId(), failure.module(), catalogEpoch)) return;
            getSLF4JLogger().warn("[MODULE_RUNTIME] {} reports {} failed in {}: {}; mods: {}",
                    player.getName(), failure.module(), failure.where(), failure.detail(), clientMods(player.getUniqueId()));
            try {
                getServer().getPluginManager().callEvent(new ClientModuleFailureEvent(player, failure.module(), failure.where(), failure.detail(),
                        clientMods.getOrDefault(player.getUniqueId(), List.of())));
            } finally {
                player.kick(messages.get("runtime.failed", Placeholder.unparsed("module", failure.module()))
                        .append(InstallErrors.code(messages, ErrorCode.MODULE_RUNTIME)));
            }
            return;
        }
        if (updates.received(player, decoded)) return;
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
        Player player = event.getPlayer();
        if (!access.current(player.getUniqueId(), catalogEpoch)) {
            player.kick(messages.get("core.reconnect"));
            return;
        }
        List<Message.ModuleInfo> jars = access.jars(player.getUniqueId());
        getServer().getPluginManager().callEvent(new ClientModulesLoadedEvent(player, jars.stream().map(Message.ModuleInfo::id).toList()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        access.forget(event.getPlayer().getUniqueId());
        updates.quit(event.getPlayer().getUniqueId());
        clientMods.remove(event.getPlayer().getUniqueId());
        malformedKicks.remove(event.getPlayer().getUniqueId());
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
