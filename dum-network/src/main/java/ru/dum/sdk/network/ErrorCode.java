package ru.dum.sdk.network;

import java.util.Locale;

/** Stable wire IDs and diagnostic names. Never encode enum ordinals or reuse an assigned ID. */
public enum ErrorCode {
    OK(0),
    INVALID_CATALOG(1),
    VERSION_ROLLBACK(2),
    UNEXPECTED_CHUNK(3),
    INVALID_CHUNK(4),
    HASH_MISMATCH(5),
    SIGNATURE_MISMATCH(6),
    CACHE_WRITE_FAILED(7),
    INVALID_MODULE(8),
    COLD_MOD_REJECTED(9),
    COLD_MOD_IO(10),
    MODULE_START_FAILED(11),
    RESOURCE_RELOAD_FAILED(12),
    MALFORMED_MESSAGE(13),
    UNEXPECTED_REQUEST(14),
    UNSUPPORTED_PROTOCOL(15),
    HELLO_TIMEOUT(16),
    INSTALL_TIMEOUT(17);

    private final int wireId;

    ErrorCode(int wireId) { this.wireId = wireId; }

    public int wireId() { return wireId; }

    public String messageKey() { return "install-errors." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    public static ErrorCode fromWire(int id) {
        for (ErrorCode code : values()) if (code.wireId == id) return code;
        throw new ProtocolException(MALFORMED_MESSAGE, "", "Unknown error code " + id);
    }
}
