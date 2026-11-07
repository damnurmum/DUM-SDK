package ru.dum.sdk.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Signing;
import ru.dum.sdk.network.TransferValidation;
import ru.dum.sdk.network.ProtocolException;
import ru.dum.sdk.network.ServerModContract;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;

/**
 * The client modules this server hands out: a published snapshot of staged jars and their .jar.sig files
 * (./gradlew deploy in the module's project). A jar with fabric.mod.json and no dum-module.json is a whole
 * Fabric mod, installed into the client's mods/ and active after a restart (cold load). Clients check the signatures themselves; checking here
 * only catches mistakes early.
 */
final class ModuleCatalog {

    private record Entry(Message.ModuleInfo info, byte[] jar) {
    }

    // ponytail: every jar is kept in memory; fine for a few MB, stream from disk if modules grow large
    private volatile Map<String, Entry> bySha = Map.of();
    /** Jars of the last {@link #load} left out because they are signed with the dev key. */
    private int devSkipped;

    void replaceWith(ModuleCatalog other) {
        bySha = other.bySha;
    }

    /**
     * Reads the folder; I/O on the calling thread. Returns how many modules are offered.
     *
     * @param allowDevSigned offer dev-signed jars too: only development clients accept them, players' clients would
     *                       refuse the whole catalog, so a live server leaves them out and lets players in without them
     */
    int load(Path dir, Logger log, boolean allowDevSigned) {
        devSkipped = 0;
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
                if (manifest.fabricMod) {
                    try {
                        ServerModContract.entrypoint(manifest.json);
                    } catch (IllegalArgumentException e) {
                        log.warn("Fabric mod {} is not server-scoped: {}, not offered", jar.getFileName(), e.getMessage());
                        continue;
                    }
                }
                String id = manifest.json.get("id").getAsString();
                String version = manifest.json.get("version").getAsString();
                if (ids.putIfAbsent(id, jar.getFileName().toString()) != null) {
                    log.warn("Module {} ({}): {} already has this id, not offered", id, jar.getFileName(), ids.get(id));
                    continue;
                }
                if (Signing.isDevSigned(bytes, signature)) {
                    if (!allowDevSigned) {
                        log.warn("Module {} is signed with the dev key, not offered (allow-dev-signed is off)", id);
                        devSkipped++;
                        continue;
                    }
                    log.warn("Module {} is signed with the dev key: only development clients accept it", id);
                }
                byte[] sha = Signing.sha256(bytes);
                found.put(Signing.hex(sha), new Entry(new Message.ModuleInfo(id, version, sha, bytes.length, signature, manifest.fabricMod), bytes));
                log.info("{} {} {} ({} KB)", manifest.fabricMod ? "Fabric mod" : "Module", id, version, bytes.length / 1024);
            }
        } catch (IOException | RuntimeException e) {
            log.error("Cannot read client modules from {}", dir, e);
        }
        try {
            TransferValidation.catalog(found.values().stream().map(Entry::info).toList());
        } catch (ProtocolException e) {
            log.error("Invalid module catalog [{}]: {}", e.code(), e.getMessage());
            found.clear();
        }
        bySha = Map.copyOf(found);
        return found.size();
    }

    int devSkipped() {
        return devSkipped;
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

    /** Reads only metadata when selecting staged jars; filenames are not module IDs. */
    static String id(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("dum-module.json");
            if (entry == null) entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IOException("No module metadata in " + jar.getFileName());
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                String id = JsonParser.parseReader(reader).getAsJsonObject().get("id").getAsString();
                if (!TransferValidation.moduleId(id)) throw new IOException("Invalid module ID in " + jar.getFileName());
                return id;
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid module metadata in " + jar.getFileName(), e);
        }
    }

    private static final Pattern SEMANTIC = Pattern.compile("(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?");

    /**
     * Semantic version order as the client's rollback check sees it: numeric parts, then a pre-release before
     * its release. False when either version is not semantic, so only a warning can depend on it.
     */
    static boolean older(String version, String than) {
        Matcher a = SEMANTIC.matcher(version), b = SEMANTIC.matcher(than);
        if (!a.matches() || !b.matches()) return false;
        int order = compareParts(a.group(1), b.group(1), true);
        if (order == 0) {
            if (a.group(2) == null || b.group(2) == null) return a.group(2) != null && b.group(2) == null;
            order = compareParts(a.group(2), b.group(2), false);
        }
        return order < 0;
    }

    /** Numbers compare numerically and before text. A missing release part is 0; a shorter pre-release is lower. */
    private static int compareParts(String a, String b, boolean release) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            if (!release && (i >= x.length || i >= y.length)) return Integer.compare(x.length, y.length);
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            boolean pn = p.chars().allMatch(Character::isDigit), qn = q.chars().allMatch(Character::isDigit);
            int order = pn && qn ? new BigInteger(p).compareTo(new BigInteger(q)) : pn != qn ? (pn ? -1 : 1) : p.compareTo(q);
            if (order != 0) return order;
        }
        return 0;
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
