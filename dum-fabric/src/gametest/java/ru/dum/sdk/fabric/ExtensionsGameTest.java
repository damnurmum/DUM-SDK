package ru.dum.sdk.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.api.DumContext;
import ru.dum.sdk.api.ServerSession;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;

/** External hookbase mixin + hookconsumer hot jar + Paper event probe; no gameplay fixtures in the SDK. */
final class ExtensionsGameTest {
    private static final int TIMEOUT = 20 * 60;
    private static final String MARK = " [DUM hooks]";

    static void run(ClientGameTestContext context) {
        String address = System.getProperty("dumsdk.testServer", "localhost");
        if (System.getProperty("dumsdk.extensionsPhase", "runtime").equals("fallback")) {
            join(context, address);
            context.runOnClient(client -> healthy());
            int reports = (int) control("reports").lines().count();
            control("block-kick");
            context.runOnClient(client -> { set("failConsumer", true); title(); });
            context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
            context.runOnClient(client -> {
                String text = client.gui.screen().getNarrationMessage().getString();
                check(text.contains("MODULE_RUNTIME") && !text.contains("Сервер принял отчёт"), "local fallback disconnects if another plugin cancels Paper's kick");
                passive();
            });
            check(control("reports").lines().count() == reports + 1, "fallback still reports exactly once to Paper");
            control("allow-kick");
            context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
            context.runOnClient(client -> set("failConsumer", false));
            join(context, address);
            context.waitTicks(80);
            context.runOnClient(client -> healthy());
            leave(context);
            context.setScreen(TitleScreen::new);
            return;
        }
        if (System.getProperty("dumsdk.extensionsPhase", "runtime").equals("install")) {
            join(context, address);
            context.runOnClient(client -> {
                check(client.gui.screen() instanceof DisconnectedScreen && !ServerSession.active(), "first cold provider installation requests restart");
                try {
                    check(Files.readString(FabricLoader.getInstance().getGameDir().resolve("dum-sdk/cold-plan.txt")).contains("dum-cold-hookbase.jar"),
                            "cold helper stages the provider jar");
                } catch (java.io.IOException e) { throw new AssertionError(e); }
            });
            context.setScreen(TitleScreen::new);
            return;
        }

        context.runOnClient(client -> passive());
        join(context, address);
        context.runOnClient(client -> {
            healthy();
            check((Integer) field("instances") == 1, "Fabric creates one provider instance for the game process");
            ClientPlayNetworking.send(new SdkPayload(Protocol.encode(new Message.ModuleFailure("ungranted", "test", "not installed"))));
        });
        context.waitTicks(20);
        context.runOnClient(client -> check(ServerSession.active(), "an ungranted failure report cannot revoke another catalog"));

        DumContext previous = context.computeOnClient(client -> (DumContext) field("lastContext"));
        leave(context);
        context.runOnClient(client -> {
            passive();
            check(!previous.active(), "old provider context is inactive after leave");
            try {
                previous.onTick(() -> { throw new AssertionError("stale context"); });
                throw new AssertionError("stale provider accepted a listener");
            } catch (IllegalStateException expected) { check(true, "old provider context rejects late registrations"); }
        });
        for (String property : new String[]{"dumsdk.plainServer", "dumsdk.otherSdkServer"}) {
            String other = System.getProperty(property);
            if (other == null) continue;
            join(context, other);
            context.runOnClient(client -> {
                check(client.level != null, "joined another server without activating the provider");
                check(ServerSession.active() == property.endsWith("otherSdkServer"), "SDK session follows the target handshake");
                passive();
            });
            leave(context);
        }
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.level != null && client.player != null, TIMEOUT);
            context.runOnClient(client -> passive());
        }
        context.waitFor(client -> client.level == null && client.gui.overlay() == null, TIMEOUT);
        join(context, address);
        context.runOnClient(client -> {
            healthy();
            check(field("lastContext") != previous && !previous.active(), "rejoin creates a new hook scope");
            check((Integer) field("instances") == 1, "native hook provider was reused without another Fabric load");
            set("failConsumer", true);
            title();
        });
        failure(context, "hookconsumer", 1);

        context.runOnClient(client -> set("failConsumer", false));
        join(context, address);
        context.runOnClient(client -> { healthy(); set("failProvider", true); });
        failure(context, "hookbase", 2);
        context.runOnClient(client -> set("failProvider", false));

        join(context, address);
        context.runOnClient(client -> healthy());
        control("bad");
        publish(context);
        join(context, address);
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof DisconnectedScreen && !ServerSession.active(), "incompatible required hook contract prevents world entry");
            check(client.gui.screen().getNarrationMessage().getString().contains("MODULE_START_FAILED"), "contract failure uses the startup error code");
            passive();
        });
        control("required");
        control("publish-required");
        join(context, address);
        context.runOnClient(client -> { healthy(); set("failConsumer", true); title(); });
        failure(context, "hookconsumer", 3);
        context.runOnClient(client -> set("failConsumer", false));
        join(context, address);
        context.runOnClient(client -> healthy());
        leave(context);
        context.runOnClient(client -> passive());
        context.setScreen(TitleScreen::new);
    }

    private static void failure(ClientGameTestContext context, String module, int count) {
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.runOnClient(client -> {
            String text = client.gui.screen().getNarrationMessage().getString();
            check(text.contains("Сервер принял отчёт") && text.contains(module) && text.contains("MODULE_RUNTIME"), "Paper received the failure and sent its configured reconnect message");
            check(!ServerSession.active() && DumSdkClient.modules().status().isEmpty(), "runtime failure disables the entire SDK session");
            passive();
        });
        var lines = control("reports").lines().toList();
        check(lines.size() == count && lines.getLast().startsWith(module + "|true|"), "one Paper event per failure, after revoking module grants");
        context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
    }

    private static void publish(ClientGameTestContext context) {
        context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload hookconsumer"));
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
    }

    private static void healthy() {
        check(ServerSession.active("hookbase") && DumSdkClient.modules().status().stream().anyMatch(m -> m.id().equals("hookconsumer")), "provider starts before the hot consumer");
        check(title().endsWith(MARK) && !title().endsWith(MARK + MARK), "real cold mixin dispatches exactly one managed hot hook listener");
    }

    private static void passive() {
        check(!ServerSession.active("hookbase") && !title().contains(MARK), "loaded mixin leaves vanilla behavior outside its accepted catalog");
    }

    private static void join(ClientGameTestContext context, String address) {
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("SDK extension test", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.waitTicks(20);
    }

    private static void leave(ClientGameTestContext context) {
        context.runOnClient(Minecraft::disconnectWithProgressScreen);
        context.waitFor(client -> client.level == null && client.gui.overlay() == null, TIMEOUT);
        context.setScreen(TitleScreen::new);
    }

    private static String title() {
        try { var method = Minecraft.class.getDeclaredMethod("createTitle"); method.setAccessible(true); return (String) method.invoke(Minecraft.getInstance()); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object field(String name) {
        try { return Class.forName("fixture.HookBase").getField(name).get(null); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(String name, boolean value) {
        try { Class.forName("fixture.HookBase").getField(name).set(null, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static String control(String action) {
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(System.getProperty("dumsdk.extensionsTest") + "/" + action))
                    .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new AssertionError("Fixture HTTP " + response.statusCode() + ": " + action);
            return response.body();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        DumSdkClient.LOG.info("Extension test: {}", message);
    }
}
