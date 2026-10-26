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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The client modules this server hands out: plugins/DUM-SDK/modules, a jar and its .jar.sig each
 * (./gradlew deploy in the module's project). Clients check the signatures themselves; checking here
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
                JsonObject manifest = manifest(bytes);
                if (manifest == null) {
                    log.warn("Module {} has no readable dum-module.json, not offered", jar.getFileName());
                    continue;
                }
                String id = manifest.get("id").getAsString();
                String version = manifest.get("version").getAsString();
                if (ids.putIfAbsent(id, jar.getFileName().toString()) != null) {
                    log.warn("Module {} ({}): {} already has this id, not offered", id, jar.getFileName(), ids.get(id));
                    continue;
                }
                if (Signing.isDevSigned(bytes, signature)) {
                    log.warn("Module {} is signed with the dev key: only development clients accept it", id);
                }
                byte[] sha = Signing.sha256(bytes);
                found.put(Signing.hex(sha), new Entry(new Message.ModuleInfo(id, version, sha, bytes.length, signature), bytes));
                log.info("Module {} {} ({} KB)", id, version, bytes.length / 1024);
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

    /** The jar with this hash, or null. */
    byte @Nullable [] jar(byte[] sha256) {
        Entry entry = bySha.get(Signing.hex(sha256));
        return entry == null ? null : entry.jar;
    }

    private static @Nullable JsonObject manifest(byte[] jar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals("dum-module.json")) {
                    return JsonParser.parseReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).getAsJsonObject();
                }
            }
        }
        return null;
    }
}
