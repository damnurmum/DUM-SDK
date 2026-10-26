package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.api.DumModule;
import ru.dum.sdk.api.ScreenLayer;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client modules installed from the server ({@link ModuleInstaller}): they are loaded, each in its own class loader,
 * once the configuration phase has them in place, and unloaded when we leave. Fabric events cannot be unregistered,
 * so the SDK registers each hook once and passes it on to the running modules.
 * Everything except {@link #init} runs on the render thread.
 */
public final class ModuleManager {

    /** Bumped when {@link ru.dum.sdk.api} changes incompatibly. Modules declare the one they were built for. */
    public static final int API_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");

    private final Path root;
    /** Exists while modules run. Left behind after a crash, it turns the next launch into safe mode. */
    private final Path activeMarker;
    private final List<RunningModule> running = new CopyOnWriteArrayList<>();
    private final ModulePacks packs = new ModulePacks();
    private final ModuleKeys keys;
    private boolean safeMode;
    /** A module consumed the last mouse press, so the screen must not see its release either. */
    private boolean pressConsumed;

    public ModuleManager(Path root) {
        this.root = root;
        this.activeMarker = root.resolve("modules-active");
        this.keys = new ModuleKeys(root);
    }

    public void init() {
        try {
            Files.createDirectories(root);
            safeMode = Files.deleteIfExists(activeMarker);
        } catch (IOException e) {
            LOG.error("Cannot prepare {}", root, e);
        }
        if (safeMode) LOG.warn("The game did not close cleanly while modules were running: safe mode, modules stay off until restart");

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("dum-sdk", "modules"),
                (graphics, delta) -> dispatch(m -> m.huds, hud -> hud.extractRenderState(graphics, delta)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            dispatch(m -> List.copyOf(m.keys.entrySet()), key -> {
                while (key.getKey().consumeClick()) key.getValue().run();
            });
            dispatch(m -> m.ticks, Runnable::run);
        });
        // Screen events belong to one screen and are recreated on every init
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterBackground(screen).register((s, graphics, mouseX, mouseY, delta) ->
                    dispatch(m -> m.screens, layer -> layer.extract(s, graphics, mouseX, mouseY, delta)));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                pressConsumed = clickConsumed(s, event);
                return !pressConsumed;
            });
            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
                boolean consumed = pressConsumed;
                pressConsumed = false;
                return !consumed;
            });
        });
    }

    public boolean safeMode() {
        return safeMode;
    }

    /**
     * Loads and turns on every module of these jars that can run, then their resources.
     * Completes when the resources are loaded.
     */
    public CompletableFuture<Void> start(List<Path> jars) {
        if (safeMode || !running.isEmpty()) return CompletableFuture.completedFuture(null);
        List<Candidate> order = resolve(scan(jars));
        if (order.isEmpty()) return CompletableFuture.completedFuture(null);
        try {
            Files.writeString(activeMarker, String.join("\n", order.stream().map(c -> c.manifest.id()).toList()));
        } catch (IOException e) {
            LOG.warn("Cannot write {}", activeMarker, e);
        }
        for (Candidate candidate : order) {
            if (candidate.manifest.deps().keySet().stream().allMatch(this::isRunning)) {
                enable(candidate);
            } else {
                LOG.warn("Module {} is not started: a module it depends on failed", candidate.manifest.id());
            }
        }
        Map<String, Path> assets = new LinkedHashMap<>();
        for (RunningModule module : running) {
            if (ModulePacks.hasAssets(module.jar)) assets.put(module.id(), module.jar);
        }
        return packs.set(assets);
    }

    /** Data from the server side of a module; dropped if that module is not running. */
    public void deliver(String id, byte[] data) {
        for (RunningModule module : running) {
            if (!module.id().equals(id)) continue;
            var handler = module.serverHandler;
            if (handler == null) return;
            try {
                handler.accept(data);
            } catch (RuntimeException | LinkageError e) {
                LOG.error("Module {} failed on a server message, turning it off", id, e);
                stop(module);
            }
            return;
        }
    }

    /**
     * Turns off and unloads every running module, dependents first.
     *
     * @param reloadResources drop the modules' resources too; not while the game is closing
     */
    public void stop(boolean reloadResources) {
        for (RunningModule module : running.reversed()) stop(module);
        if (reloadResources) packs.set(Map.of());
        try {
            Files.deleteIfExists(activeMarker);
        } catch (IOException e) {
            LOG.warn("Cannot delete {}", activeMarker, e);
        }
    }

    private boolean isRunning(String id) {
        return running.stream().anyMatch(m -> m.id().equals(id));
    }

    private record Candidate(ModuleManifest manifest, Path jar) {
    }

    private Map<String, Candidate> scan(List<Path> jars) {
        Map<String, Candidate> found = new LinkedHashMap<>();
        for (Path jar : jars) {
            ModuleManifest manifest;
            try {
                manifest = ModuleManifest.read(jar);
            } catch (IOException e) {
                LOG.warn("Skipping {}: {}", jar.getFileName(), e.getMessage());
                continue;
            }
            String problem = found.containsKey(manifest.id()) ? "another jar has the same id"
                    : manifest.apiVersion() != API_VERSION ? "built for API " + manifest.apiVersion() + ", this DUM-SDK has API " + API_VERSION
                    : !manifest.hot() ? "load mode '" + manifest.loadMode() + "' is not supported yet"
                    : null;
            if (problem != null) {
                LOG.warn("Skipping module {} ({}): {}", manifest.id(), jar.getFileName(), problem);
            } else {
                found.put(manifest.id(), new Candidate(manifest, jar));
            }
        }
        return found;
    }

    /** Drops modules with missing or mismatched dependencies and orders the rest dependencies first. */
    private List<Candidate> resolve(Map<String, Candidate> found) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Candidate candidate : List.copyOf(found.values())) {
                String problem = dependencyProblem(candidate.manifest, found);
                if (problem != null) {
                    LOG.warn("Skipping module {}: {}", candidate.manifest.id(), problem);
                    found.remove(candidate.manifest.id());
                    changed = true;
                }
            }
        }
        List<Candidate> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (Candidate candidate : found.values()) visit(candidate, found, visited, order);
        return order;
    }

    // ponytail: a dependency cycle is not reported; its modules start in scan order and may fail in onEnable
    private static void visit(Candidate candidate, Map<String, Candidate> found, Set<String> visited, List<Candidate> order) {
        if (!visited.add(candidate.manifest.id())) return;
        for (String dependency : candidate.manifest.deps().keySet()) visit(found.get(dependency), found, visited, order);
        order.add(candidate);
    }

    private static String dependencyProblem(ModuleManifest manifest, Map<String, Candidate> found) {
        for (Map.Entry<String, String> dependency : manifest.deps().entrySet()) {
            Candidate other = found.get(dependency.getKey());
            if (other == null) return "needs module " + dependency.getKey();
            try {
                if (!VersionPredicate.parse(dependency.getValue()).test(Version.parse(other.manifest.version()))) {
                    return "needs " + dependency.getKey() + " " + dependency.getValue() + ", installed " + other.manifest.version();
                }
            } catch (VersionParsingException e) {
                return "bad version requirement '" + dependency.getValue() + "' for " + dependency.getKey();
            }
        }
        return null;
    }

    private void enable(Candidate candidate) {
        ModuleManifest manifest = candidate.manifest;
        URLClassLoader loader;
        try {
            loader = new URLClassLoader("dum-module-" + manifest.id(), new URL[]{candidate.jar.toUri().toURL()},
                    ModuleManager.class.getClassLoader());
        } catch (IOException e) {
            LOG.error("Cannot open module {}", manifest.id(), e);
            return;
        }
        RunningModule module;
        try {
            DumModule instance = loader.loadClass(manifest.entrypoint()).asSubclass(DumModule.class).getConstructor().newInstance();
            module = new RunningModule(manifest, candidate.jar, loader, instance, root.resolve("data").resolve(manifest.id()), keys);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            LOG.error("Cannot create module {} from {}", manifest.id(), manifest.entrypoint(), e);
            close(loader, manifest.id());
            return;
        }
        running.add(module);
        try {
            module.instance.onEnable(module);
            LOG.info("Module {} {} is on", manifest.id(), manifest.version());
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Module {} failed to start", manifest.id(), e);
            stop(module);
        }
    }

    private void stop(RunningModule module) {
        if (!running.remove(module)) return;
        for (RunningModule other : running) {
            if (other.manifest.deps().containsKey(module.id())) stop(other);
        }
        try {
            module.instance.onDisable();
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Module {} failed while turning off", module.id(), e);
        }
        module.clear();
        close(module.loader, module.id());
        LOG.info("Module {} is off", module.id());
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) checkUnloaded(module.loader, module.id());
    }

    private static void close(URLClassLoader loader, String id) {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.warn("Cannot close the jar of module {}", id, e);
        }
    }

    /** Dev only: a class loader that is never collected means something still holds the module's classes. */
    private static void checkUnloaded(URLClassLoader loader, String id) {
        WeakReference<URLClassLoader> ref = new WeakReference<>(loader);
        Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 10 && ref.get() != null; i++) {
                System.gc();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (ref.get() == null) {
                LOG.info("Module {} is fully unloaded", id);
            } else {
                LOG.warn("Module {} is still in memory after unloading: something holds a reference to its classes", id);
            }
        });
    }

    private boolean clickConsumed(Screen screen, MouseButtonEvent event) {
        for (RunningModule module : running) {
            for (ScreenLayer layer : module.screens) {
                try {
                    if (layer.mouseClicked(screen, event)) return true;
                } catch (RuntimeException | LinkageError e) {
                    LOG.error("Module {} failed, turning it off", module.id(), e);
                    stop(module);
                    break;
                }
            }
        }
        return false;
    }

    /** Calls every registration of every running module; a module that throws is turned off. */
    private <T> void dispatch(Function<RunningModule, List<T>> registrations, Consumer<T> call) {
        for (RunningModule module : running) {
            for (T registration : registrations.apply(module)) {
                try {
                    call.accept(registration);
                } catch (RuntimeException | LinkageError e) {
                    LOG.error("Module {} failed, turning it off", module.id(), e);
                    stop(module);
                    break;
                }
            }
        }
    }
}
