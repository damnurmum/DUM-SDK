package ru.dum.sdk.fabric.module;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.fabric.cold.ColdMods;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;
import ru.dum.sdk.network.Signing;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Brings the server's module catalog into the local cache ({@code dum-sdk/cache/<sha256>.jar}): keeps what is
 * already there, asks for the rest, checks the hash and the signature of every jar, then says READY, or RESTART
 * when whole Fabric mods of the catalog differ from the ones this game runs ({@link ColdMods}).
 * Runs on the network thread of the configuration phase. Texts in errors are shown to the player by the server.
 */
public final class ModuleInstaller {

    private static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");

    private final Path cache;
    /** The highest version of every module ever installed: the server may not take a module back to an older one. */
    private final Path versionsFile;
    private final Consumer<Message> send;
    private final ColdMods coldMods;
    private final Function<List<Path>, CompletableFuture<?>> installed;
    /** Dev-signed modules run only in a development client; -Ddumsdk.release=true turns that off for testing. */
    private final boolean allowDevKey = FabricLoader.getInstance().isDevelopmentEnvironment() && !Boolean.getBoolean("dumsdk.release");

    private List<Message.ModuleInfo> catalog = List.of();
    private final Map<String, Download> downloads = new HashMap<>();

    private static final class Download {
        final Message.ModuleInfo info;
        final byte[] data;
        int received;

        Download(Message.ModuleInfo info) {
            this.info = info;
            this.data = new byte[info.size()];
        }
    }

    /** Bytes of the current downloads; read by the progress bar on the render thread. */
    private volatile long total;
    private volatile long received;

    /**
     * @param send      sends a message to the server
     * @param installed gets the jars of the whole catalog once they are all in place; READY waits for it
     */
    public ModuleInstaller(Path root, Consumer<Message> send, Function<List<Path>, CompletableFuture<?>> installed) {
        this.cache = root.resolve("cache");
        this.versionsFile = root.resolve("versions.properties");
        this.coldMods = new ColdMods(root);
        this.send = send;
        this.installed = installed;
    }

    public void catalog(List<Message.ModuleInfo> modules) {
        catalog = modules;
        downloads.clear();
        total = 0;
        received = 0;
        Properties versions = loadVersions();
        for (Message.ModuleInfo module : modules) {
            String known = versions.getProperty(module.id());
            if (known != null && older(module.version(), known)) {
                fail("модуль " + module.id() + ": сервер предлагает старую версию " + module.version() + ", уже была " + known);
                return;
            }
            if (module.size() <= 0 || module.size() > Protocol.MAX_MODULE_SIZE) {
                fail("модуль " + module.id() + ": недопустимый размер " + module.size());
                return;
            }
            if (!cached(module)) {
                downloads.put(Signing.hex(module.sha256()), new Download(module));
                total += module.size();
                send.accept(new Message.Request(module.sha256()));
            }
        }
        LOG.info("{} modules from the server, {} to download", modules.size(), downloads.size());
        if (downloads.isEmpty()) finish();
    }

    public void chunk(Message.Chunk chunk) {
        Download download = downloads.get(Signing.hex(chunk.sha256()));
        if (download == null) return;
        String id = download.info.id();
        int length = chunk.data().length;
        if (chunk.offset() < 0 || chunk.offset() > download.data.length - length) {
            fail("модуль " + id + ": повреждённые данные при загрузке");
            return;
        }
        System.arraycopy(chunk.data(), 0, download.data, chunk.offset(), length);
        // The server sends every piece once, in order
        download.received += length;
        received += length;
        if (download.received < download.data.length) return;

        downloads.remove(Signing.hex(chunk.sha256()));
        if (!Arrays.equals(Signing.sha256(download.data), download.info.sha256())) {
            fail("модуль " + id + ": файл повреждён при загрузке");
            return;
        }
        if (!Signing.verify(download.data, download.info.signature(), allowDevKey)) {
            fail("модуль " + id + ": подпись не подходит, модуль не от DUM");
            return;
        }
        try {
            Files.createDirectories(cache);
            Files.write(jar(download.info), download.data);
        } catch (IOException e) {
            LOG.error("Cannot save module {}", id, e);
            fail("модуль " + id + ": не удалось сохранить на диск");
            return;
        }
        LOG.info("Downloaded module {} {}", id, download.info.version());
        if (downloads.isEmpty()) finish();
    }

    private void finish() {
        List<Path> jars = new ArrayList<>();
        List<ColdMods.Mod> fabricMods = new ArrayList<>();
        Properties versions = loadVersions();
        for (Message.ModuleInfo module : catalog) {
            Path jar = jar(module);
            String known = versions.getProperty(module.id());
            if (known == null || older(known, module.version())) versions.setProperty(module.id(), module.version());
            if (module.fabricMod()) {
                fabricMods.add(new ColdMods.Mod(module.id(), module.version(), module.sha256(), jar));
                continue;
            }
            try {
                ModuleManifest manifest = ModuleManifest.read(jar);
                if (!manifest.id().equals(module.id()) || !manifest.version().equals(module.version())) {
                    fail("модуль " + module.id() + ": в файле другой модуль (" + manifest.id() + " " + manifest.version() + ")");
                    return;
                }
            } catch (IOException e) {
                fail("модуль " + module.id() + ": " + e.getMessage());
                return;
            }
            jars.add(jar);
        }
        List<Path> keep = new ArrayList<>(jars);
        fabricMods.forEach(mod -> keep.add(mod.jar()));
        removeUnused(keep);
        total = 0;
        List<String> restart;
        try {
            restart = coldMods.sync(fabricMods);
        } catch (IllegalStateException e) {
            fail(e.getMessage());
            return;
        } catch (IOException e) {
            LOG.error("Cannot prepare Fabric mods", e);
            fail("не удалось подготовить моды: " + e.getMessage());
            return;
        }
        // Only now is everything installed; a failed attempt must not raise the bar for older versions
        try (Writer writer = Files.newBufferedWriter(versionsFile)) {
            versions.store(writer, "Highest installed version of every DUM-SDK module");
        } catch (IOException e) {
            LOG.warn("Cannot save {}", versionsFile, e);
        }
        if (!restart.isEmpty()) {
            // The server disconnects us; the mods change when the game exits
            send.accept(new Message.Restart(restart));
            return;
        }
        installed.apply(jars).whenComplete((result, error) -> {
            if (error != null) LOG.error("Cannot start modules", error);
            send.accept(new Message.Ready(error == null ? "" : "не удалось загрузить ресурсы модулей"));
        });
    }

    /** Cached jars of older versions and of modules the server no longer has. A file still in use stays until next time. */
    private void removeUnused(List<Path> keep) {
        try (Stream<Path> files = Files.list(cache)) {
            for (Path file : files.filter(f -> !keep.contains(f)).toList()) {
                try {
                    Files.delete(file);
                    LOG.info("Removed unused {}", file.getFileName());
                } catch (IOException e) {
                    LOG.debug("Cannot remove {} yet", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            // No cache folder yet: nothing to clean
        }
    }

    /** Fabric mods from the server that change on the next restart. */
    public List<String> pendingRestart() {
        return coldMods.pending();
    }

    /** Fraction of the current downloads that arrived, or -1 when nothing is downloading. */
    public float progress() {
        long all = total;
        return all > 0 ? (float) received / all : -1;
    }

    /** Megabytes arrived and expected, for the progress bar. */
    public String progressText() {
        return String.format("%.1f из %.1f МБ", received / 1048576.0, total / 1048576.0);
    }

    private void fail(String reason) {
        LOG.warn("Cannot install modules: {}", reason);
        downloads.clear();
        total = 0;
        send.accept(new Message.Ready(reason));
    }

    private Path jar(Message.ModuleInfo module) {
        return cache.resolve(Signing.hex(module.sha256()) + ".jar");
    }

    /** In the cache with the right hash and a valid signature; anything else is downloaded again. */
    private boolean cached(Message.ModuleInfo module) {
        Path jar = jar(module);
        if (!Files.isRegularFile(jar)) return false;
        try {
            byte[] data = Files.readAllBytes(jar);
            return Arrays.equals(Signing.sha256(data), module.sha256()) && Signing.verify(data, module.signature(), allowDevKey);
        } catch (IOException e) {
            return false;
        }
    }

    private Properties loadVersions() {
        Properties versions = new Properties();
        if (Files.exists(versionsFile)) {
            try (Reader reader = Files.newBufferedReader(versionsFile)) {
                versions.load(reader);
            } catch (IOException e) {
                LOG.warn("Cannot read {}", versionsFile, e);
            }
        }
        return versions;
    }

    private static boolean older(String version, String than) {
        try {
            return Version.parse(version).compareTo(Version.parse(than)) < 0;
        } catch (VersionParsingException e) {
            // ModuleManifest.read rejects unparsable versions later
            return false;
        }
    }
}
