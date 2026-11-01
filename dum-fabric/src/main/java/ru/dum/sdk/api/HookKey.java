package ru.dum.sdk.api;

import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * A named listener contract exported by a server-scoped Fabric mod. Keep this public interface/key in
 * the provider jar; consuming hot modules compile against it without bundling another copy.
 * Version identifies the hook contract, independently of the provider's jar version.
 */
public record HookKey<T>(String id, int version, Class<T> listenerType) {
    public HookKey {
        if (!validId(id) || version < 1) throw new IllegalArgumentException("Invalid hook ID or API version");
        Objects.requireNonNull(listenerType, "listenerType");
        if (!listenerType.isInterface() || !Modifier.isPublic(listenerType.getModifiers())) {
            throw new IllegalArgumentException("Hook listener must be a public interface");
        }
    }

    public String provider() { return id.substring(0, id.indexOf(':')); }

    public static boolean validId(String id) {
        return id != null && id.length() <= 192 && id.matches("[a-z0-9_-]{1,64}:[a-z0-9_./-]+");
    }
}
