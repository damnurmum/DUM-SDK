package ru.dum.sdk.paper;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.Nullable;
import ru.dum.core.api.module.ModuleHost;
import ru.dum.core.api.module.ModuleManager;
import ru.dum.core.config.Messages;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * DUM-SDK server side: talks to the DUM-SDK client mod over dum:sdk and carries data between client modules
 * and their server sides. Every feature is a module with its own config section.
 */
public final class DumSdk extends JavaPlugin implements ModuleHost, PluginMessageListener {

    private final Map<String, BiConsumer<Player, byte[]>> moduleHandlers = new ConcurrentHashMap<>();
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
                BiConsumer<Player, byte[]> handler = moduleHandlers.get(module);
                if (handler != null) handler.accept(player, data);
            }
        } catch (IllegalArgumentException e) {
            getSLF4JLogger().warn("Malformed message from {}: {}", player.getName(), e.getMessage());
        }
    }

    /** The server side of a client module: data its clients send, on the main thread. Null removes the handler. */
    void onModuleMessage(String module, @Nullable BiConsumer<Player, byte[]> handler) {
        if (handler == null) {
            moduleHandlers.remove(module);
        } else {
            moduleHandlers.put(module, handler);
        }
    }

    /**
     * Works from PlayerJoinEvent on: the client registers dum:sdk during configuration and Paper carries
     * the registration over to the play phase (so PlayerRegisterChannelEvent does not fire for it then).
     */
    void sendToModule(Player player, String module, byte[] data) {
        player.sendPluginMessage(this, Protocol.CHANNEL, Protocol.encode(new Message.Module(module, data)));
    }
}
