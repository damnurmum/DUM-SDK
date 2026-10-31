package ru.dum.sdk.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import ru.dum.sdk.fabric.diagnostics.ModCardsWidget;
import ru.dum.sdk.fabric.diagnostics.ModDetailsScreen;
import ru.dum.sdk.fabric.diagnostics.DiagnosticsScreen;
import ru.dum.sdk.fabric.diagnostics.ModInventory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Requires the external hubprobe module and a local paginated release-history fixture. */
final class HubGameTest {
    private static final int TIMEOUT = 20 * 60;

    static void run(ClientGameTestContext context) {
        control("online");
        context.setScreen(() -> new OptionsScreen(new TitleScreen(), Minecraft.getInstance().options, false));
        context.runOnClient(client -> button(client.gui.screen(), "DUM-SDK").onPress(null));
        context.runOnClient(client -> check(!has(client.gui.screen(), "Модификации"), "no modifications before a server handshake"));
        click(context, "Обновления");
        context.waitFor(client -> !DumSdkClient.releases().loading() && !DumSdkClient.releases().snapshot().releases().isEmpty(), TIMEOUT);
        context.waitTicks(25);
        check(text(context).contains("Hub release notes fixture"), "release descriptions are rendered");
        click(context, "Старее");
        check(text(context).contains("v0.14.1"), "older releases are browsable");
        click(context, "Релиз на GitHub");
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof ConfirmLinkScreen, "release link uses native confirmation");
            check(client.gui.screen().getNarrationMessage().getString().contains("github.com/damnurmum/DUM-SDK/releases/tag/v0.14.1"), "selected release uses the official repository");
            button(client.gui.screen(), CommonComponents.GUI_CANCEL.getString()).onPress(null);
        });
        control("offline");
        click(context, "Обновить данные");
        context.waitFor(client -> !DumSdkClient.releases().loading() && !DumSdkClient.releases().snapshot().error().isEmpty(), TIMEOUT);
        context.waitTicks(25);
        check(text(context).contains("Сохранённые описания") && text(context).contains("RELEASES_NETWORK"), "failed refresh keeps cached descriptions and explains the error");
        context.takeScreenshot("dum-sdk-hub-releases");

        String address = System.getProperty("dumsdk.testServer", "localhost:25568");
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("SDK hub test", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.runOnClient(client -> check(DumSdkClient.serverActive(), "accepted server session is active"));
        context.setScreen(() -> new PauseScreen(true));
        click(context, "Модификации");
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTicks(5);
        context.runOnClient(client -> {
            var cards = cards(client.gui.screen());
            check(cards.items().size() == 1, "one server module has one card");
            check(!has(client.gui.screen(), "Серверные") && !has(client.gui.screen(), "Клиентские") && !has(client.gui.screen(), "Все"), "source filter buttons are absent");
            var expected = DumSdkClient.serverCatalog().stream().map(ru.dum.sdk.network.Message.ModuleInfo::id).collect(java.util.stream.Collectors.toSet());
            check(cards.items().stream().allMatch(item -> item.server() && expected.contains(item.id()))
                    && cards.items().size() == expected.size(), "cards contain only the current accepted server catalog");
            check(cards.items().stream().noneMatch(item -> item.id().equals("dum-sdk") || item.id().equals("fabric-api")), "local SDK and Fabric mods are excluded");
            cards.mouseClicked(new MouseButtonEvent(cards.getX() + 12, cards.getY() + 24, new MouseButtonInfo(0, 0)), false);
            check(client.gui.screen() instanceof ModDetailsScreen, "clicking a card opens its details");
        });
        check(text(context).contains("Тестовый модуль хаба") && text(context).contains("Проверка описания модуля"), "module name and description came from the downloaded jar");
        check(text(context).contains("Авторы: DUM") && text(context).contains("Активен"), "module authors and active state are visible");
        context.runOnClient(client -> {
            String previous = client.keyboardHandler.getClipboard();
            try {
                button(client.gui.screen(), "Скопировать сведения").onPress(null);
                check(client.keyboardHandler.getClipboard().contains("Проверка описания модуля"), "card details can be copied");
            } finally { client.keyboardHandler.setClipboard(previous); }
        });
        context.takeScreenshot("dum-sdk-hub-module-details");
        context.runOnClient(client -> client.gui.screen().onClose());
        context.takeScreenshot("dum-sdk-hub-server-modules");
        context.runOnClient(client -> edit(client.gui.screen()).setValue("nothing-matches-this"));
        check(text(context).contains("Ничего не найдено"), "search filters modifications");
        context.runOnClient(client -> edit(client.gui.screen()).setValue(""));
        context.runOnClient(client -> {
            var cards = cards(client.gui.screen());
            check(cards.items().size() == 1 && cards.items().getFirst().id().equals("hubprobe"), "clearing search restores only server modifications");
            cards.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_END, 0, 0));
            check(!cards.selectedKey().isEmpty(), "keyboard navigation selects a server card");
            cards.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, 0, 0));
            check(cards.scrollAmount() == 0, "Home returns to the first card");
            client.getWindow().setWindowed(1280, 720);
        });
        context.waitTicks(25);
        context.takeScreenshot("dum-sdk-hub-server-cards-wide");
        context.runOnClient(client -> {
            var cards = cards(client.gui.screen());
            cards.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_END, 0, 0));
            String selected = cards.selectedKey();
            double scroll = cards.scrollAmount();
            cards.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
            check(client.gui.screen() instanceof ModDetailsScreen, "Enter opens the selected card");
            client.gui.screen().onClose();
            check(cards(client.gui.screen()).selectedKey().equals(selected) && cards(client.gui.screen()).scrollAmount() == scroll, "return keeps card selection and scroll");
        });
        context.runOnClient(client -> client.getWindow().setWindowed(854, 480));
        context.waitTicks(25);
        context.runOnClient(client -> client.resizeGui());
        context.waitTicks(10);
        context.runOnClient(client -> {
            for (var widget : Screens.getWidgets(client.gui.screen())) if (widget instanceof Button) {
                check(widget.getY() >= 0 && widget.getY() + widget.getHeight() <= client.gui.screen().height, "hub controls stay inside the resized screen");
            }
        });
        context.setScreen(() -> null);
        KeyMapping fail = context.computeOnClient(client -> KeyMapping.get("key.hubprobe.fail"));
        check(fail != null, "fixture registered its failure key");
        context.runOnClient(client -> { fail.setKey(InputConstants.getKey("key.keyboard.f8")); KeyMapping.resetMapping(); });
        context.getInput().pressKey(fail);
        context.waitTicks(25);
        context.setScreen(() -> new DiagnosticsScreen(null, DiagnosticsScreen.Tab.MODS));
        context.runOnClient(client -> cards(client.gui.screen()).keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0)));
        check(text(context).contains("Не запущен") && DumSdkClient.serverActive(), "failed optional module remains listed without ending the session");
        context.runOnClient(client -> client.gui.screen().onClose());
        click(context, "Ошибки");
        check(text(context).contains("hubprobe"), "failure details are available in diagnostics");
        context.runOnClient(Minecraft::disconnectWithProgressScreen);
        context.waitFor(client -> client.level == null, TIMEOUT);
        context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
        context.setScreen(() -> new DiagnosticsScreen(null));
        context.runOnClient(client -> check(!has(client.gui.screen(), "Модификации") && ModInventory.snapshot().isEmpty(), "disconnect removes server controls and inventory"));
        context.setScreen(TitleScreen::new);
    }

    private static void control(String mode) {
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(System.getProperty("dumsdk.hubApi") + "/" + mode)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) { throw new AssertionError("Cannot switch the local fixture offline", e); }
    }
    private static void click(ClientGameTestContext context, String label) { context.runOnClient(client -> button(client.gui.screen(), label).onPress(null)); }
    private static Button button(Screen screen, String label) {
        return (Button) Screens.getWidgets(screen).stream().filter(w -> w instanceof Button && w.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
    }
    private static boolean has(Screen screen, String label) { return Screens.getWidgets(screen).stream().anyMatch(w -> w instanceof Button && w.getMessage().getString().equals(label)); }
    private static ModCardsWidget cards(Screen screen) { return (ModCardsWidget) Screens.getWidgets(screen).stream().filter(w -> w instanceof ModCardsWidget).findFirst().orElseThrow(); }
    private static EditBox edit(Screen screen) { return (EditBox) Screens.getWidgets(screen).stream().filter(w -> w instanceof EditBox).findFirst().orElseThrow(); }
    private static String text(ClientGameTestContext context) { return context.computeOnClient(client -> Screens.getWidgets(client.gui.screen()).stream().filter(w -> w instanceof AbstractTextAreaWidget).findFirst().orElseThrow().getMessage().getString()); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); DumSdkClient.LOG.info("SDK hub test: {}", message); }
}
