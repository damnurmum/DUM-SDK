package ru.dum.sdk.fabric.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read only on a worker. Include JVM fatal reports as well as Minecraft's crash reports. */
public record DiagnosticFiles(List<Report> crashes, boolean clientLog, boolean helperLog, String error) {
    public record Report(Path path, Instant modified, long size, String description) { }
    public static final DiagnosticFiles EMPTY = new DiagnosticFiles(List.of(), false, false, "");

    public static DiagnosticFiles scan(Path game) {
        var reports = new ArrayList<Report>();
        var errors = new ArrayList<String>();
        collect(game.resolve("crash-reports"), false, reports, errors);
        collect(game, true, reports, errors);
        List<Report> latest = reports.stream().sorted(Comparator.comparing(Report::modified).reversed()).limit(5).toList();
        return new DiagnosticFiles(latest, Files.isRegularFile(game.resolve("logs/latest.log")),
                Files.isRegularFile(game.resolve("dum-sdk/cold-apply.log")), String.join(" ", errors));
    }

    private static void collect(Path dir, boolean jvm, List<Report> result, List<String> errors) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (jvm ? !name.matches("hs_err_pid[0-9]+\\.log") : !name.endsWith(".txt")) continue;
                result.add(new Report(file, Files.getLastModifiedTime(file).toInstant(), Files.size(file), ""));
            }
        } catch (IOException | SecurityException e) { errors.add("Не удалось прочитать " + dir.getFileName() + ". Проверь права на папку."); }
        // Read a short preview only for the newest report in this directory, never a whole client log.
        result.stream().filter(r -> r.path().getParent().equals(dir)).max(Comparator.comparing(Report::modified)).ifPresent(report -> {
            try (var input = Files.newInputStream(report.path())) {
                String head = new String(input.readNBytes(16 * 1024), StandardCharsets.UTF_8);
                String description = head.lines().filter(line -> line.startsWith("Description:") || line.contains("EXCEPTION_")
                        || line.contains("SIGSEGV") || line.contains("OutOfMemoryError")).findFirst().orElse("Причина указана внутри отчёта");
                result.set(result.indexOf(report), new Report(report.path(), report.modified(), report.size(), description.substring(0, Math.min(description.length(), 300))));
            } catch (IOException e) { errors.add("Не удалось прочитать последний crash-report."); }
        });
    }
}
