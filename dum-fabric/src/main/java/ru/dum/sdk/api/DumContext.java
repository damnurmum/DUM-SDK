package ru.dum.sdk.api;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.Consumer;

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
}
