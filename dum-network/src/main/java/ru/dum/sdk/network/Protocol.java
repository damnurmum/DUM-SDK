package ru.dum.sdk.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Wire format of the dum:sdk channel, shared by the client mod and the server plugin.
 * A message is one type byte followed by its fields. The whole custom payload body is the message,
 * with no length prefix: Paper sends and receives plugin messages as raw bytes.
 */
public final class Protocol {

    public static final String CHANNEL = "dum:sdk";

    /** Bumped on every incompatible change. HELLO must always start with it, so any server can read it. */
    public static final int VERSION = 1;

    private static final byte HELLO = 0;
    private static final byte WELCOME = 1;

    private Protocol() {
    }

    public static byte[] encode(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            switch (message) {
                case Message.Hello(int protocol, String sdkVersion) -> {
                    out.writeByte(HELLO);
                    out.writeInt(protocol);
                    out.writeUTF(sdkVersion);
                }
                case Message.Welcome(int protocol, String serverVersion) -> {
                    out.writeByte(WELCOME);
                    out.writeInt(protocol);
                    out.writeUTF(serverVersion);
                }
            }
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** @throws IllegalArgumentException if the bytes are not exactly one valid message */
    public static Message decode(byte[] data) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            Message message = switch (in.readByte()) {
                case HELLO -> new Message.Hello(in.readInt(), in.readUTF());
                case WELCOME -> new Message.Welcome(in.readInt(), in.readUTF());
                default -> throw new IllegalArgumentException("Unknown message type " + data[0]);
            };
            if (in.available() > 0) {
                throw new IllegalArgumentException(in.available() + " trailing bytes after " + message);
            }
            return message;
        } catch (IOException e) {
            throw new IllegalArgumentException("Truncated message", e);
        }
    }
}
