package ru.dum.sdk.test;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import ru.dum.sdk.api.DumContext;
import ru.dum.sdk.api.DumModule;
import ru.dum.sdk.api.WorldDraw;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises the SDK hooks, for testing under vanilla, Sodium and Iris:
 * - world rendering: a gizmo box and line through walls around the block under the crosshair, and
 *   {@link WorldDraw} shapes next to a debug quad, which shader packs do not draw;
 * - Fabric events: attacking, breaking a block and using an item are reported in chat;
 * - packets: counters on the HUD, damage events in chat, and a key that drops the server's sound packets;
 * - diagnostics: a key that makes the module throw.
 */
public final class SdkTestModule implements DumModule {

    /** A flat 1x1 square at the pose's origin. */
    private static final float[][] SQUARE = {{0, 0, 0}, {0, 0, 1}, {1, 0, 1}, {1, 0, 0}};

    private final AtomicInteger received = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();
    private volatile boolean muted;
    private int dropped;

    @Override
    public void onEnable(DumContext context) {
        context.on(LevelRenderEvents.BEFORE_GIZMOS, levelContext -> {
            BlockPos target = target();
            if (target == null) return;
            Gizmos.cuboid(target, GizmoStyle.strokeAndFill(0xFFFF3B3B, 2.5f, 0x30FF3B3B)).setAlwaysOnTop();
            Vec3 from = Minecraft.getInstance().player.getEyePosition().add(0, -0.4, 0);
            Gizmos.line(from, Vec3.atCenterOf(target), 0xFFFFCC33, 2f).setAlwaysOnTop();
        });
        context.on(LevelRenderEvents.COLLECT_SUBMITS, levelContext -> {
            BlockPos target = target();
            if (target == null) return;
            // Render types side by side, to see which ones shader packs (Iris) draw:
            // lines around the target, see-through and depth-tested quads above it, a debug quad to the side
            Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
            PoseStack pose = levelContext.poseStack();
            pose.pushPose();
            pose.translate(target.getX() - camera.x, target.getY() - camera.y, target.getZ() - camera.z);
            var submits = levelContext.submitNodeCollector();
            submits.submitCustomGeometry(pose, WorldDraw.LINES, (p, buffer) -> WorldDraw.box(p, buffer, -0.05f, -0.05f, -0.05f, 1.05f, 1.05f, 1.05f, 0xFF33FF66, 3));
            pose.translate(0, 1.5, 0);
            submits.submitCustomGeometry(pose, WorldDraw.QUADS_SEE_THROUGH, (p, buffer) -> WorldDraw.quad(p, buffer, SQUARE, 0xA033AAFF));
            pose.translate(0, 0.6, 0);
            submits.submitCustomGeometry(pose, WorldDraw.QUADS, (p, buffer) -> WorldDraw.quad(p, buffer, SQUARE, 0xA0FFAA33));
            // A debug pipeline for comparison: shader packs do not draw it
            pose.translate(1.5, -1.05, 0);
            submits.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, buffer) -> WorldDraw.quad(p, buffer, SQUARE, 0xA0FF33FF));
            pose.popPose();
        });

        context.on(AttackEntityCallback.EVENT, (player, level, hand, entity, hit) -> {
            say("событие: удар по " + entity.getType().getDescription().getString());
            return InteractionResult.PASS;
        });
        context.on(ClientPlayerBlockBreakEvents.AFTER, (level, player, pos, state) ->
                say("событие: сломан блок " + state.getBlock().getName().getString() + " " + pos.toShortString()));
        context.on(ItemEvents.USE, (level, player, hand) -> {
            say("событие: использован " + player.getItemInHand(hand).getHoverName().getString());
            return InteractionResult.PASS;
        });

        context.onReceive(Packet.class, packet -> {
            received.incrementAndGet();
            return true;
        });
        context.onSend(Packet.class, packet -> {
            sent.incrementAndGet();
            return true;
        });
        context.onReceive(ClientboundDamageEventPacket.class, packet -> {
            var entity = Minecraft.getInstance().level.getEntity(packet.entityId());
            say("пакет: урон " + (entity == null ? "#" + packet.entityId() : entity.getName().getString())
                    + ", тип " + packet.sourceType().getRegisteredName());
            return true;
        });
        context.onReceive(ClientboundSoundPacket.class, packet -> {
            if (muted) dropped++;
            return !muted;
        });
        context.key("mute", -1, () -> {
            muted = !muted;
            say(muted ? "звуки сервера заглушены" : "звуки сервера снова слышны");
        });

        // Diagnostics: a module that throws is turned off, the player gets a toast, /sdk lists the error
        context.key("fail", -1, () -> {
            throw new IllegalStateException("тестовая ошибка по клавише");
        });

        context.hud((graphics, delta) -> graphics.text(Minecraft.getInstance().font,
                "SDK test: in " + received.get() + ", out " + sent.get() + (muted ? ", заглушено звуков " + dropped : ""),
                4, 4, 0xFFFFFFFF, true));
    }

    private static @Nullable BlockPos target() {
        HitResult hit = Minecraft.getInstance().hitResult;
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
    }

    private static void say(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal("[SDK test] " + text).withStyle(ChatFormatting.AQUA));
    }
}
