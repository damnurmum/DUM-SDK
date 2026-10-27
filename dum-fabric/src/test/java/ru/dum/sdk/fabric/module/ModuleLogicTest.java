package ru.dum.sdk.fabric.module;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLogicTest {

    private static ModuleManager.Candidate candidate(String id, String version, Map<String, String> dependencies) {
        return new ModuleManager.Candidate(new ModuleManifest(id, version, 1, "x", dependencies, null, null), Path.of(id + ".jar"));
    }

    private static ModuleManifest manifest(String id, Boolean required) {
        return new ModuleManifest(id, "1.0.0", 1, "x", Map.of(), null, required);
    }

    @Test
    void modulesAreRequiredUnlessTheySayOtherwise() {
        List<ModuleManifest> wanted = List.of(manifest("plain", null), manifest("rules", true), manifest("cosmetic", false));
        assertEquals(List.of(), ModuleManager.missingRequired(wanted, id -> true));
        assertEquals(List.of("plain", "rules"), ModuleManager.missingRequired(wanted, id -> false));
        assertEquals(List.of("rules"), ModuleManager.missingRequired(wanted, id -> !id.equals("rules")));
    }

    private static List<String> resolve(ModuleManager.Candidate... candidates) {
        Map<String, ModuleManager.Candidate> found = new LinkedHashMap<>();
        for (ModuleManager.Candidate candidate : candidates) found.put(candidate.manifest().id(), candidate);
        return ModuleManager.resolve(found).stream().map(c -> c.manifest().id()).toList();
    }

    @Test
    void dependenciesStartFirst() {
        assertEquals(List.of("base", "middle", "top"), resolve(
                candidate("top", "1.0.0", Map.of("middle", "*")),
                candidate("middle", "1.0.0", Map.of("base", ">=1.0.0")),
                candidate("base", "1.2.0", Map.of())));
    }

    @Test
    void missingOrWrongDependenciesDropTheModuleAndItsDependents() {
        assertEquals(List.of("alone"), resolve(
                candidate("alone", "1.0.0", Map.of()),
                candidate("needs-ghost", "1.0.0", Map.of("ghost", "*")),
                candidate("needs-needs-ghost", "1.0.0", Map.of("needs-ghost", "*")),
                candidate("too-new", "1.0.0", Map.of("alone", ">=2.0.0"))));
    }

    @Test
    void versionsCompareSemantically() {
        assertTrue(ModuleInstaller.older("0.9.0", "0.10.0"));
        assertTrue(ModuleInstaller.older("1.0.0-beta.1", "1.0.0"));
        assertFalse(ModuleInstaller.older("0.2.0", "0.2.0"));
        assertFalse(ModuleInstaller.older("1.0.1", "1.0.0"));
    }
}
