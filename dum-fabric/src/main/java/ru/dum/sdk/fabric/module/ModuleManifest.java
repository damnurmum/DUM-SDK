package ru.dum.sdk.fabric.module;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * dum-module.json at the root of a module jar.
 *
 * @param dependencies module id to a version predicate, like {@code ">=1.2.0"} (Fabric syntax)
 * @param loadMode     "hot" (default): loaded while the game runs; "cold": needs a restart, not supported yet
 */
record ModuleManifest(String id, String version, int apiVersion, String entrypoint,
                      @Nullable Map<String, String> dependencies, @Nullable String loadMode) {

    static final String FILE = "dum-module.json";

    /** @throws IOException if the jar or its manifest cannot be read or is invalid */
    static ModuleManifest read(Path jar) throws IOException {
        ModuleManifest manifest;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(FILE);
            if (entry == null) throw new IOException("no " + FILE);
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                manifest = new Gson().fromJson(reader, ModuleManifest.class);
            }
        } catch (JsonParseException e) {
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
}
