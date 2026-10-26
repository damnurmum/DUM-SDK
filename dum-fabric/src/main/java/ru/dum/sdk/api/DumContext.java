package ru.dum.sdk.api;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import org.slf4j.Logger;

import java.nio.file.Path;

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
}
