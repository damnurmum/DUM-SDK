package ru.dum.sdk.paper;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.dum.sdk.network.ErrorCode;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;
import ru.dum.sdk.network.Signing;
import ru.dum.sdk.paper.api.ClientModulesLoadedEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A publication reaches players already in the game: each one whose catalog changed gets an UPDATE, their client
 * downloads what it lacks, swaps the changed modules without reconnecting and answers UPDATED. Unchanged modules keep
 * talking to their server sides meanwhile. Changed Fabric mods still need a game restart, a failed update a rejoin.
 * Main thread only.
 */
final class LiveUpdates {

    private record Pending(List<Message.ModuleInfo> modules, Set<String> offered, Set<String> requested, BukkitTask timeout) {
    }

    private final DumSdk plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    LiveUpdates(DumSdk plugin) {
        this.plugin = plugin;
    }

    /** Right after a publication became the catalog of this epoch. */
    void published(long epoch) {
        int updating = 0;
        int timeout = plugin.getConfig().getInt("handshake.install-timeout-seconds", 120);
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            UUID id = player.getUniqueId();
            Pending previous = pending.remove(id);
            List<Message.ModuleInfo> before = plugin.access().jars(id);
            if (previous != null || before == null) {
                // The client takes one update at a time; a player without a session is being disconnected anyway
                if (previous != null) previous.timeout.cancel();
                plugin.access().forget(id);
                player.kick(plugin.messages().get("core.reconnect"));
                continue;
            }
            List<Message.ModuleInfo> after = plugin.modulesFor(id);
            Set<String> installed = hashes(before);
            if (installed.equals(hashes(after))) {
                plugin.access().grantJars(id, after, epoch);
                continue;
            }
            plugin.access().grantJars(id, after.stream().filter(module -> installed.contains(Signing.hex(module.sha256()))).toList(), epoch);
            player.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(new Message.Update(after)));
            BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> timedOut(player), timeout * 20L);
            pending.put(id, new Pending(after, hashes(after), new HashSet<>(), task));
            updating++;
        }
        if (updating > 0) plugin.getSLF4JLogger().info("Updating the modules of {} players in game", updating);
    }

    /** A play-phase message of an update; false when it is not one. */
    boolean received(Player player, Message message) {
        UUID id = player.getUniqueId();
        switch (message) {
            case Message.Request request -> {
                Pending update = pending.get(id);
                if (update == null) return true;
                String sha = Signing.hex(request.sha256());
                byte[] jar = plugin.catalog().jar(request.sha256());
                if (!update.offered.contains(sha) || !update.requested.add(sha) || jar == null) {
                    failed(player, new Message.Ready(ErrorCode.UNEXPECTED_REQUEST, "", "Repeated request or unoffered jar"));
                    return true;
                }
                for (int offset = 0; offset < jar.length; offset += Protocol.CHUNK_SIZE) {
                    byte[] data = Arrays.copyOfRange(jar, offset, Math.min(jar.length, offset + Protocol.CHUNK_SIZE));
                    player.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(new Message.Chunk(request.sha256(), offset, data)));
                }
            }
            case Message.Updated updated -> {
                Pending update = pending.remove(id);
                if (update == null) return true;
                update.timeout.cancel();
                plugin.access().grantJars(id, update.modules, plugin.catalogEpoch());
                Set<String> granted = update.modules.stream().map(Message.ModuleInfo::id).collect(Collectors.toSet());
                List<String> started = updated.modules().stream().filter(granted::contains).distinct().toList();
                plugin.getSLF4JLogger().info("{} updated modules in game, started {}", player.getName(), started);
                plugin.getServer().getPluginManager().callEvent(new ClientModulesLoadedEvent(player, started));
            }
            case Message.Ready ready -> {
                if (!pending.containsKey(id)) return true;
                failed(player, ready.code() == ErrorCode.OK ? new Message.Ready(ErrorCode.MALFORMED_MESSAGE, "", "OK instead of UPDATED") : ready);
            }
            case Message.Restart restart -> {
                Pending update = pending.remove(id);
                if (update == null) return true;
                update.timeout.cancel();
                plugin.access().forget(id);
                plugin.getSLF4JLogger().info("{} gets Fabric mods {} changed on restart, disconnecting", player.getName(), restart.mods());
                player.kick(plugin.messages().get("handshake.restart", Placeholder.unparsed("mods", String.join(", ", restart.mods()))));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    void quit(UUID player) {
        Pending update = pending.remove(player);
        if (update != null) update.timeout.cancel();
    }

    private void failed(Player player, Message.Ready ready) {
        Pending update = pending.remove(player.getUniqueId());
        if (update != null) update.timeout.cancel();
        plugin.access().forget(player.getUniqueId());
        plugin.getSLF4JLogger().info("{} could not update modules in game [{}] {}: {}; mods: {}", player.getName(),
                ready.code(), ready.module(), ready.detail(), plugin.clientMods(player.getUniqueId()));
        player.kick(InstallErrors.message(plugin.messages(), ready));
    }

    private void timedOut(Player player) {
        if (pending.remove(player.getUniqueId()) == null) return;
        plugin.access().forget(player.getUniqueId());
        plugin.getSLF4JLogger().info("{} did not update modules in time, disconnecting", player.getName());
        player.kick(plugin.messages().get("handshake.install-timeout").append(InstallErrors.code(plugin.messages(), ErrorCode.INSTALL_TIMEOUT)));
    }

    private static Set<String> hashes(List<Message.ModuleInfo> modules) {
        return modules.stream().map(module -> Signing.hex(module.sha256())).collect(Collectors.toSet());
    }
}
