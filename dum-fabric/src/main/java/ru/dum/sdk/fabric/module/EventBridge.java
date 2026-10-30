package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.event.Event;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Any Fabric API event for modules. Fabric events cannot be unregistered, so the SDK registers one proxy per event
 * and combines the listeners of running modules with the event's own invoker factory: the result is exactly what
 * Fabric would do with them. A module's listeners leave when it stops; a listener that throws stops its module and
 * answers like an event without listeners.
 */
final class EventBridge {

    private final Map<Event<?>, Hub<?>> hubs = new ConcurrentHashMap<>();
    /** Turns off a module whose listener threw: the module, where, the error. */
    interface Failed {
        void fail(RunningModule module, String where, Throwable error);
    }

    private final Failed failed;

    EventBridge(Failed failed) {
        this.failed = failed;
    }

    @SuppressWarnings("unchecked")
    <T> void add(RunningModule module, Event<T> event, T listener) {
        ((Hub<T>) hubs.computeIfAbsent(event, Hub::new)).add(module, listener);
    }

    void remove(RunningModule module) {
        hubs.values().forEach(hub -> hub.remove(module));
    }

    private final class Hub<T> {

        private final Class<T> type;
        private final Function<T[], T> factory;
        /** What the event does with no listeners: the answer of a failed one. */
        private final T neutral;
        private final List<Map.Entry<RunningModule, T>> listeners = new ArrayList<>();
        private volatile T combined;

        @SuppressWarnings("unchecked")
        Hub(Event<?> event) {
            try {
                // ArrayBackedEvent (every EventFactory event): its handler array has the listener type
                Field handlers = event.getClass().getDeclaredField("handlers");
                Field invokerFactory = event.getClass().getDeclaredField("invokerFactory");
                handlers.setAccessible(true);
                invokerFactory.setAccessible(true);
                type = (Class<T>) handlers.get(event).getClass().getComponentType();
                factory = (Function<T[], T>) invokerFactory.get(event);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Not an EventFactory event: " + event.getClass().getName(), e);
            }
            neutral = factory.apply(array(0));
            combined = neutral;
            ((Event<T>) event).register(proxy(type, (method, args) -> forward(method, combined, args)));
        }

        synchronized void add(RunningModule module, T listener) {
            T guarded = proxy(type, (method, args) -> {
                if (!module.active()) return forward(method, neutral, args);
                long start = System.nanoTime();
                try {
                    return method.invoke(listener, args);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException || e.getCause() instanceof LinkageError) {
                        failed.fail(module, "событие " + type.getSimpleName(), e.getCause());
                        return forward(method, neutral, args);
                    }
                    throw e.getCause();
                } finally {
                    module.nanos.addAndGet(System.nanoTime() - start);
                }
            });
            listeners.add(Map.entry(module, guarded));
            rebuild();
        }

        synchronized void remove(RunningModule module) {
            if (listeners.removeIf(entry -> entry.getKey() == module)) rebuild();
        }

        private void rebuild() {
            T[] array = array(listeners.size());
            for (int i = 0; i < array.length; i++) array[i] = listeners.get(i).getValue();
            combined = array.length == 0 ? neutral : factory.apply(array);
        }

        @SuppressWarnings("unchecked")
        private T[] array(int length) {
            return (T[]) Array.newInstance(type, length);
        }
    }

    private interface Call {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    private static Object forward(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "DUM-SDK " + type.getSimpleName();
                };
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            return call.invoke(method, args);
        }));
    }
}
