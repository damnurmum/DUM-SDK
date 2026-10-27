package ru.dum.sdk.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.dum.sdk.fabric.module.PacketHooks;

/** Packets inside a bundle are handled one by one, straight from the bundle. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {

    @WrapOperation(method = "handleBundlePacket", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void dumsdk$receiveBundled(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        if (PacketHooks.receive(packet)) original.call(packet, listener);
    }
}
