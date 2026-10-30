package ru.dum.sdk.network;

/** Invalid transfer data with a machine-readable reason and optional module context. */
public final class ProtocolException extends IllegalArgumentException {
    private final ErrorCode code;
    private final String module;

    public ProtocolException(ErrorCode code, String module, String detail) {
        super(detail);
        this.code = code;
        this.module = module;
    }

    public ErrorCode code() { return code; }
    public String module() { return module; }
}
