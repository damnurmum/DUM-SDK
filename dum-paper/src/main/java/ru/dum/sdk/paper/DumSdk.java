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

import java.util.List;
import java.util.Map;
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

        catalog.load(getDataPath().resolve("modules"), getSLF4JLogger());
        modules = new ModuleManager<>(this, List.of(new HandshakeModule(this), new AccessoriesModule(this)));
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
                    // Players already on the server keep their modules until they rejoin
                    reloadConfig();
                    messages.load();
                    int count = catalog.load(getDataPath().resolve("modules"), getSLF4JLogger());
                    modules.reload();
                    ctx.getSource().getSender().sendMessage(messages.get("core.reloaded", Placeholder.unparsed("count", String.valueOf(count))));
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

    /** Configuration phase: only the handshake. Paper calls this for the play phase too, then the Player variant. */
    @Override
    public void onPluginMessageReceived(String channel, PlayerConnection connection, byte[] message) {
        if (connection instanceof PlayerConfigurationConnection configuration && modules.isEnabled("handshake")) {
            modules.get(HandshakeModule.class).received(configuration, message);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            if (Protocol.decode(message) instanceof Message.Module(String module, byte[] data)) {
                Handler handler = moduleHandlers.get(module);
                if (handler != null) handler.handler.accept(player, data);
            }
        } catch (IllegalArgumentException e) {
            getSLF4JLogger().warn("Malformed message from {}: {}", player.getName(), e.getMessage());
        }
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
        player.sendPluginMessage(this, Protocol.CHANNEL, Protocol.encode(new Message.Module(module, data)));
    }
}
