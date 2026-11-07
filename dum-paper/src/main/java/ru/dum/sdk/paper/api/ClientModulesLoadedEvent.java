package ru.dum.sdk.paper.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.List;

/**
 * Client modules just started on the player's client, with no state yet: once when the player joins (during
 * PlayerJoinEvent, with every module they were given), and again for the modules a publication swapped while they
 * played. Send these modules what they show; {@link DumSdkApi#sendToModule} works from here on. Main thread.
 */
public final class ClientModulesLoadedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final List<String> modules;

    public ClientModulesLoadedEvent(Player player, List<String> modules) {
        super(player);
        this.modules = List.copyOf(modules);
    }

    /** IDs of the modules that started. */
    public List<String> modules() { return modules; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
