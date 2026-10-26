package ru.dum.sdk.network;

/** A message on the {@value Protocol#CHANNEL} channel. Encoded and decoded by {@link Protocol}. */
public sealed interface Message {

    /** Client to server, sent as soon as the configuration phase starts. */
    record Hello(int protocol, String sdkVersion) implements Message {
    }

    /** Server to client: the handshake is accepted and the player may join. */
    record Welcome(int protocol, String serverVersion) implements Message {
    }

    /** Either way, after the handshake: data for one client module and its server side. The format is up to the module. */
    record Module(String module, byte[] data) implements Message {
    }
}
