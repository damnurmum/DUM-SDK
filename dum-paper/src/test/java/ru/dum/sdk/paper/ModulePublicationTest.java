package ru.dum.sdk.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import ru.dum.sdk.network.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModulePublicationTest {

    @TempDir
    Path data;

    @Test
    void invalidStagedJarCannotReplacePublishedCatalogOrBreakRestart() throws IOException {
        var publication = new ModulePublication(data);
        var log = LoggerFactory.getLogger(ModulePublicationTest.class);
        var catalog = new ModuleCatalog();
        assertEquals(0, publication.load(catalog, log));
        String pointer = Files.readString(data.resolve("published-modules/current"));

        Files.writeString(data.resolve("modules/broken.jar"), "invalid jar");
        assertThrows(IOException.class, () -> publication.publish(catalog, log));
        assertEquals(pointer, Files.readString(data.resolve("published-modules/current")));

        var afterRestart = new ModuleCatalog();
        assertEquals(0, publication.load(afterRestart, log));
        assertEquals(0, afterRestart.modules().size());
    }

    @Test
    void selectedIdsReplaceOnlyTheirJarsAndSurviveRestart() throws IOException {
        var publication = new ModulePublication(data);
        var log = LoggerFactory.getLogger(getClass());
        var catalog = new ModuleCatalog();
        stage("alpha", "1.0.0", "old-name.jar");
        stage("beta", "1.0.0", "other.jar");
        publication.load(catalog, log);
        var before = modules(catalog);
        Files.delete(data.resolve("modules/old-name.jar"));
        // Reuse another module's old filename: selection must use metadata, not filenames.
        stage("alpha", "2.0.0", "other.jar");
        Files.writeString(data.resolve("modules/unrelated-broken.jar"), "not a zip");
        assertEquals(2, publication.publishSelected(catalog, log, List.of("alpha")));
        assertEquals("2.0.0", modules(catalog).get("alpha").version());
        assertEquals("1.0.0", modules(catalog).get("beta").version());
        assertArrayEquals(before.get("beta").sha256(), modules(catalog).get("beta").sha256());
        var afterRestart = new ModuleCatalog();
        publication.load(afterRestart, log);
        assertEquals("2.0.0", modules(afterRestart).get("alpha").version());
        assertArrayEquals(before.get("beta").sha256(), modules(afterRestart).get("beta").sha256());
        assertArrayEquals(catalog.jar(before.get("beta").sha256()), afterRestart.jar(before.get("beta").sha256()));
    }

    @Test
    void multipleIdsAndNewColdModArePublishedTogether() throws IOException {
        var publication = new ModulePublication(data);
        var log = LoggerFactory.getLogger(getClass());
        var catalog = new ModuleCatalog();
        stage("alpha", "1.0.0", "first.jar");
        stage("beta", "1.0.0", "second.jar");
        publication.load(catalog, log);
        stage("alpha", "2.0.0", "first.jar");
        stage("beta", "2.0.0", "second.jar");
        stage("coldprobe", "1.0.0", "cold.jar");
        publication.publishSelected(catalog, log, List.of("alpha", "coldprobe"));
        assertEquals("2.0.0", modules(catalog).get("alpha").version());
        assertEquals("1.0.0", modules(catalog).get("beta").version());
        assertEquals(true, modules(catalog).get("coldprobe").fabricMod());
        stage("coldprobe", "2.0.0", "cold.jar");
        publication.publishSelected(catalog, log, List.of("beta", "coldprobe"));
        assertEquals("2.0.0", modules(catalog).get("beta").version());
        assertEquals("2.0.0", modules(catalog).get("coldprobe").version());
        assertEquals("2.0.0", modules(catalog).get("alpha").version());
    }

    @Test
    void failedSelectionNeverChangesLiveCatalogOrPersistentPointer() throws IOException {
        var publication = new ModulePublication(data);
        var log = LoggerFactory.getLogger(getClass());
        var catalog = new ModuleCatalog();
        stage("alpha", "1.0.0", "first.jar");
        stage("beta", "1.0.0", "second.jar");
        publication.load(catalog, log);
        String pointer = Files.readString(data.resolve("published-modules/current"));
        stage("alpha", "2.0.0", "first.jar");
        for (List<String> ids : List.of(List.of("unknown"), List.of("../alpha"), List.of("alpha", "unknown"), List.<String>of())) {
            assertThrows(IOException.class, () -> publication.publishSelected(catalog, log, ids));
            assertEquals(pointer, Files.readString(data.resolve("published-modules/current")));
            assertEquals("1.0.0", modules(catalog).get("alpha").version());
        }
        Files.delete(data.resolve("modules/second.jar.sig"));
        assertThrows(IOException.class, () -> publication.publishSelected(catalog, log, List.of("alpha", "beta")));
        assertEquals(pointer, Files.readString(data.resolve("published-modules/current")));
        stage("beta", "2.0.0", "second.jar");
        Files.write(data.resolve("modules/second.jar.sig"), new byte[64]);
        assertThrows(IOException.class, () -> publication.publishSelected(catalog, log, List.of("alpha", "beta")));
        assertEquals(pointer, Files.readString(data.resolve("published-modules/current")));
        stage("alpha", "2.0.0", "duplicate.jar");
        assertThrows(IOException.class, () -> publication.publishSelected(catalog, log, List.of("alpha")));
        assertEquals(pointer, Files.readString(data.resolve("published-modules/current")));
        var afterRestart = new ModuleCatalog();
        publication.load(afterRestart, log);
        assertEquals("1.0.0", modules(afterRestart).get("alpha").version());
        assertEquals("1.0.0", modules(afterRestart).get("beta").version());
    }

    @Test
    void disabledPublisherCannotReplaceTheCatalogOrPersistentPointer() throws IOException {
        var publication = new ModulePublication(data);
        var log = LoggerFactory.getLogger(getClass());
        var catalog = new ModuleCatalog();
        stage("alpha", "1.0.0", "first.jar");
        publication.load(catalog, log);
        String pointer = Files.readString(data.resolve("published-modules/current"));
        stage("alpha", "2.0.0", "first.jar");
        publication.cancel();
        assertThrows(IOException.class, () -> publication.publishSelected(catalog, log, List.of("alpha")));
        assertEquals(pointer, Files.readString(data.resolve("published-modules/current")));
        assertEquals("1.0.0", modules(catalog).get("alpha").version());
    }

    @Test
    void fullReloadStillPublishesAllStagedChangesAndRemovals() throws IOException {
        var publication = new ModulePublication(data);
        var log = LoggerFactory.getLogger(getClass());
        var catalog = new ModuleCatalog();
        stage("alpha", "1.0.0", "first.jar");
        stage("beta", "1.0.0", "second.jar");
        publication.load(catalog, log);
        stage("alpha", "2.0.0", "first.jar");
        Files.delete(data.resolve("modules/second.jar"));
        publication.publish(catalog, log);
        assertEquals(1, catalog.modules().size());
        assertEquals("2.0.0", modules(catalog).get("alpha").version());
    }

    @Test
    void publishingALowerVersionIsReportedAsARollback() throws IOException {
        var publication = new ModulePublication(data);
        var log = LoggerFactory.getLogger(getClass());
        var catalog = new ModuleCatalog();
        stage("alpha", "2.0.0", "alpha.jar");
        publication.load(catalog, log);
        var before = catalog.modules();
        stage("alpha", "1.0.0", "alpha.jar");
        publication.publish(catalog, log);
        assertEquals(List.of("alpha 1.0.0"), DumSdk.rollbacks(before, catalog.modules()));
        assertEquals(List.of(), DumSdk.rollbacks(catalog.modules(), before));
    }

    @Test
    void versionOrderMatchesSemanticVersions() {
        assertTrue(ModuleCatalog.older("1.9.9", "1.10.0"));
        assertTrue(ModuleCatalog.older("1.0", "1.0.1"));
        assertTrue(ModuleCatalog.older("1.0.0-beta.2", "1.0.0"));
        assertTrue(ModuleCatalog.older("1.0.0-alpha", "1.0.0-alpha.1"));
        assertTrue(ModuleCatalog.older("1.0.0-2", "1.0.0-rc"));
        assertTrue(ModuleCatalog.older("1.0.0-beta.2", "1.0.0-beta.10"));
        assertFalse(ModuleCatalog.older("1.0.0", "1.0.0+build.5"));
        assertFalse(ModuleCatalog.older("1.0", "1.0.0"));
        assertFalse(ModuleCatalog.older("2.0.0", "1.99.99"));
        assertFalse(ModuleCatalog.older("1.0.0", "1.0.0-rc.1"));
        assertFalse(ModuleCatalog.older("dev", "1.0.0"));
    }

    private void stage(String id, String version, String name) throws IOException {
        Path directory = data.resolve("modules");
        Files.createDirectories(directory);
        for (String suffix : List.of("", ".sig")) {
            try (var input = getClass().getResourceAsStream("/publication/" + id + "-" + version + ".zip" + suffix)) {
                if (input == null) throw new IOException("Missing publication fixture");
                Files.copy(input, directory.resolve(name + suffix), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Map<String, Message.ModuleInfo> modules(ModuleCatalog catalog) {
        return catalog.modules().stream().collect(Collectors.toMap(Message.ModuleInfo::id, Function.identity()));
    }
}
