package ru.dum.sdk.network;

import java.util.List;

/** A message on the {@value Protocol#CHANNEL} channel. Encoded and decoded by {@link Protocol}. */
public sealed interface Message {

    /** Client to server, sent as soon as the configuration phase starts. */
    record Hello(int protocol, String sdkVersion) implements Message {
    }

    /** Server to client: the handshake is accepted. A {@link Catalog} follows. */
    record Welcome(int protocol, String serverVersion) implements Message {
    }

    /** Server to client: the modules to run on this server. The client fetches what it lacks, then says {@link Ready}. */
    record Catalog(List<ModuleInfo> modules) implements Message {
    }

    /**
     * One module of the catalog.
     *
     * @param sha256    of the jar; also the name the client caches it under
     * @param signature Ed25519 over the jar bytes, see {@link Signing}
     * @param fabricMod a whole Fabric mod (cold load): installed into mods/ and active after a restart
     */
    record ModuleInfo(String id, String version, byte[] sha256, int size, byte[] signature, boolean fabricMod) {
    }

    /** Client to server: send me the jar with this hash. */
    record Request(byte[] sha256) implements Message {
    }

    /** Server to client: a piece of a requested jar, starting at offset. */
    record Chunk(byte[] sha256, int offset, byte[] data) implements Message {
    }

    /** Client to server: OK after installation, or a stable error code plus diagnostic context. */
    record Ready(ErrorCode code, String module, String detail) implements Message {
        public static Ready success() { return new Ready(ErrorCode.OK, "", ""); }
    }

    /** Client to server, instead of {@link Ready}: these Fabric mods change when the game restarts. */
    record Restart(List<String> mods) implements Message {
    }

    /** Either way, in the play phase: data for one client module and its server side. The format is up to the module. */
    record Module(String module, byte[] data) implements Message {
    }
}
