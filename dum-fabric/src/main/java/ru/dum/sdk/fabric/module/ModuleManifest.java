package ru.dum.sdk.fabric.module;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * dum-module.json at the root of a module jar.
 *
 * @param dependencies module id to a version predicate, like {@code ">=1.2.0"} (Fabric syntax)
 * @param loadMode     "hot" (default), the only mode of a module; a mod that needs a restart ships as a whole Fabric mod jar
 * @param required     true (default): without this module the player would play by other rules, so if it does not
 *                     start the server refuses the join, and if it fails in game the player leaves the server.
 *                     false only for modules that change nothing in the game, such as pure cosmetics
 */
record ModuleManifest(String id, String version, int apiVersion, String entrypoint,
                      @Nullable Map<String, String> dependencies, @Nullable String loadMode, @Nullable Boolean required,
                      @Nullable String name, @Nullable String description, @Nullable List<String> authors) {

    ModuleManifest(String id, String version, int apiVersion, String entrypoint,
                   @Nullable Map<String, String> dependencies, @Nullable String loadMode, @Nullable Boolean required) {
        this(id, version, apiVersion, entrypoint, dependencies, loadMode, required, null, null, null);
    }

    String displayName() { return name == null || name.isBlank() ? id : name; }
    String descriptionText() { return description == null ? "" : description; }
    List<String> authorNames() { return authors == null ? List.of() : authors.stream().filter(java.util.Objects::nonNull).toList(); }

    static final String FILE = "dum-module.json";

    /** @throws IOException if the jar or its manifest cannot be read or is invalid */
    static ModuleManifest read(Path jar) throws IOException {
        ModuleManifest manifest;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(FILE);
            if (entry == null) throw new IOException("no " + FILE);
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                // Presentation metadata must not prevent a legacy module from starting.
                for (String field : List.of("name", "description")) {
                    var value = json.get(field);
                    if (value != null && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())) json.remove(field);
                }
                var authors = json.get("authors");
                if (authors != null) {
                    var names = new com.google.gson.JsonArray();
                    if (authors.isJsonArray()) for (var author : authors.getAsJsonArray()) {
                        if (author.isJsonPrimitive() && author.getAsJsonPrimitive().isString()) names.add(author.getAsString());
                    }
                    json.add("authors", names);
                }
                manifest = new Gson().fromJson(json, ModuleManifest.class);
            }
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("broken " + FILE + ": " + e.getMessage(), e);
        }
        if (manifest == null || manifest.id == null || !manifest.id.matches("[a-z0-9_-]+")) {
            throw new IOException("id must be lowercase letters, digits, '-' or '_'");
        }
        if (manifest.entrypoint == null) throw new IOException("no entrypoint");
        try {
            Version.parse(manifest.version);
        } catch (VersionParsingException | NullPointerException e) {
            throw new IOException("bad version '" + manifest.version + "'");
        }
        return manifest;
    }

    Map<String, String> deps() {
        return dependencies != null ? dependencies : Map.of();
    }

    boolean hot() {
        return loadMode == null || loadMode.equals("hot");
    }

    boolean isRequired() {
        return required == null || required;
    }
}
