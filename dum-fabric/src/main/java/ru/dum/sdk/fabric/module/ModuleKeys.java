package ru.dum.sdk.fabric.module;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/**
 * Module keys while the game runs. The game reads key bindings once, at start, so the SDK adds and removes
 * them itself and keeps their bindings in its own file ({@code dum-sdk/keys.properties}).
 */
final class ModuleKeys {

    private static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("dum-sdk", "modules"));

    private final Path file;

    ModuleKeys(Path root) {
        this.file = root.resolve("keys.properties");
    }

    KeyMapping add(String moduleId, String name, int defaultKey) {
        String id = "key." + moduleId + "." + name;
        if (KeyMapping.ALL.containsKey(id)) throw new IllegalArgumentException("Key " + id + " already exists");
        KeyMapping key = new KeyMapping(id, InputConstants.Type.KEYSYM, defaultKey, CATEGORY);
        String saved = load().getProperty(id);
        if (saved != null) key.setKey(InputConstants.getKey(saved));
        Options options = Minecraft.getInstance().options;
        KeyMapping[] mappings = Arrays.copyOf(options.keyMappings, options.keyMappings.length + 1);
        mappings[mappings.length - 1] = key;
        options.keyMappings = mappings;
        KeyMapping.resetMapping();
        return key;
    }

    /** Takes the keys out of the game and remembers how the player bound them. */
    void remove(Iterable<KeyMapping> keys) {
        Properties saved = load();
        Options options = Minecraft.getInstance().options;
        for (KeyMapping key : keys) {
            saved.setProperty(key.getName(), key.saveString());
            KeyMapping.ALL.remove(key.getName());
            options.keyMappings = Arrays.stream(options.keyMappings).filter(k -> k != key).toArray(KeyMapping[]::new);
        }
        KeyMapping.resetMapping();
        try (Writer writer = Files.newBufferedWriter(file)) {
            saved.store(writer, "Key bindings of DUM-SDK modules");
        } catch (IOException e) {
            LOG.warn("Cannot save {}", file, e);
        }
    }

    private Properties load() {
        Properties saved = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                saved.load(reader);
            } catch (IOException e) {
                LOG.warn("Cannot read {}", file, e);
            }
        }
        return saved;
    }
}
