package ru.dum.sdk.api;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * What the SDK gives a running module. Everything registered here is removed when the module is turned off.
 * A module may also use any game or Fabric class directly, but then it has to clean up after itself.
 */
public interface DumContext {

    String id();

    Logger logger();

    /** The module's own folder for files that survive restarts; created on first call. */
    Path dataDir();

    /** Runs at the end of every client tick. */
    void onTick(Runnable task);

    /** Drawn after the vanilla HUD. */
    void hud(HudElement element);

    /** Drawing and clicks on game screens. */
    void screen(ScreenLayer layer);

    /** Data the server side of this module sends; called on the render thread. One handler per module. */
    void onServerMessage(Consumer<byte[]> handler);

    /** Sends data to the server side of this module. Dropped when not in a world. */
    void sendToServer(byte[] data);

    /**
     * A key in Controls, category DUM. Its title is the lang key {@code key.<module id>.<name>} from the module's
     * assets. Pressing it in game calls onPress. The player's binding is kept between sessions by the SDK.
     *
     * @param defaultKey a GLFW key code, or -1 for none
     */
    KeyMapping key(String name, int defaultKey, Runnable onPress);

    /**
     * Adds a client-only entity to the current level, seen by this player alone: display entities and the like.
     * It goes away with the level (dimension change, respawn), so check {@link Entity#isRemoved()} and add a new one.
     */
    void addEntity(Entity entity);

    /**
     * Listens to any Fabric API event, such as {@code LevelRenderEvents.BEFORE_GIZMOS} or {@code AttackEntityCallback.EVENT}.
     * Listeners of all modules are combined exactly as Fabric combines them; this module's leave when it stops.
     */
    <T> void on(Event<T> event, T listener);

    /**
     * Every packet of this type (or a subtype) from the server, on the render thread, before the game handles it.
     * Packets of a bundle come one by one. Return false to drop the packet: the game never sees it.
     */
    <P extends Packet<?>> void onReceive(Class<P> type, Predicate<? super P> handler);

    /** Every packet of this type the game sends to the server, on the sending thread (usually the render thread). False drops it. */
    <P extends Packet<?>> void onSend(Class<P> type, Predicate<? super P> handler);
}
