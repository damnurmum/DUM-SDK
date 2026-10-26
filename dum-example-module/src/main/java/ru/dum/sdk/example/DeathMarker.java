package ru.dum.sdk.example;

import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import ru.dum.sdk.api.DumContext;

import java.util.Optional;

/**
 * A red flag over the place of the last death, seen while a compass is in the accessory slot: the world-space
 * twin of the death mode of the DUM-Mechanics compass. A client-only display entity with this module's own model.
 */
final class DeathMarker {

    private static final Identifier MODEL = Identifier.fromNamespaceAndPath("info", "death_marker");
    /** Ticks for one move up or down. */
    private static final int BOB = 30;

    private final DumContext context;
    private Display.@Nullable ItemDisplay marker;
    private @Nullable GlobalPos shownAt;
    private int ticks;

    DeathMarker(DumContext context) {
        this.context = context;
    }

    void tick(boolean compass) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Optional<GlobalPos> death = player == null ? Optional.empty() : player.getLastDeathLocation();
        boolean wanted = compass && minecraft.level != null && death.isPresent() && death.get().dimension() == minecraft.level.dimension();
        // The marker goes away with the level (dimension change, respawn) and when the death place moves
        if (marker != null && (!wanted || marker.isRemoved() || !death.get().equals(shownAt))) remove();
        if (!wanted) return;
        if (marker == null) spawn(death.get());
        if (++ticks % BOB == 0) {
            // Gentle float: the game interpolates between the two heights
            marker.setTransformationInterpolationDelay(0);
            marker.setTransformationInterpolationDuration(BOB);
            marker.setTransformation(transform((ticks / BOB) % 2 == 0 ? 0 : 0.25f));
        }
    }

    private void spawn(GlobalPos death) {
        Display.ItemDisplay display = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, Minecraft.getInstance().level);
        BlockPos pos = death.pos();
        display.setPos(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
        ItemStack flag = new ItemStack(Items.PAPER);
        flag.set(DataComponents.ITEM_MODEL, MODEL);
        display.setItemStack(flag);
        display.setItemTransform(ItemDisplayContext.FIXED);
        display.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
        display.setBrightnessOverride(Brightness.FULL_BRIGHT);
        display.setViewRange(4);
        display.setTransformation(transform(0));
        context.addEntity(display);
        marker = display;
        shownAt = death;
    }

    void remove() {
        if (marker != null && !marker.isRemoved() && marker.level() == Minecraft.getInstance().level) marker.discard();
        marker = null;
        shownAt = null;
    }

    private static Transformation transform(float lift) {
        return new Transformation(new Vector3f(0, lift, 0), null, new Vector3f(1.2f), null);
    }
}
