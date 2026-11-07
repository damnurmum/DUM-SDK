package ru.dum.sdk.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @Test
    void roundTrip() {
        for (Message message : new Message[]{
                new Message.Hello(Protocol.VERSION, "0.1.0", java.util.List.of("fabric-api 0.161.0+26.2", "sodium 0.8.0")),
                new Message.Welcome(Protocol.VERSION, "0.1.0"),
                new Message.Hello(-1, "", java.util.List.of()),
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
        byte[] coldSha = sha.clone();
        coldSha[1] = 1;
        Message.Catalog catalog = (Message.Catalog) Protocol.decode(Protocol.encode(new Message.Catalog(java.util.List.of(
                new Message.ModuleInfo("info", "0.2.0", sha, 1234, signature, false),
                new Message.ModuleInfo("coldtest", "1.0.0", coldSha, 5, signature, true)))));
        Message.Update update = (Message.Update) Protocol.decode(Protocol.encode(new Message.Update(catalog.modules())));
        assertEquals(9, Protocol.encode(update)[0]);
        assertEquals(catalog.modules().size(), update.modules().size());
        assertArrayEquals(coldSha, update.modules().get(1).sha256());
        assertEquals(new Message.Updated(java.util.List.of("info", "wings")),
                Protocol.decode(Protocol.encode(new Message.Updated(java.util.List.of("info", "wings")))));
        assertEquals(10, Protocol.encode(new Message.Updated(java.util.List.of()))[0]);
        assertThrows(ProtocolException.class, () -> Protocol.encode(new Message.Updated(java.util.List.of("../info"))));
        Message.ModuleInfo info = catalog.modules().getFirst();
        assertEquals("info", info.id());
        assertEquals("0.2.0", info.version());
        assertEquals(1234, info.size());
        assertArrayEquals(sha, info.sha256());
        assertArrayEquals(signature, info.signature());
        assertFalse(info.fabricMod());
        assertTrue(catalog.modules().get(1).fabricMod());
        assertEquals(new Message.Restart(java.util.List.of("coldtest", "other")),
                Protocol.decode(Protocol.encode(new Message.Restart(java.util.List.of("coldtest", "other")))));

        assertArrayEquals(sha, ((Message.Request) Protocol.decode(Protocol.encode(new Message.Request(sha)))).sha256());
        byte[] data = new byte[Protocol.CHUNK_SIZE];
        data[5] = 1;
        Message.Chunk chunk = (Message.Chunk) Protocol.decode(Protocol.encode(new Message.Chunk(sha, 1024, data)));
        assertEquals(1024, chunk.offset());
        assertArrayEquals(data, chunk.data());
        // A full chunk must fit into one plugin message
        assertTrue(Protocol.encode(new Message.Chunk(sha, 0, data)).length <= 1024 * 1024);
        assertEquals(Message.Ready.success(), Protocol.decode(Protocol.encode(Message.Ready.success())));
        for (ErrorCode code : ErrorCode.values()) {
            if (code == ErrorCode.OK) continue;
            var failed = new Message.Ready(code, "info", "diagnostic details");
            assertEquals(failed, Protocol.decode(Protocol.encode(failed)));
        }
        assertThrows(IllegalArgumentException.class, () -> Protocol.encode(new Message.Request(new byte[31])));
    }

    @Test
    void runtimeFailureRoundTripsAndRejectsMalformedReports() {
        var report = new Message.ModuleFailure("info", "HUD", "IllegalStateException: failed");
        byte[] encoded = Protocol.encode(report);
        assertEquals(8, encoded[0]);
        assertEquals(report, Protocol.decode(encoded));
        for (var invalid : new Message.ModuleFailure[]{
                new Message.ModuleFailure("", "HUD", "failed"),
                new Message.ModuleFailure("../info", "HUD", "failed"),
                new Message.ModuleFailure("info", "", "failed"),
                new Message.ModuleFailure("info", "x".repeat(Protocol.MAX_FAILURE_CONTEXT + 1), "failed"),
                new Message.ModuleFailure("info", "HUD", "x".repeat(Protocol.MAX_ERROR_DETAIL + 1)),
                new Message.ModuleFailure("info", "HUD", "forged\nlog line"),
                new Message.ModuleFailure("info", "HUD", "\u001b[31mred"),
        }) assertThrows(ProtocolException.class, () -> Protocol.encode(invalid));
        byte[] malformed = encoded.clone();
        malformed[malformed.length - 1] = '\n';
        assertThrows(ProtocolException.class, () -> Protocol.decode(malformed));
        assertThrows(ProtocolException.class, () -> Protocol.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(ProtocolException.class, () -> Protocol.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1)));
    }

    @Test
    void helloRejectsMalformedClientMods() {
        for (var mods : java.util.List.of(java.util.List.of(""), java.util.List.of("mod 1.0\nforged line"),
                java.util.List.of("x".repeat(Protocol.MAX_CLIENT_MOD + 1)),
                java.util.Collections.nCopies(Protocol.MAX_CLIENT_MODS + 1, "mod 1.0"))) {
            assertThrows(ProtocolException.class, () -> Protocol.encode(new Message.Hello(Protocol.VERSION, "0.1.0", mods)));
        }
        byte[] valid = Protocol.encode(new Message.Hello(Protocol.VERSION, "0.1.0", java.util.List.of("mod 1.0")));
        assertThrows(ProtocolException.class, () -> Protocol.decode(java.util.Arrays.copyOf(valid, valid.length - 1)));
        // A protocol 6 HELLO has no mod list: only its stable prefix can be read
        byte[] old = java.util.Arrays.copyOf(valid, 1 + 4 + 2 + "0.1.0".length());
        assertThrows(ProtocolException.class, () -> Protocol.decode(old));
        assertEquals(Protocol.VERSION, Protocol.helloProtocol(old));
    }

    @Test
    void helloStartsWithProtocolVersion() {
        // Type byte, then the protocol as a big-endian int: older and newer servers rely on this layout
        byte[] data = Protocol.encode(new Message.Hello(0x01020304, "x", java.util.List.of()));
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4}, java.util.Arrays.copyOf(data, 5));
        assertEquals(0x01020304, Protocol.helloProtocol(java.util.Arrays.copyOf(data, 5)));
        assertThrows(ProtocolException.class, () -> Protocol.helloProtocol(new byte[]{42, 1, 2, 3, 4}));
        assertThrows(ProtocolException.class, () -> Protocol.helloProtocol(new byte[4]));
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
