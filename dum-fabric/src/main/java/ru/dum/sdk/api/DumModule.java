package ru.dum.sdk.api;

/**
 * Entry point of a client module, named by "entrypoint" in its dum-module.json.
 * Needs a public no-argument constructor. Both methods run on the render thread.
 */
public interface DumModule {

    /** The module is turned on. Register everything through the context, so the SDK can remove it later. */
    void onEnable(DumContext context);

    /** The module is about to be turned off. Registrations made through the context are removed by the SDK. */
    default void onDisable() {
    }
}
