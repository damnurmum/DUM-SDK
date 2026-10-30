package ru.dum.sdk.network;

import java.util.Arrays;

/** One requested jar: contiguous, non-empty chunks exactly once, followed by a full content hash check. */
public final class ModuleDownload {
    private final Message.ModuleInfo info;
    private final byte[] data;
    private int received;

    public ModuleDownload(Message.ModuleInfo info) {
        TransferValidation.catalog(java.util.List.of(info));
        this.info = info;
        this.data = new byte[info.size()];
    }

    public Message.ModuleInfo info() { return info; }
    public byte[] data() { return data; }
    public int received() { return received; }
    public boolean complete() { return received == data.length; }

    public void accept(Message.Chunk chunk) {
        TransferValidation.chunk(chunk);
        if (!Arrays.equals(info.sha256(), chunk.sha256())) {
            throw new ProtocolException(ErrorCode.UNEXPECTED_CHUNK, info.id(), "Chunk belongs to another jar");
        }
        if (chunk.offset() != received || chunk.data().length > data.length - received) {
            throw new ProtocolException(ErrorCode.INVALID_CHUNK, info.id(), "Chunk is repeated, out of order or outside the jar");
        }
        System.arraycopy(chunk.data(), 0, data, received, chunk.data().length);
        received += chunk.data().length;
        if (complete() && !Arrays.equals(Signing.sha256(data), info.sha256())) {
            throw new ProtocolException(ErrorCode.HASH_MISMATCH, info.id(), "Downloaded jar hash does not match the catalog");
        }
    }
}
