package ru.dum.sdk.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.fabric.diagnostics.DiagnosticsScreen;

/** -Ddumsdk.readmeShots=true: joins the test server in a larger window and takes the README's screenshots of the SDK menu. */
final class ReadmeShots {

    static void run(ClientGameTestContext context) {
        context.getInput().resizeWindow(1600, 900);
        context.runOnClient(client -> {
            client.options.guiScale().set(3);
            client.resizeGui();
        });
        String address = System.getProperty("dumsdk.testServer", "localhost");
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("DUM", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null && DumSdkClient.serverActive(), 20 * 60);
        context.waitTicks(60);
        for (DiagnosticsScreen.Tab tab : new DiagnosticsScreen.Tab[]{DiagnosticsScreen.Tab.MODS, DiagnosticsScreen.Tab.UPDATES, DiagnosticsScreen.Tab.OVERVIEW}) {
            context.setScreen(() -> new DiagnosticsScreen(null, tab));
            // Releases come from GitHub; toasts would cover the menu
            context.waitTicks(tab == DiagnosticsScreen.Tab.UPDATES ? 100 : 20);
            context.runOnClient(client -> client.gui.toastManager().clear());
            context.waitTicks(2);
            context.takeScreenshot("readme-" + tab.name().toLowerCase(java.util.Locale.ROOT));
        }
        context.setScreen(() -> null);
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTicks(20);
        context.takeScreenshot("readme-game");
        context.runOnClient(net.minecraft.client.Minecraft::disconnectWithProgressScreen);
        context.waitFor(client -> client.level == null && client.gui.overlay() == null, 20 * 60);
        context.setScreen(TitleScreen::new);
    }
}
