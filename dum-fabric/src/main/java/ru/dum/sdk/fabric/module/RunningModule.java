package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Minecraft;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** A module that is turned on, and everything it registered through its context. */
final class RunningModule implements DumContext {

    final ModuleManifest manifest;
    final Path jar;
    final URLClassLoader loader;
    final DumModule instance;
    final List<Runnable> ticks = new CopyOnWriteArrayList<>();
    final List<HudElement> huds = new CopyOnWriteArrayList<>();
    final List<ScreenLayer> screens = new CopyOnWriteArrayList<>();
    volatile @Nullable Consumer<byte[]> serverHandler;
    private final Logger logger;
    private final Path dataDir;

    RunningModule(ModuleManifest manifest, Path jar, URLClassLoader loader, DumModule instance, Path dataDir) {
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

    void clear() {
        ticks.clear();
        huds.clear();
        screens.clear();
        serverHandler = null;
    }
}
