package ru.dum.sdk.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
