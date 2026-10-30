package ru.dum.sdk.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
