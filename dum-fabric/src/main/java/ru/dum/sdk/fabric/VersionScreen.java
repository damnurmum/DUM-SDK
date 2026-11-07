package ru.dum.sdk.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import ru.dum.sdk.fabric.update.SdkUpdater;
import ru.dum.sdk.fabric.update.UpdateStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown instead of the disconnect screen when the server refused this client for having another DUM-SDK version:
 * both versions, and the choice to install the server's one. Nothing is downloaded until the player agrees.
 */
public final class VersionScreen extends Screen {

    private enum State { ASK, WORKING, READY, FAILED }

    private final @Nullable Screen parent;
    private final String server;
    private State state = State.ASK;
    private UpdateStatus.Stage shown;

    public VersionScreen(@Nullable Screen parent, String server) {
        super(Component.literal("Нужна другая версия DUM-SDK"));
        this.parent = parent;
        this.server = server;
    }

    @Override
    protected void init() {
        // One above the other, the main action on top
        int y = height / 2 + 30, x = width / 2 - 100;
        switch (state) {
            case ASK -> {
                button("Обновить", x, y, this::update);
                button("Отмена", x, y + 24, this::onClose);
            }
            case WORKING -> button("Назад", x, y, this::onClose);
            case READY -> {
                button("Закрыть игру", x, y, () -> minecraft.stop());
                button("Позже", x, y + 24, this::onClose);
            }
            case FAILED -> {
                addRenderableWidget(Button.builder(Component.literal("Перейти на GitHub"),
                        ConfirmLinkScreen.confirmLink(this, SdkUpdater.DOWNLOAD_PAGE)).bounds(x, y, 200, 20).build());
                button("Назад", x, y + 24, this::onClose);
            }
        }
    }

    private void button(String label, int x, int y, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, 200, 20).build());
    }

    private void update() {
        SdkUpdater updater = DumSdkClient.updater();
        if (server.equals(updater.pending())) {
            state = State.READY;
        } else if (updater.installInBackground(server, status -> { })) {
            state = State.WORKING;
        } else {
            // Off in a development client, busy, or another update is already waiting for the restart
            state = State.FAILED;
        }
        shown = updater.status().stage();
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (state != State.WORKING) return;
        UpdateStatus status = DumSdkClient.updater().status();
        if (status.stage() == shown) return;
        shown = status.stage();
        if (status.stage() == UpdateStatus.Stage.READY) state = State.READY;
        else if (status.stage() == UpdateStatus.Stage.FAILED || status.stage() == UpdateStatus.Stage.CURRENT) state = State.FAILED;
        rebuildWidgets();
    }

    private List<Component> lines() {
        List<Component> lines = new ArrayList<>();
        String own = DumSdkClient.version();
        switch (state) {
            case ASK -> {
                lines.add(Component.literal("У тебя: " + own));
                lines.add(Component.literal("На сервере: " + server));
                lines.add(Component.empty());
                lines.add(Component.literal("Чтобы играть на этом сервере, нужна версия сервера. "
                        + "Мод скачается с GitHub и заменится, когда ты закроешь игру."));
            }
            case WORKING, FAILED -> {
                UpdateStatus status = DumSdkClient.updater().status();
                SdkUpdater updater = DumSdkClient.updater();
                if (state == State.FAILED && updater.pending() != null && !server.equals(updater.pending())) {
                    lines.add(Component.literal("Уже загружен DUM-SDK " + updater.pending() + "."));
                    lines.add(Component.literal("Перезапусти игру, затем зайди на сервер снова."));
                } else if (state == State.FAILED && status.stage() == UpdateStatus.Stage.CURRENT) {
                    lines.add(Component.literal("Версия " + server + " уже установлена. Перезапусти игру."));
                } else {
                    lines.add(Component.literal(status.title()));
                    lines.add(Component.literal(status.message()));
                }
            }
            case READY -> {
                lines.add(Component.literal("DUM-SDK " + server + " загружен."));
                lines.add(Component.literal("Он заменит " + own + ", когда ты закроешь игру. Потом зайди на сервер снова."));
            }
        }
        return lines;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int y = height / 2 - 70;
        graphics.centeredText(font, title, width / 2, y, 0xFFFFB347);
        y += 20;
        for (Component line : lines()) {
            List<FormattedCharSequence> wrapped = font.split(line, Math.min(320, width - 40));
            if (wrapped.isEmpty()) y += 6;
            for (FormattedCharSequence part : wrapped) {
                graphics.centeredText(font, part, width / 2, y, 0xFFFFFFFF);
                y += 11;
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
