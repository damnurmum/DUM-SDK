package ru.dum.sdk.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** SDK-owned player texts: local overrides, bundled defaults and MiniMessage placeholders. */
final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Path file;
    private final Supplier<InputStream> bundled;
    /** Configuration threads may read messages while the main thread reloads them. */
    private volatile Map<String, String> texts = Map.of();

    Messages(Path file, Supplier<InputStream> bundled) {
        this.file = file;
        this.bundled = bundled;
    }

    void load() throws IOException {
        byte[] defaults;
        try (InputStream stream = bundled.get()) {
            if (stream == null) throw new IOException("Missing bundled messages.yml");
            defaults = stream.readAllBytes();
        }
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.write(file, defaults);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        config.setDefaults(YamlConfiguration.loadConfiguration(new StringReader(new String(defaults, StandardCharsets.UTF_8))));
        config.options().copyDefaults(true);
        Map<String, String> loaded = new HashMap<>();
        for (String key : config.getKeys(true)) {
            if (config.isList(key)) loaded.put(key, String.join("<newline>", config.getStringList(key)));
            else if (config.isString(key)) loaded.put(key, config.getString(key));
        }
        texts = Map.copyOf(loaded);
    }

    Component get(String key, TagResolver... resolvers) {
        String text = texts.getOrDefault(key, key);
        return text.isEmpty() ? Component.empty() : MINI.deserialize(text, resolvers);
    }
}
