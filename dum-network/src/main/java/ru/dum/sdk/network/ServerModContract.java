package ru.dum.sdk.network;

import com.google.gson.JsonObject;

/** Shared publication/download check for the cooperative server-scoped Fabric mod contract. */
public final class ServerModContract {

    public static final String ENTRYPOINT = "dum-sdk:server";

    private ServerModContract() { }

    /** Returns the single SDK entrypoint class; ordinary Fabric initializers must not run server effects at boot. */
    public static String entrypoint(JsonObject metadata) {
        try {
            JsonObject entries = metadata.getAsJsonObject("entrypoints");
            if (entries == null || entries.size() != 1 || !entries.has(ENTRYPOINT)) throw new IllegalArgumentException();
            var classes = entries.getAsJsonArray(ENTRYPOINT);
            if (classes.size() != 1 || !classes.get(0).isJsonPrimitive()
                    || !classes.get(0).getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            String name = classes.get(0).getAsString();
            if (name.isBlank()) throw new IllegalArgumentException();
            JsonObject depends = metadata.getAsJsonObject("depends");
            if (depends == null || !depends.has("dum-sdk")) throw new IllegalArgumentException();
            return name;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Fabric mod must depend on dum-sdk and declare exactly one "
                    + ENTRYPOINT + " entrypoint, without startup entrypoints", e);
        }
    }
}
