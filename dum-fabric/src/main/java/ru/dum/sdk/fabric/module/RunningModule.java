package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.api.DumContext;
import ru.dum.sdk.api.DumModule;
import ru.dum.sdk.api.ScreenLayer;
import ru.dum.sdk.api.EquipmentAppearance;
import ru.dum.sdk.api.ItemDefinition;
import ru.dum.sdk.fabric.SdkPayload;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** A module that is turned on, and everything it registered through its context. */
final class RunningModule implements DumContext {

    /** A packet handler: the type it wants and what to do with it. */
    record PacketHandler(Class<?> type, Predicate<Object> handler) {
    }

    final ModuleManifest manifest;
    final Path jar;
    final @Nullable URLClassLoader loader;
    final DumModule instance;
    final List<Runnable> ticks = new CopyOnWriteArrayList<>();
    final List<HudElement> huds = new CopyOnWriteArrayList<>();
    final List<ScreenLayer> screens = new CopyOnWriteArrayList<>();
    final List<EquipmentAppearance> equipment = new CopyOnWriteArrayList<>();
    final Map<String, ItemDefinition> items = new ConcurrentHashMap<>();
    volatile @Nullable Consumer<byte[]> serverHandler;
    final Map<KeyMapping, Runnable> keys = new ConcurrentHashMap<>();
    final List<Entity> entities = new CopyOnWriteArrayList<>();
    /** Time spent in this module's code, for the diagnostics screen. */
    final AtomicLong nanos = new AtomicLong();
    final long startedAt = System.nanoTime();
    /** Threw and is being turned off: gets no more calls. */
    volatile boolean failed;
    final List<PacketHandler> received = new CopyOnWriteArrayList<>();
    final List<PacketHandler> sent = new CopyOnWriteArrayList<>();
    private final ModuleKeys keyStore;
    private final EventBridge events;
    /** Client-only entities get negative ids, so they never clash with the server's. */
    private static int nextEntityId = -1_000_000;
    private final Logger logger;
    private final Path dataDir;
    private final BooleanSupplier active;
    private volatile boolean closed;
    private final List<Runnable> cleanup = new CopyOnWriteArrayList<>();

    RunningModule(ModuleManifest manifest, Path jar, @Nullable URLClassLoader loader, DumModule instance, Path dataDir,
                  ModuleKeys keyStore, EventBridge events, BooleanSupplier active) {
        this.keyStore = keyStore;
        this.events = events;
        this.active = active;
        this.manifest = manifest;
        this.jar = jar;
        this.loader = loader;
        this.instance = instance;
        this.dataDir = dataDir;
        this.logger = LoggerFactory.getLogger("DUM-SDK/" + manifest.id());
    }

    @Override
    public String id() {
        return manifest.id();
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Path dataDir() {
        try {
            return Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onClose(Runnable task) {
        requireOpen();
        cleanup.add(task);
    }

    @Override
    public void onTick(Runnable task) {
        requireOpen();
        ticks.add(task);
    }

    @Override
    public void hud(HudElement element) {
        requireOpen();
        huds.add(element);
    }

    @Override
    public void screen(ScreenLayer layer) {
        requireOpen();
        screens.add(layer);
    }

    @Override
    public void equipmentAppearance(EquipmentAppearance appearance) {
        requireOpen();
        equipment.add(appearance);
    }

    @Override
    public void item(ItemDefinition definition) {
        requireOpen();
        if (items.putIfAbsent(definition.id(), definition) != null) throw new IllegalArgumentException("Duplicate item " + definition.id());
    }

    @Override
    public void onServerMessage(Consumer<byte[]> handler) {
        requireOpen();
        serverHandler = handler;
    }

    @Override
    public void sendToServer(byte[] data) {
        if (!active() || Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new SdkPayload(Protocol.encode(new Message.Module(id(), data))));
    }

    @Override
    public KeyMapping key(String name, int defaultKey, Runnable onPress) {
        requireOpen();
        KeyMapping key = keyStore.add(id(), name, defaultKey);
        keys.put(key, onPress);
        return key;
    }

    @Override
    public void addEntity(Entity entity) {
        if (!active()) throw new IllegalStateException("No active DUM-SDK session");
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("No level to add an entity to");
        entity.setId(nextEntityId--);
        level.addEntity(entity);
        entities.removeIf(Entity::isRemoved);
        entities.add(entity);
    }

    @Override
    public <T> void on(Event<T> event, T listener) {
        requireOpen();
        events.add(this, event, listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P extends Packet<?>> void onReceive(Class<P> type, Predicate<? super P> handler) {
        requireOpen();
        received.add(new PacketHandler(type, packet -> ((Predicate<Object>) handler).test(packet)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P extends Packet<?>> void onSend(Class<P> type, Predicate<? super P> handler) {
        requireOpen();
        sent.add(new PacketHandler(type, packet -> ((Predicate<Object>) handler).test(packet)));
    }

    void clear() {
        closed = true;
        events.remove(this);
        received.clear();
        sent.clear();
        ticks.clear();
        huds.clear();
        screens.clear();
        equipment.clear();
        items.clear();
        serverHandler = null;
        if (!keys.isEmpty()) keyStore.remove(keys.keySet());
        keys.clear();
        ClientLevel level = Minecraft.getInstance().level;
        for (Entity entity : entities) {
            if (!entity.isRemoved() && level != null && entity.level() == level) level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        entities.clear();
        for (Runnable task : cleanup.reversed()) {
            try {
                task.run();
            } catch (RuntimeException | LinkageError e) {
                logger.error("Session cleanup failed", e);
            }
        }
        cleanup.clear();
    }

    @Override
    public boolean active() {
        return !closed && !failed && active.getAsBoolean();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Module is unloaded");
    }
}
