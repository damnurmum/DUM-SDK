package ru.dum.sdk.fabric.diagnostics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** A virtualized card grid. The native text-area widget owns clipping, wheel scrolling and its scrollbar. */
public final class ModCardsWidget extends AbstractTextAreaWidget {
    private static final int HEADER = 12, CARD_HEIGHT = 44, GAP = 6;
    private final Font font;
    private final Consumer<ModInventory.Item> open;
    private List<ModInventory.Item> items = List.of();
    private String caption = "";
    private @Nullable Tooltip preview;
    private int selected = -1, hovered = -1;

    public ModCardsWidget(int x, int y, int width, int height, Font font, Consumer<ModInventory.Item> open) {
        super(x, y, width, height, Component.literal("Карточки модификаций"), defaultSettings(0));
        this.font = font;
        this.open = open;
    }

    public List<ModInventory.Item> items() { return items; }
    public String selectedKey() { return selected < 0 ? "" : key(items.get(selected)); }
    private static String key(ModInventory.Item item) { return item.server() + ":" + item.type() + ":" + item.id(); }

    public void setItems(List<ModInventory.Item> next, String summary) {
        if (items.equals(next) && caption.equals(summary)) return;
        String previous = selectedKey();
        items = List.copyOf(next);
        caption = summary;
        selectKey(previous);
        hovered = -1;
        setTooltip(null);
        setMessage(Component.literal(summary + (items.isEmpty() ? "\nНичего не найдено" : "")));
        refreshScrollAmount();
    }

    public void restore(String identity, double scroll) {
        selectKey(identity);
        setScrollAmount(scroll);
    }

    private void selectKey(String identity) {
        selected = items.isEmpty() ? -1 : 0;
        for (int i = 0; i < items.size(); i++) if (key(items.get(i)).equals(identity)) { selected = i; break; }
    }

    private int columns() { return width >= 560 ? 2 : 1; }
    private int availableWidth() { return width - totalInnerPadding() - SCROLLBAR_WIDTH - 2; }
    private int cardWidth() { return (availableWidth() - GAP * (columns() - 1)) / columns(); }

    @Override protected int getInnerHeight() {
        int rows = (items.size() + columns() - 1) / columns();
        return HEADER + (rows == 0 ? 24 : rows * (CARD_HEIGHT + GAP) - GAP);
    }
    @Override protected double scrollRate() { return CARD_HEIGHT + GAP; }

    @Override protected void extractBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(getX(), getY(), getRight(), getBottom(), 0xAA080B10);
    }

    @Override public void setTooltip(@Nullable Tooltip tooltip) {
        super.setTooltip(tooltip);
        preview = tooltip;
    }

    @Override protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Tooltip tooltip = preview;
        if (tooltip == null || at(mouseX, mouseY) < 0) return;
        // Native widget tooltips anchor to the whole list; card previews must follow the mouse instead.
        graphics.setTooltipForNextFrame(font, tooltip.toCharSequence(Minecraft.getInstance()), tooltip.component(),
                DefaultTooltipPositioner.INSTANCE, mouseX, mouseY, false, tooltip.style());
    }

    @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int hit = at(mouseX, mouseY);
        if (hit != hovered) {
            hovered = hit;
            setTooltip(hit < 0 ? null : Tooltip.create(Component.literal(items.get(hit).name() + "\n"
                    + (items.get(hit).description().isBlank() ? "Нажми, чтобы открыть сведения" : items.get(hit).description()))));
        }
        int step = CARD_HEIGHT + GAP;
        int firstRow = Math.max(0, (int) scrollAmount() / step);
        int lastRow = Math.min((items.size() + columns() - 1) / columns(),
                ((int) scrollAmount() + height) / step + 2);
        for (int row = firstRow; row < lastRow; row++) for (int column = 0; column < columns(); column++) {
            int index = row * columns() + column;
            if (index >= items.size()) break;
            drawCard(graphics, index, getInnerLeft() + column * (cardWidth() + GAP), getInnerTop() + HEADER + row * step);
        }
        if (items.isEmpty()) graphics.text(font, "Ничего не найдено", getInnerLeft() + 6, getInnerTop() + HEADER + 8, 0xFFADB4C0);
        // The base class translates by -scrollAmount. Cancel that translation for the sticky caption only.
        graphics.pose().pushMatrix().translate(0, (float) scrollAmount());
        graphics.fill(getX() + 1, getY() + 1, getX() + width - SCROLLBAR_WIDTH - 1, getInnerTop() + HEADER, 0xFF0C1017);
        graphics.text(font, fit(caption, availableWidth()), getInnerLeft(), getInnerTop(), 0xFFACB6C5);
        graphics.pose().popMatrix();
    }

    private void drawCard(GuiGraphicsExtractor graphics, int index, int x, int y) {
        ModInventory.Item item = items.get(index);
        int w = cardWidth();
        boolean highlight = hovered == index || isFocused() && selected == index;
        int accent = item.server() ? 0xFFFFB347 : 0xFF79A5CC;
        graphics.fill(x, y, x + w, y + CARD_HEIGHT, highlight ? 0xFF27313F : 0xFF19212D);
        graphics.outline(x, y, w, CARD_HEIGHT, highlight ? accent : 0xFF39465A);
        graphics.fill(x, y, x + 3, y + CARD_HEIGHT, accent);
        String version = fit(item.version(), Math.max(35, w / 3));
        int badge = font.width(version) + 8;
        graphics.fill(x + w - badge - 6, y + 4, x + w - 6, y + 16, 0xFF0D141E);
        graphics.text(font, version, x + w - badge - 2, y + 5, 0xFFD5DFEB);
        graphics.text(font, fit(item.name(), w - badge - 24), x + 10, y + 5, 0xFFFFD58A);
        graphics.text(font, fit(item.id() + " · " + item.type(), w - 20), x + 10, y + 17, 0xFFB6C2D2);
        String action = "Подробнее";
        int actionWidth = font.width(action);
        int stateColor = item.state().equals("Активен") ? 0xFFA4D892 : item.server() ? 0xFFE7BD7B : 0xFF9FB9D8;
        graphics.text(font, fit(item.state(), w - actionWidth - 32), x + 10, y + 29, stateColor);
        graphics.text(font, action, x + w - actionWidth - 8, y + 29, highlight ? 0xFFFFD58A : 0xFFAEBACC);
    }

    private String fit(String value, int width) {
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(0, width - font.width("..."))) + "...";
    }

    private int at(double mouseX, double mouseY) {
        if (mouseY < getInnerTop() + HEADER || mouseY >= getBottom() - innerPadding()) return -1;
        double x = mouseX - getInnerLeft(), y = mouseY - getInnerTop() - HEADER + scrollAmount();
        if (x < 0 || x >= availableWidth() || y < 0) return -1;
        int row = (int) y / (CARD_HEIGHT + GAP), column = (int) x / (cardWidth() + GAP);
        if (y % (CARD_HEIGHT + GAP) >= CARD_HEIGHT || x % (cardWidth() + GAP) >= cardWidth() || column >= columns()) return -1;
        int index = row * columns() + column;
        return index < items.size() ? index : -1;
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int index = at(event.x(), event.y());
        if (event.button() == 0 && index >= 0) {
            selected = index;
            playButtonClickSound(Minecraft.getInstance().getSoundManager());
            open.accept(items.get(index));
        }
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (items.isEmpty()) return super.keyPressed(event);
        if (event.isConfirmation()) { open.accept(items.get(selected)); return true; }
        int next = selected;
        if (event.isDown()) next += columns();
        else if (event.isUp()) next -= columns();
        else if (event.isLeft()) next--;
        else if (event.isRight()) next++;
        else if (event.key() == GLFW.GLFW_KEY_HOME) next = 0;
        else if (event.key() == GLFW.GLFW_KEY_END) next = items.size() - 1;
        else if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN) next += columns() * Math.max(1, (height - HEADER) / (CARD_HEIGHT + GAP));
        else if (event.key() == GLFW.GLFW_KEY_PAGE_UP) next -= columns() * Math.max(1, (height - HEADER) / (CARD_HEIGHT + GAP));
        else return super.keyPressed(event);
        selected = Math.clamp(next, 0, items.size() - 1);
        int top = selected / columns() * (CARD_HEIGHT + GAP);
        int visible = height - totalInnerPadding() - HEADER;
        if (top < scrollAmount()) setScrollAmount(top);
        else if (top + CARD_HEIGHT > scrollAmount() + visible) setScrollAmount(top + CARD_HEIGHT - visible);
        return true;
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, selected < 0 ? Component.literal("Ничего не найдено")
                : Component.literal(items.get(selected).name() + " " + items.get(selected).version() + ", " + items.get(selected).state()));
        if (selected >= 0) output.add(NarratedElementType.POSITION, Component.literal("Карточка " + (selected + 1) + " из " + items.size()));
        output.add(NarratedElementType.USAGE, Component.literal("Стрелки - выбор карточки, Enter - сведения, Home и End - начало и конец списка"));
    }
}
