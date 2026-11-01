package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import org.junit.jupiter.api.Test;
import ru.dum.sdk.api.HookKey;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HookRegistryTest {
    public interface Label { String label(String value); }
    public interface OtherLabel { String label(String value); }
    private static final HookKey<Label> KEY = new HookKey<>("base:label", 1, Label.class);

    private static Event<Label> event() {
        return EventFactory.createArrayBacked(Label.class, listeners -> value -> {
            for (Label listener : listeners) value = listener.label(value);
            return value;
        });
    }

    private final List<String> failures = new ArrayList<>();
    private final EventBridge bridge = new EventBridge((module, where, error) -> {
        module.failed = true;
        failures.add(module.id());
    });
    private final HookRegistry hooks = new HookRegistry(bridge);
    private final AtomicBoolean active = new AtomicBoolean(false);

    private RunningModule module(String id, boolean cold, Map<String, String> deps, Map<String, Integer> needs) {
        var manifest = new ModuleManifest(id, "1.0.0", 1, "test", deps, cold ? "cold" : "hot", true,
                null, null, null, needs);
        return new RunningModule(manifest, null, null, null, null, null, bridge, hooks, active::get);
    }

    private RunningModule consumer(String id) {
        return module(id, false, Map.of("base", ">=1.0.0"), Map.of(KEY.id(), 1));
    }

    @Test
    void listenersAreScopedToBothProviderAndConsumerAndFreshOnReconnect() {
        Event<Label> event = event();
        RunningModule provider = module("base", true, Map.of(), Map.of());
        RunningModule consumer = consumer("consumer");
        provider.provideHook(KEY, event);
        assertNull(hooks.problem(consumer.manifest));
        consumer.onHook(KEY, value -> value + "!");
        assertEquals("vanilla", event.invoker().label("vanilla"));
        active.set(true);
        assertEquals("vanilla!", event.invoker().label("vanilla"));
        provider.failed = true;
        assertEquals("vanilla", event.invoker().label("vanilla"));
        provider.clear();
        consumer.clear();
        assertThrows(IllegalStateException.class, () -> provider.provideHook(KEY, event));
        assertThrows(IllegalStateException.class, () -> consumer.onHook(KEY, value -> value));
        assertNotNull(hooks.problem(consumer.manifest));
        var freshProvider = module("base", true, Map.of(), Map.of());
        var freshConsumer = consumer("consumer");
        freshProvider.provideHook(KEY, event);
        freshConsumer.onHook(KEY, value -> value + "?");
        assertEquals("vanilla?", event.invoker().label("vanilla"));
        active.set(false);
        assertEquals("vanilla", event.invoker().label("vanilla"));
        freshConsumer.clear();
        freshProvider.clear();
    }

    @Test
    void missingOrWrongContractsAndUndeclaredDependenciesAreRejectedBeforeSubscription() {
        var consumer = consumer("consumer");
        assertNotNull(hooks.problem(consumer.manifest));
        var provider = module("base", true, Map.of(), Map.of());
        provider.provideHook(KEY, event());
        assertNotNull(hooks.problem(module("wrong", false, Map.of("base", "*"), Map.of(KEY.id(), 2)).manifest));
        assertNotNull(hooks.problem(module("undeclared", false, Map.of(), Map.of(KEY.id(), 1)).manifest));
        assertThrows(IllegalArgumentException.class, () -> module("none", false, Map.of("base", "*"), Map.of()).onHook(KEY, value -> value));
        assertThrows(IllegalArgumentException.class, () -> consumer.onHook(new HookKey<>(KEY.id(), 1, OtherLabel.class), value -> value));
    }

    @Test
    void onlyInitializingColdProviderCanExportItsOwnNamespaceOnce() {
        assertThrows(IllegalStateException.class, () -> consumer("consumer").provideHook(KEY, event()));
        var provider = module("base", true, Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> provider.provideHook(new HookKey<>("other:label", 1, Label.class), event()));
        provider.provideHook(KEY, event());
        assertThrows(IllegalArgumentException.class, () -> provider.provideHook(KEY, event()));
        provider.finishInitialization();
        assertThrows(IllegalStateException.class, () -> provider.provideHook(new HookKey<>("base:other", 1, Label.class), event()));
        assertThrows(IllegalArgumentException.class, () -> new HookKey<>("not-namespaced", 1, Label.class));
        assertThrows(IllegalArgumentException.class, () -> new HookKey<>(KEY.id(), 0, Label.class));
    }

    @Test
    void hookFailureIsAttributedToConsumerAndCannotRunAgain() {
        active.set(true);
        var provider = module("base", true, Map.of(), Map.of());
        var consumer = consumer("consumer");
        Event<Label> event = event();
        provider.provideHook(KEY, event);
        consumer.onHook(KEY, value -> { throw new IllegalStateException("broken listener"); });
        assertEquals("original", event.invoker().label("original"));
        assertEquals("original", event.invoker().label("original"));
        assertEquals(List.of("consumer"), failures);
        assertTrue(provider.active());
        assertFalse(consumer.active());
        consumer.clear();
        provider.clear();
    }
}
