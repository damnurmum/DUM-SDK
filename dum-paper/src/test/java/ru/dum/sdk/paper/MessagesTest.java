package ru.dum.sdk.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @TempDir Path directory;

    private static final String DEFAULTS = """
            handshake:
              missing: "<green>Install the SDK, <name>"
              timeout: "Try again"
            empty: "default text"
            lines:
              - "first"
              - "second"
            """;

    private Messages messages() {
        return new Messages(directory.resolve("messages.yml"),
                () -> new ByteArrayInputStream(DEFAULTS.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Component value) {
        return PlainTextComponentSerializer.plainText().serialize(value);
    }

    @Test
    void firstLoadCreatesTheFileAndRendersUnparsedPlaceholders() throws IOException {
        Messages messages = messages();
        messages.load();
        assertEquals(DEFAULTS, Files.readString(directory.resolve("messages.yml")));
        assertEquals("Install the SDK, <red>player", text(messages.get("handshake.missing", Placeholder.unparsed("name", "<red>player"))));
        assertEquals("first\nsecond", text(messages.get("lines")));
        assertEquals("unknown.key", text(messages.get("unknown.key")));
    }

    @Test
    void oldConfigKeepsOverridesEmptyStringsAndFallbacksWithoutRewritingIt() throws IOException {
        String old = """
                handshake:
                  missing: "Custom <name>"
                empty: ""
                """;
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, old);
        Messages messages = messages();
        messages.load();
        assertEquals("Custom player", text(messages.get("handshake.missing", Placeholder.unparsed("name", "player"))));
        assertEquals("Try again", text(messages.get("handshake.timeout")));
        assertEquals(Component.empty(), messages.get("empty"));
        assertEquals("first\nsecond", text(messages.get("lines")));
        assertEquals(old, Files.readString(file));

        Files.writeString(file, "handshake:\n  timeout: Updated\nlines:\n  - new\n");
        messages.load();
        assertEquals("Updated", text(messages.get("handshake.timeout")));
        assertEquals("Install the SDK, player", text(messages.get("handshake.missing", Placeholder.unparsed("name", "player"))));
        assertEquals("default text", text(messages.get("empty")));
        assertEquals("new", text(messages.get("lines")));
    }

    @Test
    void missingBundledResourceDoesNotOverwriteExistingMessages() throws IOException {
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "empty: keep");
        Messages messages = new Messages(file, () -> null);
        assertThrows(IOException.class, messages::load);
        assertEquals("empty: keep", Files.readString(file));
    }
}
