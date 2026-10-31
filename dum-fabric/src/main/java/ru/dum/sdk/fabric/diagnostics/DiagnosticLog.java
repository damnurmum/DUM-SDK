package ru.dum.sdk.fabric.diagnostics;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded local error history. Its single worker orders loading, recording and atomic writes. */
public final class DiagnosticLog {
    private static final int LIMIT = 40;
    private static final Gson JSON = new Gson();
    private final Path file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("DUM diagnostics").factory());
    private volatile List<Entry> entries = List.of();
    private volatile String storageError = "";

    public record Entry(String at, String code, String message) { }

    public DiagnosticLog(Path file) {
        this.file = file;
        worker.execute(() -> {
            if (!Files.exists(file)) return;
            try {
                if (Files.size(file) > 256 * 1024) throw new IllegalArgumentException("History is too large");
                var loaded = new ArrayList<Entry>();
                for (var element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                    Entry entry = JSON.fromJson(element, Entry.class);
                    if (entry != null && entry.at() != null && entry.code() != null && entry.message() != null && loaded.size() < LIMIT) {
                        loaded.add(new Entry(clean(entry.at(), 32), clean(entry.code(), 64), clean(entry.message(), 1600)));
                    }
                }
                entries = List.copyOf(loaded);
            } catch (Exception e) { failed(e); }
        });
    }

    public List<Entry> entries() { return entries; }
    public String storageError() { return storageError; }

    public void record(String code, String message) {
        Entry entry = new Entry(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                clean(code, 64), clean(message, 1600));
        worker.execute(() -> {
            var next = new ArrayList<Entry>();
            next.add(entry);
            next.addAll(entries.subList(0, Math.min(entries.size(), LIMIT - 1)));
            entries = List.copyOf(next);
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, JSON.toJson(entries));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                storageError = "";
            } catch (Exception e) { failed(e); }
        });
    }

    private void failed(Exception error) {
        storageError = "Не удалось прочитать или сохранить историю ошибок. Подробности в latest.log.";
        LoggerFactory.getLogger("DUM-SDK").warn("Cannot access diagnostic history", error);
    }

    private static String clean(String text, int limit) {
        String safe = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return safe.substring(0, Math.min(safe.length(), limit));
    }
}
