package ru.dum.sdk.fabric.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only release history. Downloading an executable remains exclusively the signed updater's job. */
public final class ReleaseCatalog {
    private static final String ENDPOINT = "https://api.github.com/repos/damnurmum/DUM-SDK/releases?per_page=20&page=";
    @FunctionalInterface interface Fetch { String get(int page) throws IOException, InterruptedException; }
    public record Release(String tag, String name, String published, String notes, boolean prerelease) {
        public String url() { return SdkUpdater.DOWNLOAD_PAGE + "/tag/" + URLEncoder.encode(tag, StandardCharsets.UTF_8); }
        public String version() { return tag.replaceFirst("^v", ""); }
    }
    public record Snapshot(List<Release> releases, Instant checked, boolean cached, String error, boolean more) { }
    private final Path cache;
    private final Fetch fetch;
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Snapshot snapshot = new Snapshot(List.of(), Instant.EPOCH, false, "", true);
    private volatile int page;

    public ReleaseCatalog(Path root) { this(root, httpFetch()); }
    ReleaseCatalog(Path root, Fetch fetch) {
        cache = root.resolve("releases.json");
        this.fetch = fetch;
    }

    public Snapshot snapshot() { return snapshot; }
    public boolean loading() { return loading.get(); }

    public boolean load(boolean refresh) {
        if (!loading.compareAndSet(false, true)) return false;
        Thread.ofVirtual().name("DUM release history").start(() -> {
            try {
                if (page == 0 && snapshot.releases().isEmpty()) readCache();
                Duration age = Duration.between(snapshot.checked(), Instant.now());
                if (!refresh && page > 0 && !age.isNegative() && age.compareTo(Duration.ofMinutes(15)) < 0) return;
                loadPage(1, false);
            } finally { loading.set(false); }
        });
        return true;
    }

    public boolean loadMore() {
        if (!snapshot.more() || !loading.compareAndSet(false, true)) return false;
        Thread.ofVirtual().name("DUM older releases").start(() -> {
            try { loadPage(Math.max(1, page + 1), page > 0); }
            finally { loading.set(false); }
        });
        return true;
    }

    private void loadPage(int next, boolean append) {
        try {
            String response = fetch.get(next);
            List<Release> received = parse(response);
            var releases = new ArrayList<Release>(append ? snapshot.releases() : List.of());
            for (Release release : received) if (releases.stream().noneMatch(r -> r.tag().equals(release.tag()))) releases.add(release);
            // Keep browsing bounded even for a repository with years of release history.
            if (releases.size() > 200) releases.subList(200, releases.size()).clear();
            snapshot = new Snapshot(List.copyOf(releases), Instant.now(), false, "", received.size() == 20 && releases.size() < 200);
            page = next;
            writeCache();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException | RuntimeException e) {
            String message = e instanceof IOException ? "Не удалось загрузить релизы. Проверь интернет или открой GitHub. Код: RELEASES_NETWORK"
                    : "Некорректное описание релизов. Попробуй позже или открой GitHub. Код: RELEASES_DATA";
            snapshot = new Snapshot(snapshot.releases(), snapshot.checked(), true, message, snapshot.more());
            LoggerFactory.getLogger("DUM-SDK").warn("Cannot load release history", e);
        }
    }

    static List<Release> parse(String data) {
        JsonArray array = JsonParser.parseString(data).getAsJsonArray();
        if (array.size() > 20) throw new IllegalArgumentException("Too many releases in one page");
        var result = new ArrayList<Release>();
        for (var element : array) {
            JsonObject json = element.getAsJsonObject();
            if (json.has("draft") && json.get("draft").getAsBoolean()) continue;
            String tag = field(json, "tag_name", 128);
            if (tag.isBlank() || tag.contains("\n")) throw new IllegalArgumentException("Missing release tag");
            result.add(new Release(tag, field(json, "name", 200), field(json, "published_at", 40),
                    field(json, "body", 64 * 1024), json.has("prerelease") && json.get("prerelease").getAsBoolean()));
        }
        return List.copyOf(result);
    }

    private static String field(JsonObject json, String key, int limit) {
        String value = !json.has(key) || json.get(key).isJsonNull() ? "" : json.get(key).getAsString();
        return value.substring(0, Math.min(limit, value.length()));
    }

    private void readCache() {
        try {
            if (!Files.isRegularFile(cache) || Files.size(cache) > 16 * 1024 * 1024) return;
            var json = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
            var releases = new ArrayList<Release>();
            for (var element : json.getAsJsonArray("pages")) {
                releases.addAll(parse(element.toString()));
                if (releases.size() >= 200) break;
            }
            snapshot = new Snapshot(List.copyOf(releases.subList(0, Math.min(200, releases.size()))),
                    Instant.parse(json.get("checked").getAsString()), true, "", !json.has("more") || json.get("more").getAsBoolean());
            page = json.has("page") ? Math.max(1, json.get("page").getAsInt()) : Math.max(1, (releases.size() + 19) / 20);
        } catch (IOException | RuntimeException e) { LoggerFactory.getLogger("DUM-SDK").debug("Cannot read release cache", e); }
    }

    private void writeCache() {
        try {
            var data = new JsonObject();
            data.addProperty("checked", snapshot.checked().toString());
            data.addProperty("page", page);
            data.addProperty("more", snapshot.more());
            var pages = new JsonArray();
            for (int offset = 0; offset < snapshot.releases().size(); offset += 20) {
                var items = new JsonArray();
                for (Release release : snapshot.releases().subList(offset, Math.min(offset + 20, snapshot.releases().size()))) {
                    var json = new JsonObject();
                    json.addProperty("tag_name", release.tag()); json.addProperty("name", release.name());
                    json.addProperty("published_at", release.published()); json.addProperty("body", release.notes());
                    json.addProperty("prerelease", release.prerelease()); items.add(json);
                }
                pages.add(items);
            }
            data.add("pages", pages);
            Files.createDirectories(cache.getParent());
            Path temp = cache.resolveSibling("releases.json.tmp");
            Files.writeString(temp, data.toString());
            Files.move(temp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { LoggerFactory.getLogger("DUM-SDK").warn("Cannot cache release descriptions", e); }
    }

    private static Fetch httpFetch() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return page -> {
            String endpoint = ENDPOINT;
            // A separate explicit test endpoint; production always uses the SDK repository.
            if (Boolean.getBoolean("dumsdk.updateTest")) endpoint = System.getProperty("dumsdk.releasesUrl", ENDPOINT);
            var request = HttpRequest.newBuilder(URI.create(endpoint + page)).header("User-Agent", "DUM-SDK")
                    .timeout(Duration.ofSeconds(30)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("GitHub release history HTTP " + response.statusCode());
            if (response.body().length() > 2 * 1024 * 1024) throw new IOException("Release page is too large");
            return response.body();
        };
    }
}
