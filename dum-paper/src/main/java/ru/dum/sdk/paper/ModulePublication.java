package ru.dum.sdk.paper;

import org.slf4j.Logger;
import ru.dum.sdk.network.TransferValidation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Keeps uploaded jars separate from the catalog the server currently offers. */
final class ModulePublication {

    private final Path staging;
    private final Path snapshots;
    private final Path current;
    private boolean cancelled;
    private volatile boolean allowDevSigned;

    ModulePublication(Path data) {
        staging = data.resolve("modules");
        snapshots = data.resolve("published-modules");
        current = snapshots.resolve("current");
    }

    /** Whether dev-signed jars are offered (allow-dev-signed in the config); read before each load or publication. */
    void allowDevSigned(boolean allow) {
        allowDevSigned = allow;
    }

    /** A disabled plugin must not commit an asynchronous publication after its replacement loads. */
    synchronized void cancel() {
        cancelled = true;
    }

    int load(ModuleCatalog catalog, Logger log) throws IOException {
        Files.createDirectories(staging);
        Files.createDirectories(snapshots);
        if (!Files.exists(current)) {
            int count = publish(catalog, log);
            log.info("Created the initial published catalog from the existing modules folder");
            return count;
        }
        Path active = activeDirectory();
        ModuleCatalog loaded = validated(active, log);
        catalog.replaceWith(loaded);
        return loaded.modules().size();
    }

    /** Copies and checks staging before atomically making it the catalog used after a server restart. */
    int publish(ModuleCatalog catalog, Logger log) throws IOException {
        return publish(catalog, log, Set.of());
    }

    /** Replaces only selected IDs; the rest comes from the persistent published snapshot. */
    int publishSelected(ModuleCatalog catalog, Logger log, Collection<String> ids) throws IOException {
        if (ids.isEmpty() || ids.stream().anyMatch(id -> !TransferValidation.moduleId(id))) {
            throw new IOException("Specify valid module IDs");
        }
        return publish(catalog, log, Set.copyOf(ids));
    }

    private int publish(ModuleCatalog catalog, Logger log, Set<String> selected) throws IOException {
        Files.createDirectories(staging);
        Files.createDirectories(snapshots);
        List<Path> oldSnapshots;
        try (Stream<Path> files = Files.list(snapshots)) {
            oldSnapshots = files.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("snapshot-")).toList();
        }
        Path snapshot = snapshots.resolve("snapshot-" + UUID.randomUUID());
        Files.createDirectory(snapshot);
        boolean committed = false;
        try {
            if (selected.isEmpty()) {
                for (Path jar : jars(staging)) copy(jar, snapshot, jar.getFileName().toString());
            } else {
                Map<String, Path> replacements = new HashMap<>();
                for (Path jar : jars(staging)) {
                    String id;
                    try {
                        id = ModuleCatalog.id(jar);
                    } catch (IOException e) {
                        log.warn("Cannot inspect staged jar {}: {}", jar.getFileName(), e.getMessage());
                        continue;
                    }
                    if (selected.contains(id) && replacements.putIfAbsent(id, jar) != null) {
                        throw new IOException("Multiple staged jars for " + id);
                    }
                }
                for (String id : selected) {
                    Path jar = replacements.get(id);
                    if (jar == null) throw new IOException("No staged jar for " + id);
                    copySelected(jar, snapshot, id);
                }
                for (Path jar : jars(activeDirectory())) {
                    String id = ModuleCatalog.id(jar);
                    if (!selected.contains(id)) copySelected(jar, snapshot, id);
                }
            }
            ModuleCatalog prepared = validated(snapshot, log);
            Path temporary = snapshots.resolve("current-" + UUID.randomUUID());
            try {
                Files.writeString(temporary, snapshot.getFileName().toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                synchronized (this) {
                    if (cancelled) throw new IOException("Publication cancelled because the plugin was disabled");
                    Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    catalog.replaceWith(prepared);
                    committed = true;
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            cleanupOldSnapshots(oldSnapshots, log);
            return prepared.modules().size();
        } finally {
            if (!committed) deleteTree(snapshot);
        }
    }

    private static void copy(Path jar, Path snapshot, String name) throws IOException {
        Path signature = jar.resolveSibling(jar.getFileName() + ".sig");
        if (!Files.isRegularFile(signature)) throw new IOException("Missing signature for " + jar.getFileName());
        Files.copy(jar, snapshot.resolve(name));
        Files.copy(signature, snapshot.resolve(name + ".sig"));
    }

    private static void copySelected(Path jar, Path snapshot, String id) throws IOException {
        copy(jar, snapshot, id + ".jar");
        if (!id.equals(ModuleCatalog.id(snapshot.resolve(id + ".jar")))) {
            throw new IOException("Module metadata changed while copying " + jar.getFileName());
        }
    }

    private Path activeDirectory() throws IOException {
        String name = Files.readString(current, StandardCharsets.UTF_8).trim();
        if (!name.matches("snapshot-[0-9a-fA-F-]+")) throw new IOException("Invalid published catalog pointer");
        Path active = snapshots.resolve(name);
        if (!Files.isDirectory(active)) throw new IOException("Published catalog is missing: " + name);
        return active;
    }

    private ModuleCatalog validated(Path directory, Logger log) throws IOException {
        int expected = jars(directory).size();
        ModuleCatalog prepared = new ModuleCatalog();
        int loaded = prepared.load(directory, log, allowDevSigned);
        // A dev-signed jar left out is not an error: the rest is published and players join without it
        if (loaded + prepared.devSkipped() != expected) throw new IOException("Only " + loaded + " of " + expected + " module jars are valid");
        return prepared;
    }

    private static List<Path> jars(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
    }

    private void cleanupOldSnapshots(List<Path> previous, Logger log) {
        // A replacement plugin may already be preparing a newer snapshot. Never delete its directories.
        for (Path path : previous) {
            try {
                deleteTree(path);
            } catch (IOException e) {
                log.warn("Cannot remove old published catalog {}", path, e);
            }
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
