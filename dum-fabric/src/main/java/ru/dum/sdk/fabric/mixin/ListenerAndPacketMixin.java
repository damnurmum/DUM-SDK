package ru.dum.sdk.fabric.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dum.sdk.fabric.module.PacketHooks;

/** Every packet the game handles on the render thread passes here, server-bound ones of a local world too. */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
abstract class ListenerAndPacketMixin {

    @Shadow
    @Final
    private PacketListener listener;

    @Shadow
    @Final
    private Packet<?> packet;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void dumsdk$receive(CallbackInfo ci) {
        if (listener instanceof ClientCommonPacketListenerImpl && !PacketHooks.receive(packet)) ci.cancel();
    }
}
