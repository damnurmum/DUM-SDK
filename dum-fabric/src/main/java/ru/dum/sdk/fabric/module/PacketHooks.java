package ru.dum.sdk.fabric.module;

import net.minecraft.network.protocol.Packet;
import org.jspecify.annotations.Nullable;

/** Called by the SDK's mixins for every packet the client receives or sends. */
public final class PacketHooks {

    static volatile @Nullable ModuleManager manager;

    private PacketHooks() {
    }

    /** On the render thread, before the game handles the packet. False drops it. */
    public static boolean receive(Packet<?> packet) {
        ModuleManager current = manager;
        return current == null || current.packet(packet, true);
    }

    /** On the sending thread, before the packet leaves. False drops it. */
    public static boolean send(Packet<?> packet) {
        ModuleManager current = manager;
        return current == null || current.packet(packet, false);
    }
}
