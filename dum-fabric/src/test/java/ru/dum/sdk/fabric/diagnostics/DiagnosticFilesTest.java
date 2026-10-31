package ru.dum.sdk.fabric.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticFilesTest {
    @TempDir Path game;

    @Test void newestReportUsesFileTimeAndIncludesJvmCrashesWithoutReadingWholeLogs() throws Exception {
        Files.createDirectories(game.resolve("crash-reports"));
        Path minecraft = game.resolve("crash-reports/crash-client.txt");
        Files.writeString(minecraft, "Description: Test failure\nPRIVATE CONTENT MUST NOT BE PREVIEWED\n");
        Files.setLastModifiedTime(minecraft, FileTime.fromMillis(1000));
        Path jvm = game.resolve("hs_err_pid42.log");
        Files.writeString(jvm, "# SIGSEGV in native code\n");
        Files.setLastModifiedTime(jvm, FileTime.fromMillis(2000));
        var result = DiagnosticFiles.scan(game);
        assertEquals(jvm, result.crashes().getFirst().path());
        assertTrue(result.crashes().getFirst().description().contains("SIGSEGV"));
        assertEquals("Description: Test failure", result.crashes().get(1).description());
        assertFalse(result.clientLog());
        assertTrue(DiagnosticFiles.scan(game.resolve("missing")).crashes().isEmpty());
    }

    @Test void persistedHistorySurvivesAnotherInstanceAndStaysBounded() throws Exception {
        Path file = game.resolve("dum-sdk/diagnostics.json");
        var log = new DiagnosticLog(file);
        for (int i = 0; i < 45; i++) log.record("UPDATE_NETWORK", "Event " + i);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.readString(file).contains("Event 44")) break;
            Thread.sleep(10);
        }
        var restored = new DiagnosticLog(file);
        while (restored.entries().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(40, restored.entries().size());
        assertEquals("Event 44", restored.entries().getFirst().message());
        assertEquals("Event 5", restored.entries().getLast().message());
    }
}
