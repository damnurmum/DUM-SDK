package ru.dum.sdk.fabric.diagnostics;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.Person;
import ru.dum.sdk.fabric.DumSdkClient;
import ru.dum.sdk.fabric.module.ModuleManager;
import ru.dum.sdk.network.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Built from already-loaded metadata and the accepted catalog. Never opens jars on the render thread. */
public final class ModInventory {
    public record Item(String id, String name, String version, String description, List<String> authors,
                       List<String> dependencies, boolean server, String type, String state, String extra) {
        public boolean matches(String query) {
            if (query.isBlank()) return true;
            String text = String.join(" ", id, name, description, String.join(" ", authors)).toLowerCase(Locale.ROOT);
            return text.contains(query.strip().toLowerCase(Locale.ROOT));
        }
    }

    public static List<Item> snapshot() {
        if (!DumSdkClient.serverActive()) return List.of();
        Map<String, ModuleManager.Details> details = DumSdkClient.moduleDetails().stream()
                .collect(Collectors.toMap(ModuleManager.Details::id, Function.identity()));
        var items = new ArrayList<Item>();
        var catalog = DumSdkClient.serverCatalog();
        for (Message.ModuleInfo entry : catalog) {
            var detail = details.get(entry.id());
            ModContainer fabric = entry.fabricMod() ? FabricLoader.getInstance().getModContainer(entry.id()).orElse(null) : null;
            String name = fabric != null ? fabric.getMetadata().getName() : detail != null ? detail.name() : entry.id();
            String description = fabric != null ? fabric.getMetadata().getDescription() : detail != null ? detail.description() : "";
            List<String> authors = fabric != null ? fabric.getMetadata().getAuthors().stream().map(Person::getName).toList()
                    : detail == null ? List.of() : detail.authors();
            List<String> dependencies = fabric != null ? dependencies(fabric) : detail == null ? List.of()
                    : detail.dependencies().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).sorted().toList();
            String extra = "Размер: " + String.format(Locale.ROOT, "%.2f МБ", entry.size() / 1048576.0)
                    + "\nФайл проверен: SHA-256 и подпись DUM";
            if (detail != null) extra += "\nОбязательный: " + (detail.required() ? "да" : "нет")
                    + "\nAPI: " + detail.apiVersion() + ", время кода: " + String.format(Locale.ROOT, "%.2f мс/с", detail.millisPerSecond());
            boolean active = detail != null && detail.active();
            items.add(new Item(entry.id(), name, entry.version(), description, authors, dependencies, true,
                    entry.fabricMod() ? "Мод сервера (Fabric)" : "Модуль сервера",
                    active ? "Активен" : "Не запущен, подробности на вкладке Ошибки", extra));
        }
        return items.stream().sorted(Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Item::id)).toList();
    }

    private static List<String> dependencies(ModContainer mod) {
        return mod.getMetadata().getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.DEPENDS)
                .map(d -> d.getModId() + " " + d.getVersionRequirements().stream().map(Object::toString).collect(Collectors.joining(" | ")))
                .sorted().toList();
    }
}
