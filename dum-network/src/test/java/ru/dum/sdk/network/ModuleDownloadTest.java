package ru.dum.sdk.network;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ModuleDownloadTest {
    private static Message.ModuleInfo info(byte[] data) {
        return new Message.ModuleInfo("probe", "1.0.0", Signing.sha256(data), data.length, new byte[64], false);
    }

    private static Message.Chunk wireChunk(Message.ModuleInfo info, int offset, byte[] data) {
        return (Message.Chunk) Protocol.decode(Protocol.encode(new Message.Chunk(info.sha256(), offset, data)));
    }

    @Test
    void acceptsContiguousChunksIncludingTheFinalShortChunkThroughTheCodec() {
        byte[] bytes = new byte[Protocol.CHUNK_SIZE + 19];
        Arrays.fill(bytes, (byte) 7);
        var info = info(bytes);
        var download = new ModuleDownload(info);
        download.accept(wireChunk(info, 0, Arrays.copyOf(bytes, Protocol.CHUNK_SIZE)));
        assertFalse(download.complete());
        assertEquals(Protocol.CHUNK_SIZE, download.received());
        download.accept(wireChunk(info, Protocol.CHUNK_SIZE, Arrays.copyOfRange(bytes, Protocol.CHUNK_SIZE, bytes.length)));
        assertTrue(download.complete());
        assertArrayEquals(bytes, download.data());
        assertEquals(ErrorCode.INVALID_CHUNK, assertThrows(ProtocolException.class,
                () -> download.accept(wireChunk(info, 0, new byte[]{7}))).code());
    }

    @Test
    void refusesRepeatedOverlappingSkippedAndOversizedPartsWithoutAdvancingProgress() {
        var info = info(new byte[8]);
        var download = new ModuleDownload(info);
        download.accept(wireChunk(info, 0, new byte[3]));
        for (int offset : new int[]{0, 1, 2, 4, 8}) {
            assertEquals(ErrorCode.INVALID_CHUNK, assertThrows(ProtocolException.class,
                    () -> download.accept(wireChunk(info, offset, new byte[1]))).code());
            assertEquals(3, download.received());
        }
        assertThrows(ProtocolException.class, () -> download.accept(wireChunk(info, 3, new byte[6])));
        assertEquals(3, download.received());
        assertThrows(ProtocolException.class, () -> Protocol.encode(new Message.Chunk(info.sha256(), 3, new byte[0])));
        assertThrows(ProtocolException.class, () -> Protocol.encode(new Message.Chunk(info.sha256(), -1, new byte[1])));
        assertThrows(ProtocolException.class, () -> Protocol.encode(new Message.Chunk(info.sha256(), 3, new byte[Protocol.CHUNK_SIZE + 1])));
    }

    @Test
    void distinguishesAnUnexpectedJarFromCorruptedContent() {
        var info = info(new byte[3]);
        var download = new ModuleDownload(info);
        assertEquals(ErrorCode.UNEXPECTED_CHUNK, assertThrows(ProtocolException.class,
                () -> download.accept(new Message.Chunk(new byte[32], 0, new byte[3]))).code());
        assertEquals(0, download.received());
        assertEquals(ErrorCode.HASH_MISMATCH, assertThrows(ProtocolException.class,
                () -> download.accept(wireChunk(info, 0, new byte[]{1, 2, 3}))).code());
    }
}
