package ru.dum.sdk.api;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Simple shapes for {@code LevelRenderEvents.COLLECT_SUBMITS} that also show with shader packs.
 * Iris draws only the game's regular render pipelines: lines and text backgrounds work, debug pipelines
 * (debugQuads, debugFilledBox, the fill of gizmo boxes) do not. Shaders may tint the colors.
 * Coordinates are relative to the pose, which starts at the camera: translate by (block - camera position) first.
 */
public final class WorldDraw {

    /** Lines, hidden behind blocks. Use with {@link #line} and {@link #box}. */
    public static final RenderType LINES = RenderTypes.lines();
    /** Flat colored quads, hidden behind blocks. Use with {@link #quad}. */
    public static final RenderType QUADS = RenderTypes.textBackground();
    /** Flat colored quads seen through blocks. Use with {@link #quad}. */
    public static final RenderType QUADS_SEE_THROUGH = RenderTypes.textBackgroundSeeThrough();

    private static final int FULL_BRIGHT = 0xF000F0;

    private WorldDraw() {
    }

    public static void line(PoseStack.Pose pose, VertexConsumer buffer, float x1, float y1, float z1, float x2, float y2, float z2,
                            int argb, float width) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return;
        dx /= length;
        dy /= length;
        dz /= length;
        buffer.addVertex(pose, x1, y1, z1).setColor(argb).setNormal(pose, dx, dy, dz).setLineWidth(width);
        buffer.addVertex(pose, x2, y2, z2).setColor(argb).setNormal(pose, dx, dy, dz).setLineWidth(width);
    }

    /** The 12 edges of the box between two corners. */
    public static void box(PoseStack.Pose pose, VertexConsumer buffer, float x1, float y1, float z1, float x2, float y2, float z2,
                           int argb, float width) {
        float[] xs = {x1, x2}, ys = {y1, y2}, zs = {z1, z2};
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                line(pose, buffer, x1, ys[a], zs[b], x2, ys[a], zs[b], argb, width);
                line(pose, buffer, xs[a], y1, zs[b], xs[a], y2, zs[b], argb, width);
                line(pose, buffer, xs[a], ys[b], z1, xs[a], ys[b], z2, argb, width);
            }
        }
    }

    /** A quad through four corners in order, drawn from both sides. */
    public static void quad(PoseStack.Pose pose, VertexConsumer buffer, float[][] corners, int argb) {
        for (float[] c : corners) buffer.addVertex(pose, c[0], c[1], c[2]).setColor(argb).setLight(FULL_BRIGHT);
        for (int i = corners.length - 1; i >= 0; i--) {
            buffer.addVertex(pose, corners[i][0], corners[i][1], corners[i][2]).setColor(argb).setLight(FULL_BRIGHT);
        }
    }
}
