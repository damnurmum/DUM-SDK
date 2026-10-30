package ru.dum.sdk.network;

import java.util.HashSet;
import java.util.List;

/** Validates the entire catalog before any file is requested or a download buffer is allocated. */
public final class TransferValidation {
    private TransferValidation() { }

    public static boolean moduleId(String id) {
        return id != null && id.length() <= 64 && id.matches("[a-z0-9_-]+");
    }

    public static void catalog(List<Message.ModuleInfo> modules) {
        if (modules == null || modules.size() > Protocol.MAX_MODULE_COUNT) invalid("", "Invalid module count");
        var ids = new HashSet<String>();
        var hashes = new HashSet<String>();
        for (var module : modules) {
            if (module == null || !moduleId(module.id())) invalid("", "Invalid module ID");
            String id = module.id();
            if (!ids.add(id)) invalid(id, "Duplicate module ID");
            if (module.version() == null || module.version().isBlank() || module.version().length() > 128) invalid(id, "Invalid version");
            if (module.size() <= 0 || module.size() > Protocol.MAX_MODULE_SIZE) invalid(id, "Invalid jar size");
            if (module.sha256() == null || module.sha256().length != 32) invalid(id, "Invalid SHA-256 length");
            if (module.signature() == null || module.signature().length != 64) invalid(id, "Invalid signature length");
            if (!hashes.add(Signing.hex(module.sha256()))) invalid(id, "Duplicate jar hash");
        }
    }

    public static void chunk(Message.Chunk chunk) {
        if (chunk.offset() < 0 || chunk.data().length == 0 || chunk.data().length > Protocol.CHUNK_SIZE) {
            throw new ProtocolException(ErrorCode.INVALID_CHUNK, "", "Invalid chunk offset or length");
        }
    }

    public static void ready(Message.Ready ready) {
        if (ready.code() == null || ready.module() == null || (!ready.module().isEmpty() && !moduleId(ready.module()))
                || ready.detail() == null || ready.detail().length() > Protocol.MAX_ERROR_DETAIL
                || (ready.code() == ErrorCode.OK && (!ready.module().isEmpty() || !ready.detail().isEmpty()))) {
            throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", "Invalid installation result");
        }
    }

    private static void invalid(String module, String detail) {
        throw new ProtocolException(ErrorCode.INVALID_CATALOG, module, detail);
    }
}
