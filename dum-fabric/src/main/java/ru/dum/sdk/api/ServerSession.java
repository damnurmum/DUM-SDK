package ru.dum.sdk.api;

import ru.dum.sdk.fabric.DumSdkClient;

/** Allows cooperating cold mods to keep their effects scoped to an accepted DUM-SDK connection. */
public final class ServerSession {

    private ServerSession() { }

    /** False in menus, singleplayer, ordinary servers, configuration and after disconnect. */
    public static boolean active() {
        return DumSdkClient.serverActive();
    }

    /** True only for this Fabric mod in the current verified catalog, while its SDK context remains enabled. */
    public static boolean active(String modId) {
        return DumSdkClient.modActive(modId);
    }
}
