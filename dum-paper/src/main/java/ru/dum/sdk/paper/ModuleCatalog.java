package ru.dum.sdk.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Signing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The client modules this server hands out: plugins/DUM-SDK/modules, a jar and its .jar.sig each
 * (./gradlew deploy in the module's project). A jar with fabric.mod.json and no dum-module.json is a whole
 * Fabric mod, installed into the client's mods/ and active after a restart (cold load). Clients check the signatures themselves; checking here
 * only catches mistakes early.
 */
final class ModuleCatalog {

    private record Entry(Message.ModuleInfo info, byte[] jar) {
    }

    // ponytail: every jar is kept in memory; fine for a few MB, stream from disk if modules grow large
    private volatile Map<String, Entry> bySha = Map.of();

    /** Reads the folder; I/O on the calling thread. Returns how many modules are offered. */
    int load(Path dir, Logger log) {
        Map<String, Entry> found = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        try {
            Files.createDirectories(dir);
            List<Path> jars;
            try (Stream<Path> files = Files.list(dir)) {
                jars = files.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            }
            for (Path jar : jars) {
                Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
                if (!Files.exists(sig)) {
                    log.warn("Module {} has no {}, not offered", jar.getFileName(), sig.getFileName());
                    continue;
                }
                byte[] bytes = Files.readAllBytes(jar);
                byte[] signature = Files.readAllBytes(sig);
                if (!Signing.verify(bytes, signature, true)) {
                    log.warn("Module {}: the signature does not match any DUM-SDK key, not offered", jar.getFileName());
                    continue;
                }
                Manifest manifest = manifest(bytes);
                if (manifest == null) {
                    log.warn("Module {} has neither dum-module.json nor fabric.mod.json, not offered", jar.getFileName());
                    continue;
                }
                String id = manifest.json.get("id").getAsString();
                String version = manifest.json.get("version").getAsString();
                if (ids.putIfAbsent(id, jar.getFileName().toString()) != null) {
                    log.warn("Module {} ({}): {} already has this id, not offered", id, jar.getFileName(), ids.get(id));
                    continue;
                }
                if (Signing.isDevSigned(bytes, signature)) {
                    log.warn("Module {} is signed with the dev key: only development clients accept it", id);
                }
                byte[] sha = Signing.sha256(bytes);
                found.put(Signing.hex(sha), new Entry(new Message.ModuleInfo(id, version, sha, bytes.length, signature, manifest.fabricMod), bytes));
                log.info("{} {} {} ({} KB)", manifest.fabricMod ? "Fabric mod" : "Module", id, version, bytes.length / 1024);
            }
        } catch (IOException | RuntimeException e) {
            log.error("Cannot read client modules from {}", dir, e);
        }
        bySha = Map.copyOf(found);
        return found.size();
    }

    List<Message.ModuleInfo> modules() {
        List<Message.ModuleInfo> modules = new ArrayList<>();
        for (Entry entry : bySha.values()) modules.add(entry.info);
        return modules;
    }

    /** The modules one player gets: without {@code op}, none of {@code opsOnly}. */
    List<Message.ModuleInfo> modulesFor(boolean op, Collection<String> opsOnly) {
        return modules().stream().filter(module -> op || !opsOnly.contains(module.id())).toList();
    }

    /** The jar with this hash, or null. */
    byte @Nullable [] jar(byte[] sha256) {
        Entry entry = bySha.get(Signing.hex(sha256));
        return entry == null ? null : entry.jar;
    }

    /** dum-module.json of a module, or fabric.mod.json of a whole Fabric mod (cold load). */
    private record Manifest(JsonObject json, boolean fabricMod) {
    }

    private static @Nullable Manifest manifest(byte[] jar) throws IOException {
        JsonObject fabric = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                switch (entry.getName()) {
                    case "dum-module.json" -> {
                        return new Manifest(JsonParser.parseReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).getAsJsonObject(), false);
                    }
                    case "fabric.mod.json" -> fabric = JsonParser.parseReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).getAsJsonObject();
                    default -> {
                    }
                }
            }
        }
        return fabric == null ? null : new Manifest(fabric, true);
    }
}
