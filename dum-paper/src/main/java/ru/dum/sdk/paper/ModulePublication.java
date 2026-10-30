package ru.dum.sdk.paper;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Keeps uploaded jars separate from the catalog the server currently offers. */
final class ModulePublication {

    private final Path staging;
    private final Path snapshots;
    private final Path current;

    ModulePublication(Path data) {
        staging = data.resolve("modules");
        snapshots = data.resolve("published-modules");
        current = snapshots.resolve("current");
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
        Files.createDirectories(staging);
        Files.createDirectories(snapshots);
        Path snapshot = snapshots.resolve("snapshot-" + UUID.randomUUID());
        Files.createDirectory(snapshot);
        boolean committed = false;
        try {
            for (Path jar : jars(staging)) {
                Path signature = jar.resolveSibling(jar.getFileName() + ".sig");
                if (!Files.isRegularFile(signature)) throw new IOException("Missing signature for " + jar.getFileName());
                Files.copy(jar, snapshot.resolve(jar.getFileName()));
                Files.copy(signature, snapshot.resolve(signature.getFileName()));
            }
            ModuleCatalog prepared = validated(snapshot, log);
            Path temporary = snapshots.resolve("current-" + UUID.randomUUID());
            try {
                Files.writeString(temporary, snapshot.getFileName().toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            catalog.replaceWith(prepared);
            committed = true;
            cleanupOldSnapshots(snapshot, log);
            return prepared.modules().size();
        } finally {
            if (!committed) deleteTree(snapshot);
        }
    }

    private Path activeDirectory() throws IOException {
        String name = Files.readString(current, StandardCharsets.UTF_8).trim();
        if (!name.matches("snapshot-[0-9a-fA-F-]+")) throw new IOException("Invalid published catalog pointer");
        Path active = snapshots.resolve(name);
        if (!Files.isDirectory(active)) throw new IOException("Published catalog is missing: " + name);
        return active;
    }

    private static ModuleCatalog validated(Path directory, Logger log) throws IOException {
        int expected = jars(directory).size();
        ModuleCatalog prepared = new ModuleCatalog();
        int loaded = prepared.load(directory, log);
        if (loaded != expected) throw new IOException("Only " + loaded + " of " + expected + " module jars are valid");
        return prepared;
    }

    private static List<Path> jars(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
    }

    private void cleanupOldSnapshots(Path active, Logger log) {
        try (Stream<Path> files = Files.list(snapshots)) {
            for (Path path : files.filter(Files::isDirectory).filter(path -> path.getFileName().toString().startsWith("snapshot-"))
                    .filter(path -> !path.equals(active)).toList()) {
                try {
                    deleteTree(path);
                } catch (IOException e) {
                    log.warn("Cannot remove old published catalog {}", path, e);
                }
            }
        } catch (IOException e) {
            log.warn("Cannot list old published catalogs", e);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
