package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBridgeTest {

    /** Like Fabric's "allow" events: false from any listener wins, true without listeners. */
    interface Allow {
        boolean allow(String what);
    }

    private static Event<Allow> allowEvent() {
        return EventFactory.createArrayBacked(Allow.class, listeners -> what -> {
            for (Allow listener : listeners) {
                if (!listener.allow(what)) return false;
            }
            return true;
        });
    }

    private static RunningModule module(String id) {
        return new RunningModule(new ModuleManifest(id, "1.0.0", 1, "x", Map.of(), null), null, null, null, null, null, null);
    }

    @Test
    void combinesModulesLikeTheEventItself() {
        Event<Allow> event = allowEvent();
        EventBridge bridge = new EventBridge((module, where, error) -> {
        });
        RunningModule a = module("a");
        RunningModule b = module("b");
        List<String> calls = new ArrayList<>();
        bridge.add(a, event, what -> {
            calls.add("a " + what);
            return true;
        });
        bridge.add(b, event, what -> {
            calls.add("b " + what);
            return !what.equals("deny");
        });

        assertTrue(event.invoker().allow("x"));
        assertFalse(event.invoker().allow("deny"));
        assertEquals(List.of("a x", "b x", "a deny", "b deny"), calls);

        // A stopped module's listeners leave; with none left the event answers as if nobody listened
        bridge.remove(b);
        assertTrue(event.invoker().allow("deny"));
        bridge.remove(a);
        calls.clear();
        assertTrue(event.invoker().allow("deny"));
        assertTrue(calls.isEmpty());
    }

    @Test
    void aThrowingListenerStopsOnlyItsModule() {
        Event<Allow> event = allowEvent();
        List<String> failed = new ArrayList<>();
        EventBridge bridge = new EventBridge((module, where, error) -> {
            failed.add(module.id() + ": " + error.getMessage());
            module.failed = true;
        });
        RunningModule broken = module("broken");
        RunningModule fine = module("fine");
        bridge.add(broken, event, what -> {
            throw new IllegalStateException("boom");
        });
        bridge.add(fine, event, what -> !what.equals("deny"));

        // The broken listener answers like no listener (true), the fine one still decides
        assertTrue(event.invoker().allow("x"));
        assertFalse(event.invoker().allow("deny"));
        assertEquals(List.of("broken: boom"), failed);
        // Once failed, the module gets no more calls
        event.invoker().allow("x");
        assertEquals(1, failed.size());
    }

    @Test
    void otherModsListenersStayUntouched() {
        Event<Allow> event = allowEvent();
        List<String> calls = new ArrayList<>();
        event.register(what -> {
            calls.add("mod " + what);
            return true;
        });
        EventBridge bridge = new EventBridge((module, where, error) -> {
        });
        RunningModule module = module("m");
        bridge.add(module, event, what -> false);
        assertFalse(event.invoker().allow("x"));
        bridge.remove(module);
        assertTrue(event.invoker().allow("y"));
        assertEquals(List.of("mod x", "mod y"), calls);
    }
}
