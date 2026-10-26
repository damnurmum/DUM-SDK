package ru.dum.sdk.fabric;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.dum.sdk.network.Protocol;

/**
 * A raw dum:sdk payload. The body is written as is, without a length prefix, because Paper
 * reads and writes plugin messages as plain bytes. {@link Protocol} turns it into a message.
 */
public record SdkPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<SdkPayload> TYPE = new Type<>(Identifier.parse(Protocol.CHANNEL));

    public static final StreamCodec<FriendlyByteBuf, SdkPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new SdkPayload(data);
            });

    @Override
    public Type<SdkPayload> type() {
        return TYPE;
    }
}
