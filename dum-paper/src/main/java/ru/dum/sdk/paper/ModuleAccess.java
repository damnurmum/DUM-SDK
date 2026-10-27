package ru.dum.sdk.paper;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which client modules each player was given, and how fast their client may talk to the server sides of them.
 * The server decides: a client cannot reach the server side of a module it was not given, nor flood one.
 * Grants come from the configuration phase (any thread); {@link #take} runs on the main thread.
 */
final class ModuleAccess {

    enum Verdict { DELIVER, DROP, DISCONNECT }

    /** A token bucket: {@code perSecond} messages on average, up to {@code burst} at once. */
    private static final class Bucket {
        double tokens;
        long last;
        int dropped;
    }

    private final Map<UUID, Set<String>> granted = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Bucket>> buckets = new ConcurrentHashMap<>();

    void grant(UUID player, Collection<String> modules) {
        granted.put(player, Set.copyOf(modules));
        buckets.remove(player);
    }

    void forget(UUID player) {
        granted.remove(player);
        buckets.remove(player);
    }

    boolean has(UUID player, String module) {
        Set<String> modules = granted.get(player);
        return modules != null && modules.contains(module);
    }

    /**
     * Whether one more message from this player to this module goes through.
     *
     * @param disconnectAfter dropped messages in a row after which the player is flooding on purpose
     */
    Verdict take(UUID player, String module, double perSecond, int burst, int disconnectAfter, long nowNanos) {
        Bucket bucket = buckets.computeIfAbsent(player, p -> new ConcurrentHashMap<>()).computeIfAbsent(module, m -> {
            Bucket fresh = new Bucket();
            fresh.tokens = burst;
            fresh.last = nowNanos;
            return fresh;
        });
        bucket.tokens = Math.min(burst, bucket.tokens + (nowNanos - bucket.last) / 1e9 * perSecond);
        bucket.last = nowNanos;
        if (bucket.tokens >= 1) {
            bucket.tokens -= 1;
            bucket.dropped = 0;
            return Verdict.DELIVER;
        }
        return ++bucket.dropped >= disconnectAfter ? Verdict.DISCONNECT : Verdict.DROP;
    }
}
