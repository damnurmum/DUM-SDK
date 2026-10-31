package ru.dum.sdk.fabric.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ModuleMetadataTest {
    @TempDir Path root;
    private ModuleManifest read(String fields) throws Exception {
        Path jar = root.resolve("module.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("dum-module.json"));
            zip.write(("{\"id\":\"probe\",\"version\":\"1.0.0\",\"apiVersion\":1,\"entrypoint\":\"Probe\"" + fields + "}").getBytes(StandardCharsets.UTF_8));
        }
        return ModuleManifest.read(jar);
    }

    @Test void oldSignedJarMetadataRemainsOptionalWithoutInventingDescriptions() throws Exception {
        var manifest = read("");
        assertEquals("probe", manifest.displayName());
        assertEquals("", manifest.descriptionText());
        assertTrue(manifest.authorNames().isEmpty());
        assertTrue(manifest.isRequired());
    }

    @Test void descriptiveMetadataAndDependenciesAreAvailableForTheHub() throws Exception {
        var manifest = read(",\"name\":\"Probe module\",\"description\":\"Module details\",\"authors\":[\"Pavel\"],\"dependencies\":{\"base\":\">=1.0.0\"},\"required\":false");
        assertEquals("Probe module", manifest.displayName());
        assertEquals("Module details", manifest.descriptionText());
        assertEquals(List.of("Pavel"), manifest.authorNames());
        assertEquals(">=1.0.0", manifest.deps().get("base"));
        assertFalse(manifest.isRequired());
    }

    @Test void unknownPresentationShapesDoNotBreakAnOtherwiseValidLegacyModule() throws Exception {
        var manifest = read(",\"name\":{\"localized\":\"Name\"},\"description\":[],\"authors\":[{\"name\":\"Object author\"},\"DUM\"]");
        assertEquals("probe", manifest.displayName());
        assertEquals("", manifest.descriptionText());
        assertEquals(List.of("DUM"), manifest.authorNames());
        assertEquals("Probe", manifest.entrypoint());
    }
}
