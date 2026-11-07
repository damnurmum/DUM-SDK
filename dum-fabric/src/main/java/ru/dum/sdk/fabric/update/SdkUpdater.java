package ru.dum.sdk.fabric.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.fabric.cold.ColdApply;
import ru.dum.sdk.network.Protocol;
import ru.dum.sdk.network.Signing;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

import static ru.dum.sdk.fabric.update.UpdateStatus.Stage.*;
import java.util.zip.ZipFile;

/**
 * Updates DUM-SDK itself from GitHub Releases with bounded background retries, only when the player asks: to the
 * version of the server they want to join ({@link #installInBackground}), or to the latest release from the DUM-SDK
 * menu. The release's DUM-SDK-Fabric-X.Y.Z[-stage].jar, signed with the release key ({@link Signing}), is downloaded
 * and replaces this jar after the game exits ({@link ColdApply}). Off in development, unless -Ddumsdk.updateTest=true.
 */
public final class SdkUpdater {

    private static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");
    private static final String RELEASES = "https://api.github.com/repos/damnurmum/DUM-SDK/releases/latest";
    /** One release by tag: this plus "v" and the version. */
    private static final String TAGS = "https://api.github.com/repos/damnurmum/DUM-SDK/releases/tags/";
    public static final String DOWNLOAD_PAGE = "https://github.com/damnurmum/DUM-SDK/releases";

    private final Path root;
    private final String current;
    @FunctionalInterface interface Download { byte[] get(String url, int limit) throws IOException, InterruptedException; }
    @FunctionalInterface interface Install { void plan(Path staged, String version) throws IOException; }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }
    private final String releases, tags;
    private final boolean enabled, allowDevKey;
    private final Download download;
    private final Install install;
    private final Pause pause;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile @Nullable String pending;
    private volatile UpdateStatus status;

    public SdkUpdater(Path root, String current) {
        this(root, current, System.getProperty("dumsdk.updateUrl", RELEASES), System.getProperty("dumsdk.updateTagsUrl", TAGS),
                httpDownload(), null, Thread::sleep,
                !FabricLoader.getInstance().isDevelopmentEnvironment() || Boolean.getBoolean("dumsdk.updateTest"),
                Boolean.getBoolean("dumsdk.updateTest"));
    }

    SdkUpdater(Path root, String current, String releases, Download download, @Nullable Install install,
               Pause pause, boolean enabled, boolean allowDevKey) {
        this(root, current, releases, releases + "/tags/", download, install, pause, enabled, allowDevKey);
    }

    SdkUpdater(Path root, String current, String releases, String tags, Download download, @Nullable Install install,
               Pause pause, boolean enabled, boolean allowDevKey) {
        this.root = root;
        this.current = current;
        this.releases = releases;
        this.tags = tags;
        this.download = download;
        this.install = install == null ? this::plan : install;
        this.pause = pause;
        this.enabled = enabled;
        this.allowDevKey = allowDevKey;
        status = new UpdateStatus(enabled ? IDLE : DISABLED, 0, "", "", "");
    }

    public @Nullable String pending() { return pending; }
    public UpdateStatus status() { return status; }
    public boolean canCheck() { return enabled && !running.get() && pending == null; }

    /** The latest release, when it is newer. The observer runs on the worker; UI callers dispatch to the render thread. */
    public boolean checkInBackground(Consumer<UpdateStatus> observer) {
        return start(null, observer);
    }

    /**
     * Exactly this version, older or newer than this one: the server's, once the player agreed. Its release is
     * found by tag v + version. False when the updater is off, busy or an update is already staged.
     */
    public boolean installInBackground(String version, Consumer<UpdateStatus> observer) {
        return safeVersion(version) && start(version, observer);
    }

    private boolean start(@Nullable String wanted, Consumer<UpdateStatus> observer) {
        if (!enabled || pending != null || !running.compareAndSet(false, true)) return false;
        Thread.ofVirtual().name("DUM-SDK update").start(() -> {
            try {
                clearStaged();
                for (int attempt = 1; attempt <= UpdateStatus.MAX_ATTEMPTS; attempt++) {
                    publish(new UpdateStatus(CHECKING, attempt, "", "", ""), observer);
                    try {
                        String version = check(wanted, attempt, observer);
                        publish(new UpdateStatus(version == null ? CURRENT : READY, attempt,
                                version == null ? current : version, "", ""), observer);
                        return;
                    } catch (IOException | RuntimeException e) {
                        Failure failure = classify(e);
                        boolean retry = failure.retry && attempt < UpdateStatus.MAX_ATTEMPTS;
                        LOG.warn("DUM-SDK update [{}, attempt {}/{}]: {}", failure.code, attempt, UpdateStatus.MAX_ATTEMPTS, e.toString());
                        publish(new UpdateStatus(retry ? RETRY_WAIT : FAILED, attempt, status.version(), failure.code, failure.getMessage()), observer);
                        if (!retry) return;
                        pause.sleep(attempt == 1 ? 5_000 : 15_000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                publish(new UpdateStatus(FAILED, status.attempt(), status.version(), "UPDATE_INTERRUPTED", "Обновление прервано."), observer);
            } finally { running.set(false); }
        });
        return true;
    }

    private void publish(UpdateStatus next, Consumer<UpdateStatus> observer) {
        status = next;
        // Display failures must never restart an already staged update.
        try { observer.accept(next); } catch (RuntimeException e) { LOG.warn("Cannot display update status", e); }
    }

    private @Nullable String check(@Nullable String wanted, int attempt, Consumer<UpdateStatus> observer) throws IOException, InterruptedException {
        String version, jarUrl = null, signatureUrl = null;
        byte[] metadata;
        try {
            metadata = download.get(wanted == null ? releases : tags + "v" + wanted, 1024 * 1024);
        } catch (Failure e) {
            if (wanted != null && e.code.equals("UPDATE_HTTP_404")) {
                throw new Failure("UPDATE_NOT_FOUND", "Версии " + wanted + " нет среди релизов на GitHub.", false, e);
            }
            throw e;
        }
        try {
            JsonObject release = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
            version = release.get("tag_name").getAsString().replaceFirst("^v", "");
            Version.parse(version);
            // The version enters a filename and a helper plan: accept only a safe filename token.
            if (!safeVersion(version)) throw new IllegalArgumentException("Invalid version");
            if (wanted != null && !wanted.equals(version)) throw new IllegalArgumentException("Release " + version + " instead of " + wanted);
            if (wanted == null ? !newer(version, current) : version.equals(current)) return null;
            for (JsonElement asset : release.getAsJsonArray("assets")) {
                String name = asset.getAsJsonObject().get("name").getAsString();
                String url = asset.getAsJsonObject().get("browser_download_url").getAsString();
                if (name.equals(jarName(version))) jarUrl = url;
                if (name.equals(jarName(version) + ".sig")) signatureUrl = url;
            }
            if (jarUrl == null || signatureUrl == null) throw new IllegalArgumentException("Missing release assets");
        } catch (RuntimeException | VersionParsingException e) {
            throw new Failure("UPDATE_RELEASE", "GitHub прислал некорректное описание релиза.", false, e);
        }
        publish(new UpdateStatus(DOWNLOADING, attempt, version, "", ""), observer);
        byte[] jar = download.get(jarUrl, Protocol.MAX_MODULE_SIZE);
        byte[] signature = download.get(signatureUrl, 64);
        publish(new UpdateStatus(VERIFYING, attempt, version, "", ""), observer);
        if (!Signing.verify(jar, signature, allowDevKey)) {
            throw new Failure("UPDATE_SIGNATURE", "Подпись обновления не подходит. Файл не установлен.", false, null);
        }
        Path staged = root.resolve("update").resolve(jarName(version));
        try {
            Files.createDirectories(staged.getParent());
            Files.write(staged, jar);
            if (!version.equals(modVersion(staged))) {
                Files.deleteIfExists(staged);
                throw new Failure("UPDATE_VERSION", "Версия внутри файла не совпадает с релизом.", false, null);
            }
            install.plan(staged, version);
        } catch (Failure e) { throw e; }
        catch (IOException | RuntimeException e) {
            throw new Failure("UPDATE_STORAGE", "Не удалось подготовить обновление. Проверь место и права на папку игры.", false, e);
        }
        pending = version;
        LOG.info("DUM-SDK {} is downloaded and replaces {} when the game exits", version, current);
        return version;
    }

    private static boolean safeVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    }

    /**
     * The client's file in a release, in update/ and in mods/, its signature that plus .sig: DUM-SDK-Fabric-X.Y.Z.jar,
     * with the stage of a pre-release in its version, as in DUM-SDK-Fabric-0.16.0-alpha.jar.
     */
    static String jarName(String version) {
        return "DUM-SDK-Fabric-" + version + ".jar";
    }

    /** Jars downloaded for earlier updates: installed by now, or no longer wanted. */
    private void clearStaged() {
        Path staged = root.resolve("update");
        if (!Files.isDirectory(staged)) return;
        try (var files = Files.list(staged)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.debug("Cannot clear {}", staged, e);
        }
    }

    /** The new jar goes into mods/, this one leaves it, both after the game exits. */
    private void plan(Path staged, String version) throws IOException {
        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
        List<String> operations = new ArrayList<>();
        operations.add("copy\t" + staged.toAbsolutePath() + "\t" + mods.resolve(jarName(version)).toAbsolutePath());
        ModOrigin origin = FabricLoader.getInstance().getModContainer("dum-sdk").orElseThrow().getOrigin();
        if (origin.getKind() == ModOrigin.Kind.PATH) {
            for (Path path : origin.getPaths()) {
                // A development client runs from class folders: nothing to delete
                if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) operations.add("delete\t" + path.toAbsolutePath());
            }
        }
        Path plan = root.resolve("sdk-update-plan.txt");
        Files.writeString(plan, String.join("\n", operations), StandardCharsets.UTF_8);
        try { ColdApply.start(plan); }
        catch (IOException e) { Files.deleteIfExists(plan); throw e; }
    }

    static Download httpDownload() {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
        return (url, limit) -> {
            URI uri;
            try {
                uri = URI.create(url);
                if (!"https".equals(uri.getScheme()) && !(Boolean.getBoolean("dumsdk.updateTest") && "http".equals(uri.getScheme()))) {
                    throw new IllegalArgumentException("Expected HTTPS");
                }
            } catch (RuntimeException e) { throw new Failure("UPDATE_RELEASE", "Некорректная ссылка в релизе.", false, e); }
            HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", "DUM-SDK").timeout(Duration.ofSeconds(60)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int code = response.statusCode();
            if (code != 200) throw new Failure("UPDATE_HTTP_" + code, "GitHub ответил с ошибкой " + code + ".",
                    code == 408 || code == 429 || code >= 500, null);
            if (response.body().length > limit) throw new Failure("UPDATE_SIZE", "Файл обновления превышает допустимый размер.", false, null);
            return response.body();
        };
    }

    static Failure classify(Throwable error) {
        if (error instanceof Failure failure) return failure;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException) {
                return new Failure("UPDATE_DNS", "Не удалось найти GitHub. Проверь подключение к интернету.", true, error);
            }
            if (cause instanceof SSLException) return new Failure("UPDATE_TLS", "Не удалось установить защищённое соединение с GitHub.", false, error);
        }
        if (error instanceof IOException) return new Failure("UPDATE_NETWORK", "Нет соединения с GitHub. Проверь интернет.", true, error);
        return new Failure("UPDATE_INTERNAL", "Не удалось обработать обновление. Подробности в логе игры.", false, error);
    }

    static final class Failure extends IOException {
        final String code;
        final boolean retry;
        Failure(String code, String message, boolean retry, @Nullable Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retry = retry;
        }
    }

    private static @Nullable String modVersion(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return "dum-sdk".equals(json.get("id").getAsString()) ? json.get("version").getAsString() : null;
            }
        }
    }

    static boolean newer(String version, String than) {
        try {
            return Version.parse(version).compareTo(Version.parse(than)) > 0;
        } catch (VersionParsingException e) {
            return false;
        }
    }
}
