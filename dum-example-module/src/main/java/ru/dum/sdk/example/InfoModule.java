package ru.dum.sdk.example;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import ru.dum.sdk.api.DumContext;
import ru.dum.sdk.api.DumModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Terraria-like info accessories. A compass or a clock in the accessory slots ({@link Accessories}) adds
 * a line on the right, just above the scoreboard sidebar: coordinates and facing, or the day, the time and
 * how long until night or morning. Same texts as the DUM-Mechanics actionbars. A key hides the lines;
 * the compass also marks the place of the last death ({@link DeathMarker}).
 */
public final class InfoModule implements DumModule {

    /** Minecraft yaw: 0 is south, 90 west, 180 north, 270 east. One name per 45 degrees. */
    private static final String[] DIRECTIONS = {
            "Юг", "Юго-запад", "Запад", "Северо-запад", "Север", "Северо-восток", "Восток", "Юго-восток"};
    /** From this tick players can sleep and monsters spawn: "night" for the player. */
    private static final int NIGHT = 13000;
    private static final int MORNING = 23000;
    private static final int DAY = 24000;

    private static final TextColor GOLD = TextColor.fromRgb(0xffcc33);
    private static final TextColor DAY_TEXT = TextColor.fromRgb(0xffd08a);
    private static final TextColor MOON = TextColor.fromRgb(0x8fa8ff);
    private static final TextColor NIGHT_TEXT = TextColor.fromRgb(0xaeacff);
    private static final TextColor WHITE = TextColor.fromRgb(0xffffff);
    private static final TextColor SEPARATOR = TextColor.fromRgb(0x555555);

    private Accessories accessories;
    private DeathMarker deathMarker;
    /** Lines hidden by the player with the toggle key; remembered as a file in the module's folder. */
    private boolean hidden;
    private Path hiddenFlag;

    @Override
    public void onEnable(DumContext context) {
        accessories = new Accessories(context);
        deathMarker = new DeathMarker(context);
        hiddenFlag = context.dataDir().resolve("lines-hidden");
        hidden = Files.exists(hiddenFlag);
        context.screen(accessories);
        context.onServerMessage(accessories::receive);
        context.hud(this::draw);
        context.onTick(() -> deathMarker.tick(accessories.equipped(Items.COMPASS)));
        context.key("toggle_lines", -1, this::toggle);
    }

    @Override
    public void onDisable() {
        deathMarker.remove();
    }

    private void toggle() {
        hidden = !hidden;
        try {
            if (hidden) Files.writeString(hiddenFlag, "");
            else Files.deleteIfExists(hiddenFlag);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (hidden || player == null || minecraft.level == null) return;

        List<Component> lines = new ArrayList<>(2);
        if (accessories.equipped(Items.COMPASS)) lines.add(compass(player));
        if (accessories.equipped(Items.CLOCK)) lines.add(clock(minecraft.level));
        if (lines.isEmpty()) return;

        int bottom = sidebarTop(graphics, minecraft.level.getScoreboard()) - 4;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            int x = graphics.guiWidth() - minecraft.font.width(line) - 3;
            int y = bottom - (lines.size() - i) * 10;
            graphics.text(minecraft.font, line, x, y, 0xFFFFFFFF, true);
        }
    }

    /** Top of the vanilla sidebar (its title row), or where an empty one would start. Same math as the game. */
    private static int sidebarTop(GuiGraphicsExtractor graphics, Scoreboard scoreboard) {
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        int entries = objective == null ? 0 : Math.min(scoreboard.listPlayerScores(objective).size(), 15);
        int height = entries * 9;
        return graphics.guiHeight() / 2 + height / 3 - height - 10;
    }

    private static Component compass(Player player) {
        var pos = player.blockPosition();
        float yaw = ((player.getYRot() % 360) + 360) % 360;
        String direction = DIRECTIONS[Math.round(yaw / 45f) % DIRECTIONS.length];
        return text("⌖ ", GOLD)
                .append(text(pos.getX() + " " + pos.getY() + " " + pos.getZ(), WHITE))
                .append(text(" · ", SEPARATOR))
                .append(text(direction, DAY_TEXT));
    }

    private static Component clock(Level level) {
        if (level.dimension() != Level.OVERWORLD) {
            return text("☀ ", GOLD).append(text("Стрелки часов беспорядочно вращаются", DAY_TEXT));
        }
        long fullTime = level.getOverworldClockTime();
        long day = fullTime / DAY + 1;
        int time = (int) (fullTime % DAY);
        // Tick 0 is 6:00 in the morning
        int hours = (time / 1000 + 6) % 24;
        int minutes = time % 1000 * 60 / 1000;
        boolean night = time >= NIGHT && time < MORNING;
        int left = night ? MORNING - time : Math.floorMod(NIGHT - time, DAY);
        TextColor words = night ? NIGHT_TEXT : DAY_TEXT;
        return text(night ? "★ " : "☀ ", night ? MOON : GOLD)
                .append(text("День ", words)).append(text(String.valueOf(day), WHITE))
                .append(text(" · ", SEPARATOR))
                .append(text(String.format("%d:%02d", hours, minutes), WHITE))
                .append(text(" · ", SEPARATOR))
                .append(text(night ? "до утра " : "до ночи ", words))
                .append(text(realMinutes(left), WHITE));
    }

    /** Game ticks to real minutes: 1200 ticks is one minute. */
    private static String realMinutes(int ticks) {
        int value = ticks / 1200;
        return value < 1 ? "меньше минуты" : value + " мин";
    }

    private static MutableComponent text(String text, TextColor color) {
        return Component.literal(text).withStyle(style -> style.withColor(color));
    }
}
