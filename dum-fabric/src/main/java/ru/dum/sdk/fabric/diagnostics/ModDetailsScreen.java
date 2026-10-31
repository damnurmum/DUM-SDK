package ru.dum.sdk.fabric.diagnostics;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import ru.dum.sdk.fabric.DumSdkClient;

/** Full metadata for one card, keeping the parent search/filter/scroll state intact. */
public final class ModDetailsScreen extends Screen {
    private final Screen parent;
    private ModInventory.Item item;
    private FittingMultiLineTextWidget body;
    private int ticks;

    public ModDetailsScreen(Screen parent, ModInventory.Item item) {
        super(Component.literal("Сведения о модификации"));
        this.parent = parent;
        this.item = item;
    }

    @Override protected void init() {
        int w = Math.min(620, width - 32), x = (width - w) / 2;
        body = addRenderableWidget(new FittingMultiLineTextWidget(x, 36, w, Math.max(30, height - 76), describe(item), font));
        int button = Math.min(200, (w - 6) / 2);
        addRenderableWidget(Button.builder(Component.literal("Скопировать сведения"), b -> {
            minecraft.keyboardHandler.setClipboard(describe(item).getString());
            b.setMessage(Component.literal("Скопировано"));
        })
                .bounds(width / 2 - button - 3, height - 28, button, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Назад"), b -> onClose()).bounds(width / 2 + 3, height - 28, button, 20).build());
    }

    @Override public void tick() {
        if (++ticks % 20 != 0) return;
        if (!DumSdkClient.serverActive()) { onClose(); return; }
        var next = ModInventory.snapshot().stream().filter(entry -> entry.id().equals(item.id()) && entry.server() == item.server()
                && entry.type().equals(item.type())).findFirst();
        if (next.isEmpty()) { onClose(); return; }
        if (!next.get().equals(item)) {
            item = next.get(); body.setMessage(describe(item)); body.refreshScrollAmount();
        }
    }

    static Component describe(ModInventory.Item item) {
        var text = Component.literal(item.name() + " " + item.version() + "\n").withStyle(ChatFormatting.GOLD);
        text.append(Component.literal(item.id() + " · " + item.type() + "\n" + item.state() + "\n\n").withStyle(ChatFormatting.WHITE));
        text.append(Component.literal((item.description().isBlank() ? "Описание не указано" : item.description()) + "\n\n").withStyle(ChatFormatting.GRAY));
        if (!item.authors().isEmpty()) text.append(Component.literal("Авторы: " + String.join(", ", item.authors()) + "\n").withStyle(ChatFormatting.GRAY));
        if (!item.dependencies().isEmpty()) text.append(Component.literal("Зависимости: " + String.join(", ", item.dependencies()) + "\n").withStyle(ChatFormatting.GRAY));
        return text.append(Component.literal(item.extra()).withStyle(ChatFormatting.GRAY));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFB347);
    }
    @Override protected void repositionElements() { rebuildWidgets(); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
