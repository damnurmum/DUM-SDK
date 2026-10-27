package ru.dum.sdk.fabric.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dum.sdk.fabric.module.PacketHooks;

/** Every packet the client sends; a local world's server side uses Connection too and is skipped. */
@Mixin(Connection.class)
abstract class ConnectionMixin {

    @Shadow
    public abstract PacketFlow getReceiving();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void dumsdk$send(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (getReceiving() == PacketFlow.CLIENTBOUND && !PacketHooks.send(packet)) ci.cancel();
    }
}
