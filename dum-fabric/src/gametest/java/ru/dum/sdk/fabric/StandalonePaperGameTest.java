package ru.dum.sdk.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.api.ServerSession;

/** Run against Paper with only DUM-SDK, an empty catalog and an operator test player. No external fixtures. */
final class StandalonePaperGameTest {
    private static final int TIMEOUT = 20 * 60;

    static void run(ClientGameTestContext context) {
        String address = System.getProperty("dumsdk.testServer", "localhost");
        for (int attempt = 0; attempt < 3; attempt++) {
            context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                    new ServerData("Standalone SDK test", address, ServerData.Type.OTHER), false, null));
            context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
            context.waitTicks(20);
            context.runOnClient(client -> {
                check(client.level != null && client.player != null, "joined standalone Paper");
                check(ServerSession.active() && DumSdkClient.serverVersion() != null, "handshake completed without Core");
                check(DumSdkClient.modules().status().isEmpty(), "empty catalog needs no gameplay fixtures");
                client.player.connection.sendCommand("dumsdk");
            });
            if (attempt < 2) {
                context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload"));
                context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
                context.runOnClient(client -> {
                    check(!ServerSession.active(), "reload deactivates the client session");
                    check(client.gui.screen().getNarrationMessage().getString().contains("обновлены"),
                            "reload disconnect uses the bundled Russian reconnect message");
                });
            } else {
                context.runOnClient(Minecraft::disconnectWithProgressScreen);
                context.waitFor(client -> client.level == null, TIMEOUT);
                context.runOnClient(client -> check(!ServerSession.active(), "leaving deactivates the standalone session"));
            }
            context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
            context.setScreen(TitleScreen::new);
        }
    }

    private static void check(boolean condition, String result) {
        if (!condition) throw new AssertionError("Expected: " + result);
        DumSdkClient.LOG.info("Standalone Paper test: {}", result);
    }
}
