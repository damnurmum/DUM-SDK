package ru.dum.sdk.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.ErrorCode;

/** Server-owned Russian descriptions; client-provided diagnostic details belong in the log, not the UI. */
final class InstallErrors {
    private InstallErrors() { }

    static Component code(Messages messages, ErrorCode code) {
        return messages.get("handshake.error-code", Placeholder.unparsed("code", code.name()));
    }

    static Component message(Messages messages, Message.Ready result) {
        Component reason = messages.get(result.code().messageKey());
        if (!result.module().isEmpty()) {
            reason = reason.append(messages.get("handshake.error-module", Placeholder.unparsed("module", result.module())));
        }
        return messages.get("handshake.install-failed", Placeholder.component("reason", reason))
                .append(code(messages, result.code()));
    }
}
