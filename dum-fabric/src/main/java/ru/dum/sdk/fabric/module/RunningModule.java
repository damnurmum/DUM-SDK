package ru.dum.sdk.fabric.module;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.api.DumContext;
import ru.dum.sdk.api.DumModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A module that is turned on, and everything it registered through its context. */
final class RunningModule implements DumContext {

    final ModuleManifest manifest;
    final URLClassLoader loader;
    final DumModule instance;
    final List<Runnable> ticks = new CopyOnWriteArrayList<>();
    final List<HudElement> huds = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private final Path dataDir;

    RunningModule(ModuleManifest manifest, URLClassLoader loader, DumModule instance, Path dataDir) {
        this.manifest = manifest;
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
}
