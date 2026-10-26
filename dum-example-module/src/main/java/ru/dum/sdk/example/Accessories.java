package ru.dum.sdk.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import ru.dum.sdk.api.DumContext;
import ru.dum.sdk.api.ScreenLayer;
import ru.dum.sdk.api.Screens;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Two accessory slots on a small panel to the right of the inventory. The server owns their contents:
 * a click only tells it which slot was clicked, and it answers with what the slots hold now.
 * To the server: one byte, the slot. From the server: an item id per slot, "" when empty.
 */
final class Accessories implements ScreenLayer {

    /** What each slot takes. */
    static final Item[] SLOT_ITEMS = {Items.COMPASS, Items.CLOCK};
    /** Outline shown in an empty slot, like the armor slots; from this module's own assets. */
    private static final Identifier[] HINT_SPRITES = {
            Identifier.fromNamespaceAndPath("info", "slot/compass"), Identifier.fromNamespaceAndPath("info", "slot/clock")};
    private static final Component[] HINTS = {Component.literal("Слот компаса"), Component.literal("Слот часов")};

    private static final int SLOT = 18;
    private static final int PADDING = 4;

    private final DumContext context;
    private volatile ItemStack[] slots = {ItemStack.EMPTY, ItemStack.EMPTY};

    Accessories(DumContext context) {
        this.context = context;
    }

    boolean equipped(Item item) {
        for (ItemStack stack : slots) {
            if (stack.is(item)) return true;
        }
        return false;
    }

    void receive(byte[] data) {
        ItemStack[] next = new ItemStack[SLOT_ITEMS.length];
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            for (int i = 0; i < next.length; i++) {
                String id = in.readUTF();
                next[i] = id.isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Bad accessory state from the server", e);
        }
        slots = next;
    }

    /** Panel bounds next to the inventory window, or null on any other screen. */
    private static @Nullable ScreenRectangle panel(Screen screen) {
        if (!(screen instanceof InventoryScreen inventory)) return null;
        ScreenRectangle window = Screens.containerBounds(inventory);
        return new ScreenRectangle(window.right() + 2, window.top() + 4, SLOT + PADDING * 2, SLOT * SLOT_ITEMS.length + PADDING * 2);
    }

    private static int slotAt(ScreenRectangle panel, double x, double y) {
        int left = panel.left() + PADDING;
        int top = panel.top() + PADDING;
        if (x < left || x >= left + SLOT || y < top) return -1;
        int slot = (int) (y - top) / SLOT;
        return slot < SLOT_ITEMS.length ? slot : -1;
    }

    @Override
    public void extract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        ScreenRectangle panel = panel(screen);
        if (panel == null) return;
        drawPanel(graphics, panel);
        int hovered = slotAt(panel, mouseX, mouseY);
        for (int i = 0; i < SLOT_ITEMS.length; i++) {
            int x = panel.left() + PADDING;
            int y = panel.top() + PADDING + i * SLOT;
            drawSlot(graphics, x, y);
            ItemStack stack = slots[i];
            if (stack.isEmpty()) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HINT_SPRITES[i], x + 1, y + 1, 16, 16);
            } else {
                graphics.item(stack, x + 1, y + 1);
            }
            if (i == hovered) {
                graphics.nextStratum();
                graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x80FFFFFF);
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null && minecraft.player.containerMenu.getCarried().isEmpty()) {
                    if (stack.isEmpty()) {
                        graphics.setTooltipForNextFrame(minecraft.font, HINTS[i], mouseX, mouseY);
                    } else {
                        graphics.setTooltipForNextFrame(minecraft.font, stack, mouseX, mouseY);
                    }
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(Screen screen, MouseButtonEvent event) {
        ScreenRectangle panel = panel(screen);
        if (panel == null || !panel.containsPoint((int) event.x(), (int) event.y())) return false;
        int slot = slotAt(panel, event.x(), event.y());
        if (slot >= 0) context.sendToServer(new byte[]{(byte) slot});
        // Clicks on the panel never reach the inventory, which would drop the carried item
        return true;
    }

    /** Same look as a vanilla window: light gray with a white top-left and dark bottom-right edge. */
    private static void drawPanel(GuiGraphicsExtractor graphics, ScreenRectangle panel) {
        int l = panel.left(), t = panel.top(), r = panel.right(), b = panel.bottom();
        graphics.fill(l + 1, t, r - 1, b, 0xFF000000);
        graphics.fill(l, t + 1, r, b - 1, 0xFF000000);
        graphics.fill(l + 1, t + 1, r - 1, b - 1, 0xFFC6C6C6);
        graphics.fill(l + 1, t + 1, r - 2, t + 3, 0xFFFFFFFF);
        graphics.fill(l + 1, t + 1, l + 3, b - 2, 0xFFFFFFFF);
        graphics.fill(l + 2, b - 3, r - 1, b - 1, 0xFF555555);
        graphics.fill(r - 3, t + 2, r - 1, b - 1, 0xFF555555);
    }

    private static void drawSlot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + SLOT, y + SLOT, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + SLOT, y + SLOT, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF8B8B8B);
    }
}
