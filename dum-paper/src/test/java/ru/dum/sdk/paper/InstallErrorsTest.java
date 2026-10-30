package ru.dum.sdk.paper;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.dum.sdk.network.ErrorCode;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class InstallErrorsTest {
    @TempDir Path directory;

    @Test
    void everyWireCodeHasServerOwnedTextWithOldFileFallbacksAndNoClientMarkup() throws Exception {
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "handshake:\n  install-failed: Custom <reason>\n");
        var messages = new Messages(file, () -> getClass().getResourceAsStream("/messages.yml"));
        messages.load();
        for (ErrorCode code : ErrorCode.values()) {
            if (code == ErrorCode.OK) continue;
            var ready = (Message.Ready) Protocol.decode(Protocol.encode(new Message.Ready(code, "probe", "<red>UNTRUSTED DETAILS")));
            String text = PlainTextComponentSerializer.plainText().serialize(InstallErrors.message(messages, ready));
            assertTrue(text.startsWith("Custom "));
            assertTrue(text.contains("Код: " + code.name()));
            assertTrue(text.contains("Модуль: probe"));
            assertFalse(text.contains("install-errors."));
            assertFalse(text.contains("UNTRUSTED DETAILS"));
            assertTrue(text.matches("(?s).*\\p{IsCyrillic}.*"));
        }
    }
}
