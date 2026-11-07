package ru.dum.sdk.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format of the dum:sdk channel, shared by the client mod and the server plugin.
 * A message is one type byte followed by its fields. The whole custom payload body is the message,
 * with no length prefix: Paper sends and receives plugin messages as raw bytes.
 */
public final class Protocol {

    public static final String CHANNEL = "dum:sdk";

    /**
     * Bumped on every incompatible change. HELLO must always start with it and the client's SDK version, and WELCOME
     * with the server's protocol and version, so any server and client can read them and tell the versions apart.
     */
    public static final int VERSION = 7;

    /** The first protocol whose clients must have exactly the server's SDK version and can offer to install it. */
    public static final int VERSION_CHECK = 7;

    public static final int MAX_MODULE_COUNT = 1024;
    public static final int MAX_ERROR_DETAIL = 1024;
    public static final int MAX_FAILURE_CONTEXT = 128;
    public static final int MAX_CLIENT_MODS = 1024;
    public static final int MAX_CLIENT_MOD = 200;

    /** Jar bytes per {@link Message.Chunk}; a plugin message may carry up to 1 MiB. */
    public static final int CHUNK_SIZE = 512 * 1024;

    /** Largest module jar a client accepts. */
    public static final int MAX_MODULE_SIZE = 64 * 1024 * 1024;

    private static final byte HELLO = 0;
    private static final byte WELCOME = 1;
    private static final byte MODULE = 2;
    private static final byte CATALOG = 3;
    private static final byte REQUEST = 4;
    private static final byte CHUNK = 5;
    private static final byte READY = 6;
    private static final byte RESTART = 7;
    private static final byte MODULE_FAILURE = 8;
    private static final byte UPDATE = 9;
    private static final byte UPDATED = 10;

    private static final int SHA256 = 32;
    private static final int SIGNATURE = 64;

    private Protocol() {
    }

    public static byte[] encode(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            switch (message) {
                case Message.Hello(int protocol, String sdkVersion, List<String> mods) -> {
                    TransferValidation.clientMods(mods);
                    out.writeByte(HELLO);
                    out.writeInt(protocol);
                    out.writeUTF(sdkVersion);
                    out.writeInt(mods.size());
                    for (String mod : mods) out.writeUTF(mod);
                }
                case Message.Welcome(int protocol, String serverVersion) -> {
                    out.writeByte(WELCOME);
                    out.writeInt(protocol);
                    out.writeUTF(serverVersion);
                }
                case Message.Catalog(List<Message.ModuleInfo> modules) -> {
                    out.writeByte(CATALOG);
                    writeModules(out, modules);
                }
                case Message.Update(List<Message.ModuleInfo> modules) -> {
                    out.writeByte(UPDATE);
                    writeModules(out, modules);
                }
                case Message.Updated(List<String> modules) -> {
                    TransferValidation.started(modules);
                    out.writeByte(UPDATED);
                    out.writeInt(modules.size());
                    for (String module : modules) out.writeUTF(module);
                }
                case Message.Request(byte[] sha256) -> {
                    out.writeByte(REQUEST);
                    writeFixed(out, sha256, SHA256);
                }
                case Message.Chunk(byte[] sha256, int offset, byte[] data) -> {
                    TransferValidation.chunk(new Message.Chunk(sha256, offset, data));
                    // The data runs to the end of the message
                    out.writeByte(CHUNK);
                    writeFixed(out, sha256, SHA256);
                    out.writeInt(offset);
                    out.write(data);
                }
                case Message.Ready ready -> {
                    TransferValidation.ready(ready);
                    out.writeByte(READY);
                    out.writeByte(ready.code().wireId());
                    out.writeUTF(ready.module());
                    out.writeUTF(ready.detail());
                }
                case Message.Restart(List<String> mods) -> {
                    out.writeByte(RESTART);
                    out.writeInt(mods.size());
                    for (String mod : mods) out.writeUTF(mod);
                }
                case Message.Module(String module, byte[] payload) -> {
                    // The data runs to the end of the message
                    out.writeByte(MODULE);
                    out.writeUTF(module);
                    out.write(payload);
                }
                case Message.ModuleFailure failure -> {
                    TransferValidation.failure(failure);
                    out.writeByte(MODULE_FAILURE);
                    out.writeUTF(failure.module());
                    out.writeUTF(failure.where());
                    out.writeUTF(failure.detail());
                }
            }
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Reads only the stable HELLO prefix, even when a different protocol changes the remaining fields. */
    public static int helloProtocol(byte[] data) {
        if (data.length < 5 || data[0] != HELLO) {
            throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", "Missing HELLO prefix");
        }
        return java.nio.ByteBuffer.wrap(data, 1, 4).getInt();
    }

    /** The client's SDK version from the stable HELLO prefix, whatever its protocol puts after it. */
    public static String helloVersion(byte[] data) {
        helloProtocol(data);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 5, data.length - 5));
            return in.readUTF();
        } catch (IOException e) {
            throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", "Missing SDK version in HELLO");
        }
    }

    /** @throws IllegalArgumentException if the bytes are not exactly one valid message */
    public static Message decode(byte[] data) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            Message message = switch (in.readByte()) {
                case HELLO -> {
                    int protocol = in.readInt();
                    String sdkVersion = in.readUTF();
                    int count = in.readInt();
                    if (count < 0 || count > MAX_CLIENT_MODS) throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", "Bad client mod count " + count);
                    List<String> mods = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) mods.add(in.readUTF());
                    TransferValidation.clientMods(mods);
                    yield new Message.Hello(protocol, sdkVersion, List.copyOf(mods));
                }
                case WELCOME -> new Message.Welcome(in.readInt(), in.readUTF());
                case CATALOG -> new Message.Catalog(readModules(in));
                case UPDATE -> new Message.Update(readModules(in));
                case UPDATED -> {
                    int count = in.readInt();
                    if (count < 0 || count > MAX_MODULE_COUNT) throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", "Bad module count " + count);
                    List<String> modules = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) modules.add(in.readUTF());
                    TransferValidation.started(modules);
                    yield new Message.Updated(List.copyOf(modules));
                }
                case REQUEST -> new Message.Request(readFixed(in, SHA256));
                case CHUNK -> {
                    var chunk = new Message.Chunk(readFixed(in, SHA256), in.readInt(), in.readAllBytes());
                    TransferValidation.chunk(chunk);
                    yield chunk;
                }
                case READY -> {
                    var ready = new Message.Ready(ErrorCode.fromWire(in.readUnsignedByte()), in.readUTF(), in.readUTF());
                    TransferValidation.ready(ready);
                    yield ready;
                }
                case RESTART -> {
                    int count = in.readInt();
                    if (count < 0 || count > 1024) throw new IllegalArgumentException("Bad mod count " + count);
                    List<String> mods = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) mods.add(in.readUTF());
                    yield new Message.Restart(List.copyOf(mods));
                }
                case MODULE -> new Message.Module(in.readUTF(), in.readAllBytes());
                case MODULE_FAILURE -> {
                    var failure = new Message.ModuleFailure(in.readUTF(), in.readUTF(), in.readUTF());
                    TransferValidation.failure(failure);
                    yield failure;
                }
                default -> throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", "Unknown message type " + data[0]);
            };
            if (in.available() > 0) {
                throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", in.available() + " trailing bytes after " + message.getClass().getSimpleName());
            }
            return message;
        } catch (IOException e) {
            throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "", "Truncated message");
        }
    }

    private static void writeModules(DataOutputStream out, List<Message.ModuleInfo> modules) throws IOException {
        TransferValidation.catalog(modules);
        out.writeInt(modules.size());
        for (Message.ModuleInfo module : modules) {
            out.writeUTF(module.id());
            out.writeUTF(module.version());
            writeFixed(out, module.sha256(), SHA256);
            out.writeInt(module.size());
            writeFixed(out, module.signature(), SIGNATURE);
            out.writeBoolean(module.fabricMod());
        }
    }

    private static List<Message.ModuleInfo> readModules(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_MODULE_COUNT) throw new ProtocolException(ErrorCode.INVALID_CATALOG, "", "Bad module count " + count);
        List<Message.ModuleInfo> modules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            modules.add(new Message.ModuleInfo(in.readUTF(), in.readUTF(), readFixed(in, SHA256), in.readInt(),
                    readFixed(in, SIGNATURE), readModFlag(in)));
        }
        TransferValidation.catalog(modules);
        return List.copyOf(modules);
    }

    private static boolean readModFlag(DataInputStream in) throws IOException {
        int flag = in.readUnsignedByte();
        if (flag > 1) throw new ProtocolException(ErrorCode.INVALID_CATALOG, "", "Invalid Fabric mod flag");
        return flag == 1;
    }

    private static void writeFixed(DataOutputStream out, byte[] bytes, int length) throws IOException {
        if (bytes.length != length) throw new IllegalArgumentException("Expected " + length + " bytes, got " + bytes.length);
        out.write(bytes);
    }

    private static byte[] readFixed(DataInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
