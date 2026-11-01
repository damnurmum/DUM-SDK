package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.event.Event;
import ru.dum.sdk.api.HookKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Named, versioned cold-provider events for exactly one accepted session. Accessed on the render thread. */
final class HookRegistry {
    private record Export<T>(RunningModule owner, HookKey<T> key, Event<T> event) { }
    private final Map<String, Export<?>> exports = new LinkedHashMap<>();
    private final EventBridge events;
    private final BooleanSupplier renderThread;

    HookRegistry(EventBridge events) {
        this.events = events;
        Thread owner = Thread.currentThread();
        this.renderThread = () -> Thread.currentThread() == owner;
    }

    HookRegistry(EventBridge events, BooleanSupplier renderThread) {
        this.events = events;
        this.renderThread = renderThread;
    }

    <T> void provide(RunningModule owner, HookKey<T> key, Event<T> event) {
        requireThread();
        if (!owner.open()) throw new IllegalStateException("Hook provider is unloaded");
        if (owner.loader != null || !"cold".equals(owner.manifest.loadMode())) {
            throw new IllegalStateException("Only server-scoped Fabric mods may publish hooks");
        }
        if (!key.provider().equals(owner.id())) throw new IllegalArgumentException("Hook namespace must match its provider");
        if (!key.listenerType().isInstance(event.invoker())) throw new IllegalArgumentException("Hook event has another listener type");
        if (exports.putIfAbsent(key.id(), new Export<>(owner, key, event)) != null) {
            throw new IllegalArgumentException("Duplicate hook " + key.id());
        }
    }

    /** Before running consumer code, check every declared contract and its explicit provider dependency. */
    String problem(ModuleManifest consumer) {
        requireThread();
        for (var wanted : consumer.hookRequirements().entrySet()) {
            String provider = wanted.getKey().substring(0, wanted.getKey().indexOf(':'));
            if (!consumer.deps().containsKey(provider)) return "Hook " + wanted.getKey() + " needs a dependency on " + provider;
            Export<?> export = exports.get(wanted.getKey());
            if (export == null || !export.owner.open()) return "Missing hook " + wanted.getKey();
            if (export.key.version() != wanted.getValue()) return "Hook " + wanted.getKey() + " needs API " + wanted.getValue()
                    + ", provided " + export.key.version();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    <T> void subscribe(RunningModule consumer, HookKey<T> key, T listener) {
        requireThread();
        if (!consumer.open()) throw new IllegalStateException("Hook consumer is unloaded");
        if (!Integer.valueOf(key.version()).equals(consumer.manifest.hookRequirements().get(key.id()))) {
            throw new IllegalArgumentException("Undeclared hook requirement " + key.id());
        }
        String problem = problem(consumer.manifest);
        if (problem != null) throw new IllegalStateException(problem);
        Export<?> export = exports.get(key.id());
        if (export.key.listenerType() != key.listenerType() || !key.listenerType().isInstance(listener)) {
            throw new IllegalArgumentException("Hook listener type mismatch: use the provider's shared API classes");
        }
        events.add(consumer, (Event<T>) export.event, listener, export.owner::active);
    }

    void remove(RunningModule owner) {
        requireThread();
        exports.values().removeIf(export -> export.owner == owner);
    }

    private void requireThread() {
        if (!renderThread.getAsBoolean()) throw new IllegalStateException("Register hooks on the render thread");
    }
}
