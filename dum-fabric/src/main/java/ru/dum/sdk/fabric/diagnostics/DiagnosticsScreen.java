package ru.dum.sdk.fabric.diagnostics;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import ru.dum.sdk.fabric.DumSdkClient;
import ru.dum.sdk.fabric.update.SdkUpdater;
import ru.dum.sdk.fabric.update.ReleaseCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/** SDK hub with release history, session-scoped modifications and local diagnostics. */
public final class DiagnosticsScreen extends Screen {
    private static final SystemToast.SystemToastId FEEDBACK = new SystemToast.SystemToastId();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final @Nullable Screen parent;
    private final Path game = FabricLoader.getInstance().getGameDir();
    private DiagnosticFiles files = DiagnosticFiles.EMPTY;
    private boolean scanning;
    public enum Tab { OVERVIEW, UPDATES, MODS, ERRORS, FILES }
    private Tab tab = Tab.OVERVIEW;
    private int ticks;
    private boolean serverAvailable;
    private String query = "", selectedRelease = "", lastReleaseError = "";
    private List<ModInventory.Item> inventory = List.of();
    private Button newer, older, more, releaseLink;
    private EditBox search;
    private String displayed = "";
    private AbstractTextAreaWidget body;
    private double modsScroll;
    private String selectedMod = "";
    private @Nullable Button update;
    private @Nullable Button crash;
    private @Nullable Button log;
    private @Nullable Button helper;

    public DiagnosticsScreen(@Nullable Screen parent) {
        this(parent, Tab.OVERVIEW);
    }

    public DiagnosticsScreen(@Nullable Screen parent, Tab initial) {
        super(Component.literal("DUM-SDK"));
        this.parent = parent;
        this.serverAvailable = DumSdkClient.serverActive();
        this.tab = initial == Tab.MODS && !serverAvailable ? Tab.OVERVIEW : initial;
        refreshFiles();
        if (tab == Tab.MODS) inventory = ModInventory.snapshot();
        if (tab == Tab.UPDATES) DumSdkClient.releases().load(false);
    }

    @Override protected void init() {
        if (body instanceof ModCardsWidget cards) {
            modsScroll = cards.scrollAmount();
            selectedMod = cards.selectedKey();
        }
        int w = Math.min(620, width - 32), x = (width - w) / 2, half = (w - 6) / 2, third = (w - 12) / 3;
        update = crash = log = helper = newer = older = more = releaseLink = null;
        search = null;
        displayed = "";
        var tabs = new ArrayList<>(List.of(Tab.OVERVIEW, Tab.UPDATES));
        if (DumSdkClient.serverActive()) tabs.add(Tab.MODS);
        tabs.add(Tab.ERRORS); tabs.add(Tab.FILES);
        int columns = tabs.size();
        int rows = 1;
        int tabWidth = (w - (columns - 1) * 6) / columns;
        for (int i = 0; i < tabs.size(); i++) {
            Tab selected = tabs.get(i);
            int inRow = Math.min(columns, tabs.size() - i / columns * columns);
            int offset = (w - (inRow * tabWidth + (inRow - 1) * 6)) / 2;
            String label = tabLabel(selected);
            if (font.width(label) > tabWidth - 8) label = selected == Tab.MODS ? "Моды" : selected == Tab.UPDATES ? "Релизы" : label;
            Button button = action(label, x + offset + (i % columns) * (tabWidth + 6), 34 + (i / columns) * 24, tabWidth, () -> {
                tab = selected;
                if (tab == Tab.MODS) inventory = ModInventory.snapshot();
                if (tab == Tab.UPDATES) DumSdkClient.releases().load(false);
                rebuildWidgets();
            });
            button.active = tab != selected;
            button.setTooltip(Tooltip.create(Component.literal(tabLabel(selected))));
        }
        int top = 34 + rows * 24 + 4;
        if (tab == Tab.MODS) {
            search = addRenderableWidget(new EditBox(font, x, top, w, 20, Component.literal("Поиск модификаций")));
            search.setMaxLength(128);
            search.setHint(Component.literal("Поиск по названию, ID, автору или описанию"));
            search.setValue(query);
            search.setResponder(value -> {
                query = value;
                if (body instanceof ModCardsWidget cards) cards.setScrollAmount(0);
                updateText();
            });
            top += 24;
        }
        int footer = tab == Tab.FILES || tab == Tab.UPDATES ? 114 : tab == Tab.MODS ? 66 : 90;
        int bodyHeight = Math.max(30, height - top - footer);
        body = tab == Tab.MODS
                ? addRenderableWidget(new ModCardsWidget(x, top, w, bodyHeight, font, item -> minecraft.gui.setScreen(new ModDetailsScreen(this, item))))
                : addRenderableWidget(new FittingMultiLineTextWidget(x, top, w, bodyHeight, Component.empty(), font));
        int bottom = height - 84;
        if (tab == Tab.FILES) {
            crash = action("Краш-репорт", x, bottom - 24, third, () -> { if (!files.crashes().isEmpty()) open(files.crashes().getFirst().path(), false); });
            action("Папка отчётов", x + third + 6, bottom - 24, third,
                    () -> open(files.crashes().isEmpty() ? game.resolve("crash-reports") : files.crashes().getFirst().path().getParent(), true));
            action("Папка DUM-SDK", x + 2 * (third + 6), bottom - 24, third, () -> open(game.resolve("dum-sdk"), true));
            log = action("Открыть latest.log", x, bottom, third, () -> open(game.resolve("logs/latest.log"), false));
            action("Папка логов", x + third + 6, bottom, third, () -> open(game.resolve("logs"), true));
            helper = action("Установка модов", x + 2 * (third + 6), bottom, third, () -> open(game.resolve("dum-sdk/cold-apply.log"), false));
        } else if (tab == Tab.UPDATES) {
            newer = action("Новее", x, bottom - 24, third, () -> selectRelease(-1));
            older = action("Старее", x + third + 6, bottom - 24, third, () -> selectRelease(1));
            more = action("Ещё релизы", x + 2 * (third + 6), bottom - 24, third, () -> DumSdkClient.releases().loadMore());
            update = action("Проверить обновления", x, bottom, half, DumSdkClient::checkUpdates);
            releaseLink = action("Релиз на GitHub", x + half + 6, bottom, half, () -> {
                var selected = release();
                ConfirmLinkScreen.confirmLinkNow(this, selected == null ? SdkUpdater.DOWNLOAD_PAGE : selected.url());
            });
        } else if (tab != Tab.MODS) {
            update = action("Проверить обновления", x, bottom, half, DumSdkClient::checkUpdates);
            action("Перейти на GitHub", x + half + 6, bottom, half,
                    () -> ConfirmLinkScreen.confirmLinkNow(this, SdkUpdater.DOWNLOAD_PAGE));
        }
        Button copy = action("Скопировать диагностику", x, height - 60, half, () -> {
            minecraft.keyboardHandler.setClipboard(summary());
            feedback("Диагностика скопирована", "Сводку можно отправить администратору");
        });
        copy.setTooltip(Tooltip.create(Component.literal("Копирует сводку, версии, модификации и ошибки. Содержимое логов и crash-report не копируется")));
        action("Обновить данные", x + half + 6, height - 60, half, () -> {
            if (tab == Tab.UPDATES) DumSdkClient.releases().load(true);
            else if (tab == Tab.MODS) inventory = ModInventory.snapshot();
            else refreshFiles();
            updateText();
        });
        action("Назад", (width - 200) / 2, height - 28, 200, this::onClose);
        updateText();
        if (body instanceof ModCardsWidget cards) cards.restore(selectedMod, modsScroll);
        refreshActions();
    }

    private static String tabLabel(Tab tab) {
        return switch (tab) {
            case OVERVIEW -> "Обзор"; case UPDATES -> "Обновления"; case MODS -> "Модификации";
            case ERRORS -> "Ошибки"; case FILES -> "Файлы";
        };
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
        boolean connected = DumSdkClient.serverActive();
        if (connected != serverAvailable) {
            serverAvailable = connected;
            inventory = connected ? ModInventory.snapshot() : List.of();
            if (!connected && tab == Tab.MODS) tab = Tab.OVERVIEW;
            rebuildWidgets();
        }
        if (++ticks % 20 == 0) {
            if (tab == Tab.MODS) inventory = ModInventory.snapshot();
            updateText();
        }
        refreshActions();
    }

    private void refreshActions() {
        if (update != null) update.active = DumSdkClient.updater().canCheck();
        if (crash != null) crash.active = !scanning && !files.crashes().isEmpty();
        if (log != null) log.active = files.clientLog();
        if (helper != null) helper.active = files.helperLog();
        if (newer != null) {
            var list = DumSdkClient.releases().snapshot().releases();
            var selected = release();
            int index = selected == null ? -1 : list.indexOf(selected);
            newer.active = index > 0;
            older.active = index >= 0 && index < list.size() - 1;
            more.active = !DumSdkClient.releases().loading() && DumSdkClient.releases().snapshot().more();
        }
    }

    private void updateText() {
        if (body == null) return;
        if (body instanceof ModCardsWidget cards) {
            List<ModInventory.Item> selected = !DumSdkClient.serverActive() ? List.of() : inventory.stream()
                    .filter(item -> item.matches(query)).toList();
            cards.setItems(selected, "Найдено: " + selected.size() + " · Всего: " + inventory.size());
            return;
        }
        Component text = switch (tab) {
            case ERRORS -> errors(); case FILES -> fileText(); case UPDATES -> releaseText();
            case MODS -> Component.empty(); default -> join(DumSdkClient.diagnosticsReport());
        };
        if (!text.getString().equals(displayed)) {
            displayed = text.getString(); body.setMessage(text); body.refreshScrollAmount();
        }
    }

    private ReleaseCatalog.Release release() {
        var list = DumSdkClient.releases().snapshot().releases();
        var selected = list.stream().filter(r -> r.tag().equals(selectedRelease)).findFirst().orElse(list.isEmpty() ? null : list.getFirst());
        if (selected != null) selectedRelease = selected.tag();
        return selected;
    }

    private void selectRelease(int direction) {
        var list = DumSdkClient.releases().snapshot().releases();
        var selected = release();
        int index = selected == null ? -1 : list.indexOf(selected);
        if (index < 0) return;
        selectedRelease = list.get(Math.clamp(index + direction, 0, list.size() - 1)).tag();
        body.setScrollAmount(0);
        updateText(); refreshActions();
    }

    private Component releaseText() {
        var status = DumSdkClient.updater().status();
        if (update != null) update.setTooltip(Tooltip.create(Component.literal("Установлено: " + DumSdkClient.version() + "\n" + status.title() + "\n" + status.message())));
        var snapshot = DumSdkClient.releases().snapshot();
        var text = Component.empty();
        var release = release();
        if (release != null) {
            int index = snapshot.releases().indexOf(release);
            String label = release.version().equals(DumSdkClient.version()) ? " · установлено" : "";
            text.append(Component.literal("DUM-SDK " + release.tag() + label + "  [" + (index + 1) + "/" + snapshot.releases().size() + "]\n").withStyle(ChatFormatting.GOLD));
            if (!release.name().isBlank() && !release.name().contains(release.version())) text.append(Component.literal(release.name() + "\n").withStyle(ChatFormatting.WHITE));
            if (!release.published().isBlank()) {
                String date = release.published();
                try { date = DATE.format(java.time.Instant.parse(date)); } catch (java.time.DateTimeException ignored) { }
                text.append(Component.literal("Опубликован: " + date + "\n").withStyle(ChatFormatting.GRAY));
            }
            if (release.prerelease()) text.append(Component.literal("Предварительный релиз\n").withStyle(ChatFormatting.YELLOW));
            String notes = release.notes().isBlank() ? "Описание не указано" : plainNotes(release.notes());
            text.append(Component.literal("\n" + notes + "\n").withStyle(ChatFormatting.GRAY));
        }
        if (DumSdkClient.releases().loading()) text.append(Component.literal("Загружаем описания релизов...\n").withStyle(ChatFormatting.YELLOW));
        if (!snapshot.error().isEmpty()) {
            text.append(Component.literal("\n" + snapshot.error() + "\n").withStyle(ChatFormatting.RED));
            if (!snapshot.error().equals(lastReleaseError)) {
                lastReleaseError = snapshot.error();
                DumSdkClient.recordError(snapshot.error().contains("RELEASES_DATA") ? "RELEASES_DATA" : "RELEASES_NETWORK", snapshot.error());
                feedback("Не удалось загрузить релизы", snapshot.releases().isEmpty() ? "Проверь интернет или открой GitHub" : "Показаны сохранённые описания. Подробнее: Ошибки");
            }
        } else lastReleaseError = "";
        if (snapshot.cached() && !snapshot.releases().isEmpty()) text.append(Component.literal("Сохранённые описания, проверено: " + DATE.format(snapshot.checked()) + "\n").withStyle(ChatFormatting.YELLOW));
        if (release == null && !DumSdkClient.releases().loading()) text.append(Component.literal("Релизов пока нет. Нажми Обновить данные или открой GitHub.\n").withStyle(ChatFormatting.GRAY));
        return text;
    }

    static String plainNotes(String markdown) {
        return markdown.replaceAll("(?m)^#{1,6}\\s+", "").replace("```", "").replace("`", "")
                .replace("**", "").replaceAll("!?\\[([^\\]]*)\\]\\(([^)]*)\\)", "$1 ($2)");
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
        if (DumSdkClient.serverActive()) {
            text.append("\nМодификации:\n");
            for (var item : ModInventory.snapshot()) text.append(item.id()).append(' ').append(item.version()).append(" · ").append(item.type()).append(" · ").append(item.state()).append('\n');
        }
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
