package ru.dum.sdk.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @Test
    void roundTrip() {
        for (Message message : new Message[]{
                new Message.Hello(Protocol.VERSION, "0.1.0"),
                new Message.Welcome(Protocol.VERSION, "0.1.0"),
                new Message.Hello(-1, ""),
        }) {
            assertEquals(message, Protocol.decode(Protocol.encode(message)));
        }
    }

    @Test
    void moduleRoundTrip() {
        for (byte[] data : new byte[][]{{}, {0, 1, -1}, new byte[30_000]}) {
            Message.Module decoded = (Message.Module) Protocol.decode(Protocol.encode(new Message.Module("info", data)));
            assertEquals("info", decoded.module());
            assertArrayEquals(data, decoded.data());
        }
    }

    @Test
    void installMessagesRoundTrip() {
        byte[] sha = new byte[32];
        sha[0] = 7;
        byte[] signature = new byte[64];
        signature[63] = 9;
        Message.Catalog catalog = (Message.Catalog) Protocol.decode(Protocol.encode(new Message.Catalog(java.util.List.of(
                new Message.ModuleInfo("info", "0.2.0", sha, 1234, signature)))));
        Message.ModuleInfo info = catalog.modules().getFirst();
        assertEquals("info", info.id());
        assertEquals("0.2.0", info.version());
        assertEquals(1234, info.size());
        assertArrayEquals(sha, info.sha256());
        assertArrayEquals(signature, info.signature());

        assertArrayEquals(sha, ((Message.Request) Protocol.decode(Protocol.encode(new Message.Request(sha)))).sha256());
        byte[] data = new byte[Protocol.CHUNK_SIZE];
        data[5] = 1;
        Message.Chunk chunk = (Message.Chunk) Protocol.decode(Protocol.encode(new Message.Chunk(sha, 1024, data)));
        assertEquals(1024, chunk.offset());
        assertArrayEquals(data, chunk.data());
        // A full chunk must fit into one plugin message
        assertTrue(Protocol.encode(new Message.Chunk(sha, 0, data)).length <= 1024 * 1024);
        assertEquals(new Message.Ready("модуль info: подпись не подходит"),
                Protocol.decode(Protocol.encode(new Message.Ready("модуль info: подпись не подходит"))));
        assertThrows(IllegalArgumentException.class, () -> Protocol.encode(new Message.Request(new byte[31])));
    }

    @Test
    void helloStartsWithProtocolVersion() {
        // Type byte, then the protocol as a big-endian int: older and newer servers rely on this layout
        byte[] data = Protocol.encode(new Message.Hello(0x01020304, "x"));
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4}, java.util.Arrays.copyOf(data, 5));
    }

    @Test
    void rejectsMalformed() {
        byte[] valid = Protocol.encode(new Message.Welcome(1, "0.1.0"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Protocol.decode(new byte[]{42}));
        assertThrows(IllegalArgumentException.class, () -> Protocol.decode(java.util.Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> Protocol.decode(java.util.Arrays.copyOf(valid, valid.length + 1)));
    }
}
