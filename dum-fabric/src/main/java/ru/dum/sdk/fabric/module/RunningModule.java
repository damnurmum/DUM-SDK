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
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A module that is turned on, and everything it registered through its context. */
final class RunningModule implements DumContext {

    static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");

    /** A packet handler: the type it wants and what to do with it. */
    record PacketHandler(Class<?> type, Predicate<Object> handler) {
    }

    final ModuleManifest manifest;
    final Path jar;
    final URLClassLoader loader;
    final DumModule instance;
    final List<Runnable> ticks = new CopyOnWriteArrayList<>();
    final List<HudElement> huds = new CopyOnWriteArrayList<>();
    final List<ScreenLayer> screens = new CopyOnWriteArrayList<>();
    volatile @Nullable Consumer<byte[]> serverHandler;
    final Map<KeyMapping, Runnable> keys = new ConcurrentHashMap<>();
    final List<Entity> entities = new CopyOnWriteArrayList<>();
    final List<PacketHandler> received = new CopyOnWriteArrayList<>();
    final List<PacketHandler> sent = new CopyOnWriteArrayList<>();
    private final ModuleKeys keyStore;
    private final EventBridge events;
    /** Client-only entities get negative ids, so they never clash with the server's. */
    private static int nextEntityId = -1_000_000;
    private final Logger logger;
    private final Path dataDir;

    RunningModule(ModuleManifest manifest, Path jar, URLClassLoader loader, DumModule instance, Path dataDir,
                  ModuleKeys keyStore, EventBridge events) {
        this.keyStore = keyStore;
        this.events = events;
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
    public void onTick(Runnable task) {
        ticks.add(task);
    }

    @Override
    public void hud(HudElement element) {
        huds.add(element);
    }

    @Override
    public void screen(ScreenLayer layer) {
        screens.add(layer);
    }

    @Override
    public void onServerMessage(Consumer<byte[]> handler) {
        serverHandler = handler;
    }

    @Override
    public void sendToServer(byte[] data) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new SdkPayload(Protocol.encode(new Message.Module(id(), data))));
    }

    @Override
    public KeyMapping key(String name, int defaultKey, Runnable onPress) {
        KeyMapping key = keyStore.add(id(), name, defaultKey);
        keys.put(key, onPress);
        return key;
    }

    @Override
    public void addEntity(Entity entity) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("No level to add an entity to");
        entity.setId(nextEntityId--);
        level.addEntity(entity);
        entities.removeIf(Entity::isRemoved);
        entities.add(entity);
    }

    @Override
    public <T> void on(Event<T> event, T listener) {
        events.add(this, event, listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P extends Packet<?>> void onReceive(Class<P> type, Predicate<? super P> handler) {
        received.add(new PacketHandler(type, packet -> ((Predicate<Object>) handler).test(packet)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P extends Packet<?>> void onSend(Class<P> type, Predicate<? super P> handler) {
        sent.add(new PacketHandler(type, packet -> ((Predicate<Object>) handler).test(packet)));
    }

    void clear() {
        events.remove(this);
        received.clear();
        sent.clear();
        ticks.clear();
        huds.clear();
        screens.clear();
        serverHandler = null;
        if (!keys.isEmpty()) keyStore.remove(keys.keySet());
        keys.clear();
        ClientLevel level = Minecraft.getInstance().level;
        for (Entity entity : entities) {
            if (!entity.isRemoved() && level != null && entity.level() == level) level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        entities.clear();
    }
}
