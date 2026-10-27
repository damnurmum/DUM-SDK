package ru.dum.sdk.fabric.cold;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.network.Signing;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Whole Fabric mods from the server (cold load). They live in mods/ as {@code dum-cold-<id>.jar}; Fabric loads
 * them only at start, so a change waits for a restart and is carried out by {@link ColdApply} once the game exits.
 * Error messages are shown to the player by the server.
 */
public final class ColdMods {

    private static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");
    private static final String PREFIX = "dum-cold-";

    /** A cold mod of the catalog, already downloaded and checked. */
    public record Mod(String id, String version, byte[] sha256, Path jar) {
    }

    private final Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
    private final Path plan;
    private boolean helperStarted;
    /** Mods that change on the next restart, for /sdk. */
    private volatile List<String> pending = List.of();

    public ColdMods(Path root) {
        this.plan = root.resolve("cold-plan.txt");
    }

    /**
     * Compares the catalog with the mods this game runs and, if they differ, plans the change for after exit.
     *
     * @return ids of the mods that change on restart; empty when the game already runs exactly these
     * @throws IllegalStateException with the reason to show the player when a mod cannot be installed
     */
    public List<String> sync(List<Mod> wanted) throws IOException {
        Map<String, String> versions = new HashMap<>();
        Map<String, JsonObject> metadata = new HashMap<>();
        for (Mod mod : wanted) {
            JsonObject json = fabricModJson(mod.jar);
            if (!mod.id.equals(string(json, "id")) || !mod.version.equals(string(json, "version"))) {
                throw new IllegalStateException("мод " + mod.id + ": в файле другой мод (" + string(json, "id") + " " + string(json, "version") + ")");
            }
            versions.put(mod.id, mod.version);
            metadata.put(mod.id, json);
        }
        for (Mod mod : wanted) {
            checkDependencies(mod.id, metadata.get(mod.id), versions);
            Optional<ModContainer> loaded = FabricLoader.getInstance().getModContainer(mod.id);
            if (loaded.isPresent() && !isCold(loaded.get())) {
                throw new IllegalStateException("мод " + mod.id + " уже стоит у тебя (" + origin(loaded.get())
                        + "), а сервер ставит свой. Удали свой из mods и зайди снова");
            }
        }

        List<String> operations = new ArrayList<>();
        TreeSet<String> changed = new TreeSet<>();
        for (Mod mod : wanted) {
            Path target = mods.resolve(PREFIX + mod.id + ".jar");
            if (!running(mod, target)) {
                operations.add("copy\t" + mod.jar.toAbsolutePath() + "\t" + target.toAbsolutePath());
                changed.add(mod.id);
            }
        }
        // Cold mods the server no longer has: files left in mods/ and mods still running from such files
        try (Stream<Path> files = Files.exists(mods) ? Files.list(mods) : Stream.empty()) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(PREFIX) || !name.endsWith(".jar")) continue;
                String id = name.substring(PREFIX.length(), name.length() - ".jar".length());
                if (!versions.containsKey(id)) {
                    operations.add("delete\t" + file.toAbsolutePath());
                    changed.add(id);
                }
            }
        }
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            if (isCold(container) && !versions.containsKey(container.getMetadata().getId())) changed.add(container.getMetadata().getId());
        }

        Files.writeString(plan, String.join("\n", operations), StandardCharsets.UTF_8);
        pending = List.copyOf(changed);
        if (!operations.isEmpty()) {
            LOG.info("Fabric mods change on restart: {}", operations);
            startHelper();
        }
        return List.copyOf(changed);
    }

    public List<String> pending() {
        return pending;
    }

    /** Fabric mods this game runs from the server: id and version. */
    public static List<String> running() {
        return FabricLoader.getInstance().getAllMods().stream().filter(ColdMods::isCold)
                .map(c -> c.getMetadata().getId() + " " + c.getMetadata().getVersion().getFriendlyString()).sorted().toList();
    }

    /** Already running from its place in mods/, with the catalog's content. */
    private static boolean running(Mod mod, Path target) throws IOException {
        Optional<ModContainer> loaded = FabricLoader.getInstance().getModContainer(mod.id);
        if (loaded.isEmpty() || !isCold(loaded.get())) return false;
        Path origin = loaded.get().getOrigin().getPaths().getFirst();
        return origin.toAbsolutePath().equals(target.toAbsolutePath()) && Files.isRegularFile(target)
                && Arrays.equals(Signing.sha256(Files.readAllBytes(target)), mod.sha256);
    }

    private static boolean isCold(ModContainer container) {
        ModOrigin origin = container.getOrigin();
        return origin.getKind() == ModOrigin.Kind.PATH && origin.getPaths().size() == 1
                && origin.getPaths().getFirst().getFileName().toString().startsWith(PREFIX);
    }

    private static String origin(ModContainer container) {
        ModOrigin origin = container.getOrigin();
        return origin.getKind() == ModOrigin.Kind.PATH ? origin.getPaths().getFirst().getFileName().toString() : "встроен в другой мод";
    }

    /** Every "depends" entry: another mod of the catalog or one this game already runs, in a fitting version. */
    private static void checkDependencies(String id, JsonObject json, Map<String, String> catalog) {
        if (!json.has("depends")) return;
        for (Map.Entry<String, JsonElement> dependency : json.getAsJsonObject("depends").entrySet()) {
            String other = dependency.getKey();
            String installed = catalog.containsKey(other) ? catalog.get(other)
                    : FabricLoader.getInstance().getModContainer(other).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
            if (installed == null) throw new IllegalStateException("мод " + id + " требует " + other + ", а его нет");
            List<String> predicates = new ArrayList<>();
            JsonElement value = dependency.getValue();
            if (value.isJsonArray()) value.getAsJsonArray().forEach(e -> predicates.add(e.getAsString()));
            else predicates.add(value.getAsString());
            if (!matchesAny(predicates, installed)) {
                throw new IllegalStateException("мод " + id + " требует " + other + " " + String.join(" или ", predicates) + ", а есть " + installed);
            }
        }
    }

    private static boolean matchesAny(List<String> predicates, String version) {
        try {
            Version parsed = Version.parse(version);
            for (String predicate : predicates) {
                if (VersionPredicate.parse(predicate).test(parsed)) return true;
            }
            return false;
        } catch (VersionParsingException e) {
            return false;
        }
    }

    private static JsonObject fabricModJson(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IllegalStateException("в файле мода нет fabric.mod.json");
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static @Nullable String string(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : null;
    }

    /** Starts {@link ColdApply} once per game session; it reads the latest plan when the game exits. */
    private void startHelper() throws IOException {
        if (helperStarted) return;
        ColdApply.start(plan);
        helperStarted = true;
        LOG.info("Started the helper that changes mods/ after the game exits");
    }
}
