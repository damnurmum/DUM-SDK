package ru.dum.sdk.paper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleAccessTest {

    private static final long SECOND = 1_000_000_000L;
    private final UUID player = UUID.randomUUID();

    @Test
    void onlyGrantedModules() {
        ModuleAccess access = new ModuleAccess();
        assertFalse(access.has(player, "info"));
        access.grant(player, List.of("info"));
        assertTrue(access.has(player, "info"));
        assertFalse(access.has(player, "admin"));
        access.forget(player);
        assertFalse(access.has(player, "info"));
    }

    @Test
    void burstThenSteadyRate() {
        ModuleAccess access = new ModuleAccess();
        // A burst of 3 at once goes through, the 4th is dropped
        for (int i = 0; i < 3; i++) assertEquals(ModuleAccess.Verdict.DELIVER, access.take(player, "info", 2, 3, 10, 0));
        assertEquals(ModuleAccess.Verdict.DROP, access.take(player, "info", 2, 3, 10, 0));
        // Half a second at 2 per second earns one more
        assertEquals(ModuleAccess.Verdict.DELIVER, access.take(player, "info", 2, 3, 10, SECOND / 2));
        assertEquals(ModuleAccess.Verdict.DROP, access.take(player, "info", 2, 3, 10, SECOND / 2));
        // Modules have separate limits
        assertEquals(ModuleAccess.Verdict.DELIVER, access.take(player, "other", 2, 3, 10, SECOND / 2));
    }

    @Test
    void floodingDisconnects() {
        ModuleAccess access = new ModuleAccess();
        assertEquals(ModuleAccess.Verdict.DELIVER, access.take(player, "info", 1, 1, 3, 0));
        assertEquals(ModuleAccess.Verdict.DROP, access.take(player, "info", 1, 1, 3, 0));
        assertEquals(ModuleAccess.Verdict.DROP, access.take(player, "info", 1, 1, 3, 0));
        assertEquals(ModuleAccess.Verdict.DISCONNECT, access.take(player, "info", 1, 1, 3, 0));
    }

    @Test
    void aDeliveredMessageResetsTheFloodCount() {
        ModuleAccess access = new ModuleAccess();
        access.take(player, "info", 1, 1, 3, 0);
        access.take(player, "info", 1, 1, 3, 0);
        access.take(player, "info", 1, 1, 3, 0);
        assertEquals(ModuleAccess.Verdict.DELIVER, access.take(player, "info", 1, 1, 3, SECOND));
        assertEquals(ModuleAccess.Verdict.DROP, access.take(player, "info", 1, 1, 3, SECOND));
    }
}
