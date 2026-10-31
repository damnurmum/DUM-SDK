package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.api.DumModule;
import ru.dum.sdk.api.DumServerMod;
import ru.dum.sdk.fabric.cold.ColdMods;
import ru.dum.sdk.fabric.DumSdkClient;
import ru.dum.sdk.network.ServerModContract;
import ru.dum.sdk.api.ScreenLayer;
import ru.dum.sdk.api.EquipmentRenderLayer;
import ru.dum.sdk.api.ItemDefinition;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Client modules installed from the server ({@link ModuleInstaller}): they are loaded, each in its own class loader,
 * once the configuration phase has them in place, and unloaded when we leave. Fabric events cannot be unregistered,
 * so the SDK registers each hook once and passes it on to the running modules.
 * Cooperative cold Fabric mods share this lifecycle and context; Fabric retains their class loaders.
 * Mutations except packet callbacks run on the render thread; session queries may run on other threads.
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
    private final EventBridge events = new EventBridge(this::fail);
    /** What went wrong with modules this game session, for the diagnostics screen. */
    private final List<Failure> failures = new CopyOnWriteArrayList<>();

    /** A module that was turned off because it threw. */
    public record Failure(String module, String where, String error, LocalTime at) {
    }

    /** A running module and how much time its code takes, in milliseconds per second since it started. */
    public record Status(String id, String version, double millisPerSecond) {
    }

    /** Metadata is retained for failed optional entries until the current server session ends. */
    public record Details(String id, String name, String description, List<String> authors,
                          Map<String, String> dependencies, int apiVersion, boolean required, boolean active, double millisPerSecond) { }

    public List<Details> details() {
        Map<String, Status> statuses = status().stream().collect(java.util.stream.Collectors.toMap(Status::id, s -> s));
        return required.stream().map(manifest -> {
            Status status = statuses.get(manifest.id());
            return new Details(manifest.id(), manifest.displayName(), manifest.descriptionText(), manifest.authorNames(),
                    Map.copyOf(manifest.deps()), manifest.apiVersion(), manifest.isRequired(),
                    active.getAsBoolean() && status != null, status == null ? 0 : status.millisPerSecond());
        }).toList();
    }
    private boolean safeMode;
    /** Every module of the current server, to tell whether a required one has gone. Render thread only. */
    private List<ModuleManifest> required = List.of();
    /** A module consumed the last mouse press, so the screen must not see its release either. */
    private boolean pressConsumed;
    private final BooleanSupplier active;

    public ModuleManager(Path root, BooleanSupplier active) {
        this.root = root;
        this.activeMarker = root.resolve("modules-active");
        this.keys = new ModuleKeys(root);
        this.active = active;
    }

    public void init() {
        try {
            Files.createDirectories(root);
            safeMode = Files.deleteIfExists(activeMarker);
        } catch (IOException e) {
            LOG.error("Cannot prepare {}", root, e);
        }
        if (safeMode) LOG.warn("The game did not close cleanly while modules were running: safe mode, modules stay off until restart");

        PacketHooks.manager = this;
        AppearanceHooks.manager = this;
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("dum-sdk", "modules"),
                (graphics, delta) -> dispatch("HUD", m -> m.huds, hud -> hud.extractRenderState(graphics, delta)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            dispatch("клавиша", m -> List.copyOf(m.keys.entrySet()), key -> {
                while (key.getKey().consumeClick()) key.getValue().run();
            });
            dispatch("тик", m -> m.ticks, Runnable::run);
        });
        // Screen events belong to one screen and are recreated on every init
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterBackground(screen).register((s, graphics, mouseX, mouseY, delta) ->
                    dispatch("экран", m -> m.screens, layer -> layer.extract(s, graphics, mouseX, mouseY, delta)));
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
    public CompletableFuture<Void> start(List<Path> jars, List<ColdMods.Mod> mods) {
        if (!running.isEmpty()) return CompletableFuture.completedFuture(null);
        List<ModuleManifest> all = new ArrayList<>();
        Map<String, Candidate> found = scan(jars, all);
        List<Candidate> order = resolve(found);
        Map<String, Candidate> cold = new LinkedHashMap<>();
        Set<String> coldIds = mods.stream().map(ColdMods.Mod::id).collect(java.util.stream.Collectors.toSet());
        for (ColdMods.Mod mod : mods) {
            Map<String, String> dependencies = new LinkedHashMap<>();
            FabricLoader.getInstance().getModContainer(mod.id()).orElseThrow().getMetadata().getDependencies().stream()
                    .filter(d -> d.getKind() == ModDependency.Kind.DEPENDS && coldIds.contains(d.getModId()))
                    .forEach(d -> dependencies.put(d.getModId(), "*"));
            ModuleManifest manifest = new ModuleManifest(mod.id(), mod.version(), API_VERSION, ServerModContract.ENTRYPOINT,
                    dependencies, "cold", true);
            all.add(manifest);
            cold.put(mod.id(), new Candidate(manifest, mod.jar()));
        }
        List<Candidate> coldOrder = resolve(cold);
        if (all.isEmpty()) return CompletableFuture.completedFuture(null);
        // Playing without the server's modules would be playing by other rules: no join until the game restarts
        if (safeMode) {
            return CompletableFuture.failedFuture(new ModuleStartException(
                    "Игра в прошлый раз закрылась с ошибкой, и модули выключены до перезапуска. Перезапусти игру"));
        }
        try {
            Files.writeString(activeMarker, String.join("\n", all.stream().map(ModuleManifest::id).toList()));
        } catch (IOException e) {
            LOG.warn("Cannot write {}", activeMarker, e);
        }
        for (Candidate candidate : coldOrder) {
            if (candidate.manifest.deps().keySet().stream().allMatch(this::isRunning)) enableMod(candidate);
        }
        for (Candidate candidate : order) {
            if (candidate.manifest.deps().keySet().stream().allMatch(this::isRunning)) {
                enable(candidate);
            } else {
                LOG.warn("Module {} is not started: a module it depends on failed", candidate.manifest.id());
            }
        }
        List<String> missing = missingRequired(all, id -> running.stream().anyMatch(m -> m.id().equals(id) && !m.failed));
        if (!missing.isEmpty()) {
            stop();
            return CompletableFuture.failedFuture(new ModuleStartException(
                    "Не запустились модули " + String.join(", ", missing) + ", без них играть на сервере нельзя. Подробнее в логе игры"));
        }
        required = all;
        Map<String, Path> assets = new LinkedHashMap<>();
        for (RunningModule module : running) {
            if (module.loader != null && ModulePacks.hasAssets(module.jar)) assets.put(module.id(), module.jar);
        }
        return packs.set(assets);
    }

    /** Used by permanent mixins: an unrelated SDK server must never activate a cached cold mod. */
    public boolean modActive(String id) {
        return active.getAsBoolean() && running.stream().anyMatch(m -> m.loader == null && m.id().equals(id) && m.active());
    }

    private void enableMod(Candidate candidate) {
        ModuleManifest manifest = candidate.manifest;
        try {
            var entries = FabricLoader.getInstance().getEntrypointContainers(ServerModContract.ENTRYPOINT, DumServerMod.class)
                    .stream().filter(e -> e.getProvider().getMetadata().getId().equals(manifest.id())).toList();
            if (entries.size() != 1) throw new IllegalStateException("Expected one server-scoped entrypoint for " + manifest.id());
            DumServerMod instance = entries.getFirst().getEntrypoint();
            RunningModule module = new RunningModule(manifest, candidate.jar, null, instance, root.resolve("data").resolve(manifest.id()), keys, events, active);
            running.add(module);
            try {
                instance.onEnable(module);
                LOG.info("Server-scoped Fabric mod {} {} is prepared", manifest.id(), manifest.version());
            } catch (RuntimeException | LinkageError e) {
                fail(module, "запуск мода", e);
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Cannot prepare server-scoped Fabric mod {}", manifest.id(), e);
        }
    }

    /** Data from the server side of a module; dropped if that module is not running. */
    public void deliver(String id, byte[] data) {
        if (!active.getAsBoolean()) return;
        for (RunningModule module : running) {
            if (!module.id().equals(id)) continue;
            var handler = module.serverHandler;
            if (handler == null || !module.active()) return;
            long start = System.nanoTime();
            try {
                handler.accept(data);
            } catch (RuntimeException | LinkageError e) {
                fail(module, "сообщение сервера", e);
            }
            module.nanos.addAndGet(System.nanoTime() - start);
            return;
        }
    }

    /** Turns off and unloads every running module, dependents first. Their resources stay until {@link #dropResources}. */
    public void stop() {
        pressConsumed = false;
        required = List.of();
        for (RunningModule module : running.reversed()) stop(module);
        try {
            Files.deleteIfExists(activeMarker);
        } catch (IOException e) {
            LOG.warn("Cannot delete {}", activeMarker, e);
        }
    }

    /**
     * Takes the resources of modules that no longer run off the game. Never from inside Minecraft.disconnect before it
     * is done: it throws away the queued render thread tasks, the reload's own among them, and the loading screen
     * would stay forever.
     */
    public void dropResources() {
        if (running.isEmpty()) packs.set(Map.of());
    }

    ItemStack equipment(EquipmentRenderLayer layer, ItemStack equipped) {
        if (!active.getAsBoolean()) return equipped;
        ItemStack result = equipped;
        for (RunningModule module : running) {
            if (!module.active()) continue;
            for (var appearance : module.equipment) {
                long start = System.nanoTime();
                try {
                    result = Objects.requireNonNull(appearance.resolve(layer, result), "Null equipment appearance");
                } catch (RuntimeException | LinkageError e) {
                    fail(module, "изображение экипировки", e);
                    break;
                } finally {
                    module.nanos.addAndGet(System.nanoTime() - start);
                }
            }
        }
        return result;
    }

    ItemStack itemAppearance(ItemStack stack) {
        ItemDefinition definition = itemDefinition(stack);
        if (definition == null) return stack;
        ItemStack rendered = stack.copy();
        rendered.set(DataComponents.ITEM_MODEL, definition.model());
        return rendered;
    }

    Component itemName(ItemStack stack, Component original) {
        if (stack.has(DataComponents.CUSTOM_NAME)) return original;
        ItemDefinition definition = itemDefinition(stack);
        return definition == null ? original : definition.name();
    }

    private @Nullable ItemDefinition itemDefinition(ItemStack stack) {
        if (!active.getAsBoolean() || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String value = data.copyTag().getCompoundOrEmpty("PublicBukkitValues").getString("dum-sdk:item").orElse("");
        Identifier id = Identifier.tryParse(value);
        if (id == null) return null;
        for (RunningModule module : running) {
            if (module.active() && module.id().equals(id.getNamespace())) return module.items.get(id.getPath());
        }
        return null;
    }

    private boolean isRunning(String id) {
        return running.stream().anyMatch(m -> m.id().equals(id));
    }

    /** Required modules of these that do not run: with any of them missing, the player may not play on the server. */
    static List<String> missingRequired(List<ModuleManifest> modules, Predicate<String> runs) {
        return modules.stream().filter(ModuleManifest::isRequired).map(ModuleManifest::id).filter(id -> !runs.test(id)).toList();
    }

    record Candidate(ModuleManifest manifest, Path jar) {
    }

    /** @param all gets the manifest of every readable module, the skipped ones too */
    private Map<String, Candidate> scan(List<Path> jars, List<ModuleManifest> all) {
        Map<String, Candidate> found = new LinkedHashMap<>();
        for (Path jar : jars) {
            ModuleManifest manifest;
            try {
                manifest = ModuleManifest.read(jar);
            } catch (IOException e) {
                LOG.warn("Skipping {}: {}", jar.getFileName(), e.getMessage());
                continue;
            }
            all.add(manifest);
            String problem = found.containsKey(manifest.id()) ? "another jar has the same id"
                    : manifest.apiVersion() != API_VERSION ? "built for API " + manifest.apiVersion() + ", this DUM-SDK has API " + API_VERSION
                    : !manifest.hot() ? "load mode '" + manifest.loadMode() + "' is not a module mode: a mod that needs a restart ships as a whole Fabric mod jar"
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
    static List<Candidate> resolve(Map<String, Candidate> found) {
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
            module = new RunningModule(manifest, candidate.jar, loader, instance, root.resolve("data").resolve(manifest.id()), keys, events, active);
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
            fail(module, "запуск", e);
        }
    }

    /**
     * Turns off a module that threw, once, on the render thread, and tells the player. Until it is off,
     * it gets no more calls.
     */
    void fail(RunningModule module, String where, Throwable error) {
        if (module.failed) return;
        module.failed = true;
        LOG.error("Module {} failed in {}, turning it off", module.id(), where, error);
        failures.add(new Failure(module.id(), where, error.toString(), LocalTime.now().withNano(0)));
        DumSdkClient.recordError("MODULE_RUNTIME", module.id() + " (" + where + "): " + error);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (!running.contains(module)) return;
            stop(module);
            // A required module (or one it took down) is gone: playing on would be playing by other rules
            if (!missingRequired(required, this::isRunning).isEmpty() && minecraft.getConnection() != null) {
                minecraft.getConnection().getConnection().disconnect(Component.literal("Модуль DUM-SDK " + module.id()
                        + " перестал работать, а без него играть на сервере нельзя. Зайди снова. Если повторится, напиши администратору"));
                return;
            }
            SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("DUM-SDK: модуль " + module.id() + " выключен"), Component.literal("Подробнее: Настройки > DUM-SDK > Ошибки"));
        });
    }

    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    public List<Status> status() {
        long now = System.nanoTime();
        return running.stream().map(m -> new Status(m.id(), m.manifest.version(),
                m.nanos.get() / 1e6 / Math.max(1e-3, (now - m.startedAt) / 1e9))).toList();
    }

    private void stop(RunningModule module) {
        if (!running.remove(module)) return;
        for (RunningModule other : running) {
            if (other.manifest.deps().containsKey(module.id())) stop(other);
        }
        module.failed = true;
        try {
            module.instance.onDisable();
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Module {} failed while turning off", module.id(), e);
        }
        module.clear();
        if (module.loader != null) close(module.loader, module.id());
        LOG.info("Module {} is off", module.id());
        if (module.loader != null && FabricLoader.getInstance().isDevelopmentEnvironment()) checkUnloaded(module.loader, module.id());
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

    /** True to let the packet through; any thread for sent packets. */
    boolean packet(Packet<?> packet, boolean received) {
        if (!active.getAsBoolean()) return true;
        for (RunningModule module : running) {
            if (!module.active()) continue;
            for (RunningModule.PacketHandler handler : received ? module.received : module.sent) {
                if (!handler.type().isInstance(packet)) continue;
                long start = System.nanoTime();
                try {
                    if (!handler.handler().test(packet)) return false;
                } catch (RuntimeException | LinkageError e) {
                    fail(module, "пакет " + packet.getClass().getSimpleName(), e);
                    break;
                } finally {
                    module.nanos.addAndGet(System.nanoTime() - start);
                }
            }
        }
        return true;
    }

    private boolean clickConsumed(Screen screen, MouseButtonEvent event) {
        if (!active.getAsBoolean()) return false;
        for (RunningModule module : running) {
            if (!module.active()) continue;
            for (ScreenLayer layer : module.screens) {
                long start = System.nanoTime();
                try {
                    if (layer.mouseClicked(screen, event)) return true;
                } catch (RuntimeException | LinkageError e) {
                    fail(module, "клик", e);
                    break;
                } finally {
                    module.nanos.addAndGet(System.nanoTime() - start);
                }
            }
        }
        return false;
    }

    /** Calls every registration of every running module; a module that throws is turned off. */
    private <T> void dispatch(String where, Function<RunningModule, List<T>> registrations, Consumer<T> call) {
        if (!active.getAsBoolean()) return;
        for (RunningModule module : running) {
            if (!module.active()) continue;
            long start = System.nanoTime();
            for (T registration : registrations.apply(module)) {
                try {
                    call.accept(registration);
                } catch (RuntimeException | LinkageError e) {
                    fail(module, registration instanceof Map.Entry<?, ?> key && key.getKey() instanceof KeyMapping mapping
                            ? where + " " + mapping.getName() : where, e);
                    break;
                }
            }
            module.nanos.addAndGet(System.nanoTime() - start);
        }
    }
}
