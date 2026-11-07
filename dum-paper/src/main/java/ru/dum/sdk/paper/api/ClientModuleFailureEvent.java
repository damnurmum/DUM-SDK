package ru.dum.sdk.paper.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.List;

/**
 * A granted client reports a runtime failure. Runs on the server thread after SDK grants are revoked,
 * before the reconnect kick. Diagnostic fields are untrusted client reports, not proof of executed code.
 * The event cannot cancel the kick; plugins may log it or clean up their server-side state.
 */
public final class ClientModuleFailureEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String module;
    private final String where;
    private final String detail;
    private final List<String> clientMods;

    /** @param clientMods the Fabric mods the client said it runs, as "id version" */
    public ClientModuleFailureEvent(Player player, String module, String where, String detail, List<String> clientMods) {
        super(player);
        this.module = module;
        this.where = where;
        this.detail = detail;
        this.clientMods = List.copyOf(clientMods);
    }

    public String module() { return module; }
    public String where() { return where; }
    public String detail() { return detail; }
    /** The client's own Fabric mods as "id version", reported in its HELLO; empty if unknown. */
    public List<String> clientMods() { return clientMods; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
