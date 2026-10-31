package ru.dum.sdk.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.api.ServerSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

/** Uses external signed publishhot/publishstable/publishcold jars and a staging-control HTTP fixture. */
final class PublicationGameTest {
    private static final int TIMEOUT = 20 * 60;

    static void run(ClientGameTestContext context) {
        String phase = System.getProperty("dumsdk.publicationPhase", "hot");
        join(context);
        context.runOnClient(client -> check(client.level != null && DumSdkClient.serverActive(), "joined the published catalog"));
        if (phase.equals("verify")) {
            versions(context, "2.0.0", "2.0.0");
            context.runOnClient(client -> check(ServerSession.active("publishcold")
                    && FabricLoader.getInstance().getModContainer("publishcold").orElseThrow().getMetadata().getVersion().getFriendlyString().equals("2.0.0"),
                    "updated cold jar activates after restarting the game"));
            reload(context, "");
            join(context);
            versions(context, "2.0.0", "2.0.0");
            context.runOnClient(client -> check(ServerSession.active("publishcold"), "full reload preserves an unchanged loaded cold mod"));
            context.runOnClient(Minecraft::disconnectWithProgressScreen);
            context.waitFor(client -> client.level == null && client.gui.overlay() == null, TIMEOUT);
        } else if (phase.equals("cold")) {
            versions(context, "2.0.0", "2.0.0");
            context.runOnClient(client -> check(ServerSession.active("publishcold"), "installed cold mod is active before its update"));
            control("stage-cold-update");
            reload(context, "publishcold");
            join(context);
            restart(context);
        } else {
            versions(context, "1.0.0", "1.0.0");
            control("stage-hot");
            versions(context, "1.0.0", "1.0.0");
            context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload missing"));
            control("failed");
            context.waitTicks(20);
            context.runOnClient(client -> check(client.level != null && DumSdkClient.serverActive(), "failed selection does not disconnect the player"));
            reload(context, "publishhot");
            join(context);
            versions(context, "2.0.0", "1.0.0");
            reload(context, "publishstable");
            join(context);
            versions(context, "2.0.0", "2.0.0");
            control("stage-cold");
            reload(context, "publishhot publishcold");
            join(context);
            restart(context);
        }
        context.setScreen(TitleScreen::new);
    }

    private static void reload(ClientGameTestContext context, String ids) {
        context.runOnClient(client -> client.player.connection.sendCommand(ids.isBlank() ? "dumsdk reload" : "dumsdk reload " + ids));
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.runOnClient(client -> check(!ServerSession.active()
                && client.gui.screen().getNarrationMessage().getString().contains("обновлены"), "successful selective publication disconnects with the reconnect message"));
        context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
    }

    private static void join(ClientGameTestContext context) {
        String address = System.getProperty("dumsdk.testServer", "localhost");
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("Selective publication test", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.waitTicks(20);
    }

    private static void versions(ClientGameTestContext context, String hot, String stable) {
        context.runOnClient(client -> {
            var modules = DumSdkClient.modules().status();
            check(modules.stream().anyMatch(m -> m.id().equals("publishhot") && m.version().equals(hot))
                    && modules.stream().anyMatch(m -> m.id().equals("publishstable") && m.version().equals(stable)),
                    "running versions are publishhot=" + hot + ", publishstable=" + stable);
        });
    }

    private static void restart(ClientGameTestContext context) {
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof DisconnectedScreen && !ServerSession.active(), "cold installation refuses world entry until restart");
            check(client.gui.screen().getNarrationMessage().getString().contains("Перезапусти игру"), "cold installation explains the required game restart");
            try {
                String plan = Files.readString(FabricLoader.getInstance().getGameDir().resolve("dum-sdk/cold-plan.txt"));
                check(plan.contains("dum-cold-publishcold.jar") && plan.contains("copy\t"), "cold update stages a helper installation plan");
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        });
    }

    private static void control(String action) {
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(System.getProperty("dumsdk.publicationTest") + "/" + action))
                    .timeout(java.time.Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding());
            check(response.statusCode() == 200, "external publication fixture: " + action);
        } catch (Exception e) { throw new AssertionError("Cannot control the external publication fixture", e); }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        DumSdkClient.LOG.info("Publication test: {}", message);
    }
}
