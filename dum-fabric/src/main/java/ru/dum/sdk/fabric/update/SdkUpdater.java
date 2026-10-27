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
import java.util.zip.ZipFile;

/**
 * Updates DUM-SDK itself from GitHub Releases. Once per game start it looks at the latest release; a newer version
 * whose DUM-SDK.jar is signed with the release key ({@link Signing}) is downloaded and replaces this jar after the
 * game exits ({@link ColdApply}). Off in development, unless -Ddumsdk.updateTest=true.
 */
public final class SdkUpdater {

    private static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");
    private static final String RELEASES = "https://api.github.com/repos/damnurmum/DUM-SDK/releases/latest";
    public static final String DOWNLOAD_PAGE = "https://github.com/damnurmum/DUM-SDK/releases";

    private final Path root;
    private final String current;
    private final boolean test = Boolean.getBoolean("dumsdk.updateTest");
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
    /** A newer version waiting for the restart, or null. */
    private volatile @Nullable String pending;

    public SdkUpdater(Path root, String current) {
        this.root = root;
        this.current = current;
    }

    public @Nullable String pending() {
        return pending;
    }

    /** Checks in the background; calls back with the new version once it is ready to install. */
    public void checkInBackground(Consumer<String> ready) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment() && !test) return;
        Thread.ofVirtual().name("DUM-SDK update").start(() -> {
            try {
                String version = check();
                if (version != null) ready.accept(version);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Cannot check for a DUM-SDK update: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private @Nullable String check() throws IOException, InterruptedException {
        clearStaged();
        JsonObject release = JsonParser.parseString(get(System.getProperty("dumsdk.updateUrl", RELEASES)).body()).getAsJsonObject();
        String version = release.get("tag_name").getAsString().replaceFirst("^v", "");
        if (!newer(version, current)) return null;
        String jarUrl = null, signatureUrl = null;
        for (JsonElement asset : release.getAsJsonArray("assets")) {
            String name = asset.getAsJsonObject().get("name").getAsString();
            String url = asset.getAsJsonObject().get("browser_download_url").getAsString();
            if (name.equals("DUM-SDK.jar")) jarUrl = url;
            if (name.equals("DUM-SDK.jar.sig")) signatureUrl = url;
        }
        if (jarUrl == null || signatureUrl == null) throw new IOException("release " + version + " has no DUM-SDK.jar with its .sig");

        byte[] jar = getBytes(jarUrl);
        byte[] signature = getBytes(signatureUrl);
        // The release key of this very version decides: a stolen GitHub account cannot ship code
        if (!Signing.verify(jar, signature, test)) throw new IOException("release " + version + " is not signed with the DUM key");
        Path staged = Files.createDirectories(root.resolve("update")).resolve("DUM-SDK-" + version + ".jar");
        Files.write(staged, jar);
        String inside = modVersion(staged);
        if (!version.equals(inside)) throw new IOException("release " + version + " contains DUM-SDK " + inside);

        plan(staged, version);
        pending = version;
        LOG.info("DUM-SDK {} is downloaded and replaces {} when the game exits", version, current);
        return version;
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
        operations.add("copy\t" + staged.toAbsolutePath() + "\t" + mods.resolve("DUM-SDK-" + version + ".jar").toAbsolutePath());
        ModOrigin origin = FabricLoader.getInstance().getModContainer("dum-sdk").orElseThrow().getOrigin();
        if (origin.getKind() == ModOrigin.Kind.PATH) {
            for (Path path : origin.getPaths()) {
                // A development client runs from class folders: nothing to delete
                if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) operations.add("delete\t" + path.toAbsolutePath());
            }
        }
        Path plan = root.resolve("sdk-update-plan.txt");
        Files.writeString(plan, String.join("\n", operations), StandardCharsets.UTF_8);
        ColdApply.start(plan);
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(url + " answered " + response.statusCode());
        return response;
    }

    private byte[] getBytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException(url + " answered " + response.statusCode());
        if (response.body().length > Protocol.MAX_MODULE_SIZE) throw new IOException(url + " is too large");
        return response.body();
    }

    private static HttpRequest request(String url) {
        // GitHub's API wants a User-Agent
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "DUM-SDK").timeout(Duration.ofSeconds(60)).build();
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
