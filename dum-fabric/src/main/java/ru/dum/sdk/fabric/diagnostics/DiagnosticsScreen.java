package ru.dum.sdk.fabric.diagnostics;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import ru.dum.sdk.fabric.DumSdkClient;
import ru.dum.sdk.fabric.update.SdkUpdater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Settings diagnostics work in the title menu and in a world, including after a failed handshake. */
public final class DiagnosticsScreen extends Screen {
    private static final SystemToast.SystemToastId FEEDBACK = new SystemToast.SystemToastId();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final @Nullable Screen parent;
    private final Path game = FabricLoader.getInstance().getGameDir();
    private DiagnosticFiles files = DiagnosticFiles.EMPTY;
    private boolean scanning;
    private int tab, ticks;
    private String displayed = "";
    private FittingMultiLineTextWidget body;
    private @Nullable Button update;
    private @Nullable Button crash;
    private @Nullable Button log;
    private @Nullable Button helper;

    public DiagnosticsScreen(@Nullable Screen parent) {
        super(Component.literal("Диагностика DUM-SDK"));
        this.parent = parent;
        refreshFiles();
    }

    @Override protected void init() {
        int w = Math.min(620, width - 32), x = (width - w) / 2, half = (w - 6) / 2;
        update = crash = log = helper = null;
        displayed = "";
        String[] tabs = {"Обзор", "Ошибки", "Файлы"};
        int third = (w - 12) / 3;
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            Button button = addRenderableWidget(Button.builder(Component.literal(tabs[i]), b -> {
                tab = selected;
                rebuildWidgets();
            }).bounds(x + i * (third + 6), 34, third, 20).build());
            button.active = tab != i;
        }
        body = addRenderableWidget(new FittingMultiLineTextWidget(x, 62, w, Math.max(30, height - 62 - (tab == 2 ? 114 : 90)), Component.empty(), font));
        int bottom = height - 84;
        if (tab == 2) {
            crash = action("Краш-репорт", x, bottom - 24, third, () -> {
                if (!files.crashes().isEmpty()) open(files.crashes().getFirst().path(), false);
            });
            action("Папка отчётов", x + third + 6, bottom - 24, third,
                    () -> open(files.crashes().isEmpty() ? game.resolve("crash-reports") : files.crashes().getFirst().path().getParent(), true));
            action("Папка DUM-SDK", x + 2 * (third + 6), bottom - 24, third, () -> open(game.resolve("dum-sdk"), true));
            log = action("Открыть latest.log", x, bottom, third, () -> open(game.resolve("logs/latest.log"), false));
            action("Папка логов", x + third + 6, bottom, third, () -> open(game.resolve("logs"), true));
            helper = action("Установка модов", x + 2 * (third + 6), bottom, third, () -> open(game.resolve("dum-sdk/cold-apply.log"), false));
        } else {
            update = action("Проверить обновления", x, bottom, half, DumSdkClient::checkUpdates);
            action("Перейти на GitHub", x + half + 6, bottom, half,
                    () -> ConfirmLinkScreen.confirmLinkNow(this, SdkUpdater.DOWNLOAD_PAGE));
        }
        Button copy = action("Скопировать диагностику", x, height - 60, half, () -> {
            minecraft.keyboardHandler.setClipboard(summary());
            feedback("Диагностика скопирована", "Сводку можно отправить администратору");
        });
        copy.setTooltip(Tooltip.create(Component.literal("Копирует сводку, версии и ошибки. Содержимое логов и crash-report не копируется")));
        action("Обновить данные", x + half + 6, height - 60, half, this::refreshFiles);
        action("Назад", (width - 200) / 2, height - 28, 200, this::onClose);
        updateText();
    }

    private Button action(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
    }

    private void refreshFiles() {
        if (scanning) return;
        scanning = true;
        CompletableFuture.supplyAsync(() -> DiagnosticFiles.scan(game)).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            scanning = false;
            files = error == null ? result : new DiagnosticFiles(List.of(), false, false, "Не удалось проверить файлы. Подробности в latest.log.");
            if (error != null) DumSdkClient.recordError("DIAGNOSTIC_FILES", "Не удалось проверить файлы диагностики");
            if (body != null) updateText();
        }));
    }

    @Override public void tick() {
        if (++ticks % 20 == 0) updateText();
        // Atomic updater and cached scan state only; no I/O or network work in this tick.
        if (update != null) update.active = DumSdkClient.updater().canCheck();
        if (crash != null) crash.active = !scanning && !files.crashes().isEmpty();
        if (log != null) log.active = files.clientLog();
        if (helper != null) helper.active = files.helperLog();
    }

    private void updateText() {
        Component text = switch (tab) {
            case 1 -> errors();
            case 2 -> fileText();
            default -> join(DumSdkClient.diagnosticsReport());
        };
        if (!text.getString().equals(displayed)) {
            displayed = text.getString();
            body.setMessage(text);
            body.refreshScrollAmount();
        }
    }

    private Component errors() {
        var text = Component.literal("Последние ошибки\n").withStyle(ChatFormatting.GOLD);
        List<DiagnosticLog.Entry> entries = DumSdkClient.diagnosticLog().entries();
        if (entries.isEmpty()) text.append(Component.literal("Ошибок пока нет\n").withStyle(ChatFormatting.GRAY));
        for (var entry : entries) {
            text.append(Component.literal("\n" + entry.at() + "  " + entry.code() + "\n").withStyle(ChatFormatting.YELLOW));
            text.append(Component.literal(entry.message() + "\n").withStyle(ChatFormatting.GRAY));
        }
        text.append(Component.literal("\n" + DumSdkClient.diagnosticLog().storageError()).withStyle(ChatFormatting.RED));
        return text;
    }

    private Component fileText() {
        var text = Component.literal(scanning ? "Проверяем файлы...\n" : "Последние crash-report\n").withStyle(ChatFormatting.GOLD);
        if (files.crashes().isEmpty()) text.append(Component.literal("Отчётов о краше не найдено. Если игра была принудительно закрыта, отчёт мог не создаться.\n").withStyle(ChatFormatting.GRAY));
        for (var report : files.crashes()) {
            text.append(Component.literal("\n" + report.path().getFileName() + "\n").withStyle(ChatFormatting.WHITE));
            text.append(Component.literal(DATE.format(report.modified()) + ", " + Math.max(1, report.size() / 1024) + " КБ\n" + report.description() + "\n").withStyle(ChatFormatting.GRAY));
        }
        text.append(Component.literal("\nlatest.log: " + (files.clientLog() ? "доступен" : "не найден")
                + "\nЖурнал установки модов: " + (files.helperLog() ? "dum-sdk/cold-apply.log" : "не найден")
                + "\nИстория ошибок: dum-sdk/diagnostics.json\n\n" + files.error()).withStyle(ChatFormatting.GRAY));
        return text;
    }

    private String summary() {
        StringBuilder text = new StringBuilder(join(DumSdkClient.diagnosticsReport()).getString()).append("\n\n").append(errors().getString());
        text.append("\n\nПоследние crash-report:\n");
        for (var report : files.crashes()) text.append(report.path().getFileName()).append("  ").append(DATE.format(report.modified())).append('\n');
        return text.toString().replace(game.toAbsolutePath().toString(), "<папка игры>");
    }

    private static Component join(List<Component> lines) {
        var text = Component.empty();
        for (Component line : lines) text.append(line).append("\n");
        return text;
    }

    private void open(Path path, boolean directory) {
        CompletableFuture.runAsync(() -> {
            try {
                if (directory) Files.createDirectories(path);
                else if (!Files.isRegularFile(path)) throw new java.io.IOException("File no longer exists");
                Util.getPlatform().openPath(path);
            } catch (Exception e) {
                DumSdkClient.recordError("DIAGNOSTIC_OPEN", "Не удалось открыть " + path.getFileName());
                minecraft.execute(() -> feedback("Не удалось открыть файл", "Проверь права и приложение для открытия файлов"));
            }
        });
    }

    private void feedback(String title, String message) {
        SystemToast.addOrUpdate(minecraft.gui.toastManager(), FEEDBACK, Component.literal(title), Component.literal(message));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFB347);
    }

    @Override protected void repositionElements() { rebuildWidgets(); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
