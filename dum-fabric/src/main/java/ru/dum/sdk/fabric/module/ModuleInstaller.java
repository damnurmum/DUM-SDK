package ru.dum.sdk.fabric.module;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.fabric.cold.ColdMods;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.ErrorCode;
import ru.dum.sdk.network.ModuleDownload;
import ru.dum.sdk.network.ProtocolException;
import ru.dum.sdk.network.TransferValidation;
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
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
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
    private final BiFunction<List<Path>, List<ColdMods.Mod>, CompletableFuture<?>> installed;
    private final BooleanSupplier current;
    /** Dev-signed modules run only in a development client; -Ddumsdk.release=true turns that off for testing. */
    private final boolean allowDevKey = FabricLoader.getInstance().isDevelopmentEnvironment() && !Boolean.getBoolean("dumsdk.release");

    private volatile List<Message.ModuleInfo> catalog = List.of();
    private final Map<String, ModuleDownload> downloads = new HashMap<>();
    private enum State { NEW, DOWNLOADING, INSTALLING, COMPLETE, FAILED }
    private volatile State state = State.NEW;

    /** Bytes of the current downloads; read by the progress bar on the render thread. */
    private volatile long total;
    private volatile long received;

    /**
     * @param send      sends a message to the server
     * @param installed gets hot jars and verified cold mods once they are all in place; READY waits for it
     */
    public ModuleInstaller(Path root, ColdMods coldMods, Consumer<Message> send, BiFunction<List<Path>, List<ColdMods.Mod>, CompletableFuture<?>> installed,
                           BooleanSupplier current) {
        this.cache = root.resolve("cache");
        this.versionsFile = root.resolve("versions.properties");
        this.coldMods = coldMods;
        this.send = send;
        this.installed = installed;
        this.current = current;
    }

    public void catalog(List<Message.ModuleInfo> modules) {
        if (!current.getAsBoolean() || terminal()) return;
        if (state != State.NEW) {
            fail(ErrorCode.INVALID_CATALOG, "", "Catalog received more than once");
            return;
        }
        try {
            TransferValidation.catalog(modules);
        } catch (ProtocolException e) {
            reject(e);
            return;
        }
        // Check every entry, including rollback, before allocating buffers or sending any request.
        Properties versions = loadVersions();
        for (Message.ModuleInfo module : modules) {
            String known = versions.getProperty(module.id());
            if (known != null && older(module.version(), known)) {
                fail(ErrorCode.VERSION_ROLLBACK, module.id(), "Offered " + module.version() + ", already installed " + known);
                return;
            }
        }
        catalog = List.copyOf(modules);
        for (Message.ModuleInfo module : catalog) {
            if (!cached(module)) {
                downloads.put(Signing.hex(module.sha256()), new ModuleDownload(module));
                total += module.size();
            }
        }
        state = State.DOWNLOADING;
        LOG.info("{} modules from the server, {} to download", modules.size(), downloads.size());
        if (downloads.isEmpty()) {
            finish();
            return;
        }
        for (ModuleDownload download : List.copyOf(downloads.values())) {
            if (terminal() || !current.getAsBoolean()) return;
            send.accept(new Message.Request(download.info().sha256()));
        }
    }

    public void chunk(Message.Chunk chunk) {
        if (!current.getAsBoolean() || terminal()) return;
        ModuleDownload download = downloads.get(Signing.hex(chunk.sha256()));
        if (state != State.DOWNLOADING || download == null) {
            fail(ErrorCode.UNEXPECTED_CHUNK, "", "Chunk was not requested or its download already completed");
            return;
        }
        String id = download.info().id();
        try {
            download.accept(chunk);
        } catch (ProtocolException e) {
            reject(e);
            return;
        }
        received += chunk.data().length;
        if (!download.complete()) return;
        if (!Signing.verify(download.data(), download.info().signature(), allowDevKey)) {
            fail(ErrorCode.SIGNATURE_MISMATCH, id, "Jar is not signed with an accepted key");
            return;
        }
        try {
            Files.createDirectories(cache);
            Files.write(jar(download.info()), download.data());
        } catch (IOException e) {
            LOG.error("Cannot save module {}", id, e);
            fail(ErrorCode.CACHE_WRITE_FAILED, id, e.toString());
            return;
        }
        downloads.remove(Signing.hex(chunk.sha256()));
        LOG.info("Downloaded module {} {}", id, download.info().version());
        if (downloads.isEmpty()) finish();
    }

    /** Malformed packets must stop installation instead of waiting for a timeout or later claiming success. */
    public void reject(ProtocolException error) {
        fail(error.code(), error.module(), error.getMessage());
    }

    private void finish() {
        if (!current.getAsBoolean() || state != State.DOWNLOADING) return;
        state = State.INSTALLING;
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
                    fail(ErrorCode.INVALID_MODULE, module.id(), "Manifest ID/version does not match the catalog");
                    return;
                }
            } catch (IOException e) {
                fail(ErrorCode.INVALID_MODULE, module.id(), e.toString());
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
            restart = coldMods.sync(fabricMods, current);
        } catch (RuntimeException e) {
            fail(ErrorCode.COLD_MOD_REJECTED, "", e.getMessage());
            return;
        } catch (IOException e) {
            LOG.error("Cannot prepare Fabric mods", e);
            fail(ErrorCode.COLD_MOD_IO, "", e.toString());
            return;
        }
        if (!current.getAsBoolean()) return;
        if (!restart.isEmpty()) {
            saveVersions(versions);
            state = State.COMPLETE;
            send.accept(new Message.Restart(restart));
            return;
        }
        CompletableFuture<?> installation;
        try {
            installation = installed.apply(jars, fabricMods);
        } catch (RuntimeException e) {
            fail(ErrorCode.MODULE_START_FAILED, "", e.toString());
            return;
        }
        installation.whenCompleteAsync((result, error) -> {
            if (!current.getAsBoolean() || state != State.INSTALLING) return;
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof ModuleStartException refused) {
                fail(ErrorCode.MODULE_START_FAILED, "", refused.getMessage());
                return;
            }
            if (error != null) {
                LOG.error("Cannot start modules", error);
                fail(ErrorCode.RESOURCE_RELOAD_FAILED, "", error.toString());
                return;
            }
            complete(versions);
        });
    }

    private synchronized void complete(Properties versions) {
        if (!current.getAsBoolean() || state != State.INSTALLING) return;
        saveVersions(versions);
        state = State.COMPLETE;
        send.accept(Message.Ready.success());
    }

    private void saveVersions(Properties versions) {
        try (Writer writer = Files.newBufferedWriter(versionsFile)) {
            versions.store(writer, "Highest installed version of every DUM-SDK module");
        } catch (IOException e) {
            LOG.warn("Cannot save {}", versionsFile, e);
        }
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

    public List<Message.ModuleInfo> catalogSnapshot() { return catalog; }

    /** Fraction of the current downloads that arrived, or -1 when nothing is downloading. */
    public float progress() {
        long all = total;
        return all > 0 ? (float) received / all : -1;
    }

    /** Megabytes arrived and expected, for the progress bar. */
    public String progressText() {
        return String.format("%.1f из %.1f МБ", received / 1048576.0, total / 1048576.0);
    }

    private boolean terminal() {
        return state == State.COMPLETE || state == State.FAILED;
    }

    private synchronized void fail(ErrorCode code, String module, String detail) {
        if (terminal() || !current.getAsBoolean()) return;
        state = State.FAILED;
        LOG.warn("Cannot install modules [{}] {}: {}", code, module, detail);
        downloads.clear();
        total = 0;
        if (!TransferValidation.moduleId(module)) module = "";
        detail = detail == null ? "" : detail;
        if (detail.length() > Protocol.MAX_ERROR_DETAIL) detail = detail.substring(0, Protocol.MAX_ERROR_DETAIL);
        send.accept(new Message.Ready(code, module, detail));
    }

    private Path jar(Message.ModuleInfo module) {
        return cache.resolve(Signing.hex(module.sha256()) + ".jar");
    }

    /** In the cache with the right hash and a valid signature; anything else is downloaded again. */
    private boolean cached(Message.ModuleInfo module) {
        Path jar = jar(module);
        if (!Files.isRegularFile(jar)) return false;
        try {
            if (Files.size(jar) != module.size()) return false;
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

    static boolean older(String version, String than) {
        try {
            return Version.parse(version).compareTo(Version.parse(than)) < 0;
        } catch (VersionParsingException e) {
            // ModuleManifest.read rejects unparsable versions later
            return false;
        }
    }
}
