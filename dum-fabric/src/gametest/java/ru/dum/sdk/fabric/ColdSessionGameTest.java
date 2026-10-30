package ru.dum.sdk.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.api.DumContext;
import ru.dum.sdk.api.ServerSession;

/** Uses the external dumcoldtest 0.4.0+ fixture; its actual title mixin and counters expose lifecycle effects. */
final class ColdSessionGameTest {
    private static final int TIMEOUT = 20 * 60;
    private static final String ID = "dumcoldtest";
    private static final String MARK = " + DUM cold load ";

    static void install(ClientGameTestContext context) {
        join(context, System.getProperty("dumsdk.testServer", "localhost"));
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof DisconnectedScreen, "first cold install requires restart");
            check(!ServerSession.active() && !ServerSession.active(ID), "a restart request cannot activate the mod");
            try {
                var root = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                String plan = java.nio.file.Files.readString(root.resolve("dum-sdk/cold-plan.txt"));
                check(plan.contains("dum-cold-dumcoldtest.jar") && plan.contains("copy\t"), "the helper has a cold install plan");
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        });
        context.setScreen(TitleScreen::new);
    }

    static void run(ClientGameTestContext context) {
        context.runOnClient(client -> passive(false));
        String address = System.getProperty("dumsdk.testServer", "localhost");
        DumContext[] previous = {null};
        for (int session = 1; session <= 2; session++) {
            join(context, address);
            int count = session;
            context.runOnClient(client -> {
                check(client.level != null && ServerSession.active(ID), "catalog activates this cold mod in play");
                check(title().contains(MARK), "the real cold mixin changes the title on its server");
                check(((Integer) field("enables")) == count, "one enable per connection");
                if (previous[0] != null) {
                    check(!previous[0].active(), "a retained old context stays inactive on reconnect");
                    try {
                        previous[0].onTick(() -> { throw new AssertionError("stale callback"); });
                        throw new AssertionError("A stale context accepted a registration");
                    } catch (IllegalStateException expected) {
                        check(true, "a retained old context refuses late registrations");
                    }
                }
                previous[0] = (DumContext) field("lastContext");
                check(previous[0].active(), "the current cold context is active");
            });
            leave(context);
            context.runOnClient(client -> {
                passive(false);
                check(!previous[0].active(), "the context deactivates after leaving");
                check(((Integer) field("disables")) == count && ((Integer) field("cleanups")) == count,
                        "disable and managed cleanup run once per connection");
                check((Boolean) field("cleanupSawInactive"), "cleanup sees an already inactive context");
            });
        }
        int ticks = context.computeOnClient(client -> (Integer) field("ticks"));
        String other = System.getProperty("dumsdk.otherSdkServer");
        if (other != null) {
            join(context, other);
            context.runOnClient(client -> {
                check(client.level != null, "a compliant inactive mod does not force restart on another SDK server");
                passive(true);
                check(((Integer) field("enables")) == 2 && ((Integer) field("ticks")) == ticks,
                        "an SDK catalog without this mod cannot enable its code or ticks");
            });
            leave(context);
        }
        String plain = System.getProperty("dumsdk.plainServer");
        if (plain != null) {
            join(context, plain);
            context.runOnClient(client -> {
                check(client.level != null, "joined a server without the handshake plugin");
                passive(false);
                check(((Integer) field("ticks")) == ticks, "cold tick handlers do not leak to an ordinary server");
            });
            leave(context);
        }
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.level != null && client.player != null, TIMEOUT);
            context.waitTicks(20);
            context.runOnClient(client -> {
                passive(false);
                check(((Integer) field("ticks")) == ticks, "cold tick handlers do not leak to singleplayer");
            });
        }
        context.waitFor(client -> client.level == null && client.gui.overlay() == null, TIMEOUT);
        join(context, address);
        context.runOnClient(client -> {
            check(ServerSession.active(ID) && title().contains(MARK), "returning to the original server restores its cold mod");
            setField("failNextTick", true);
        });
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.waitTicks(20);
        context.runOnClient(client -> {
            passive(false);
            check(((Integer) field("enables")) == 3 && ((Integer) field("disables")) == 3 && ((Integer) field("cleanups")) == 3,
                    "a required cold mod failure disconnects and cleans up exactly once");
            check(!((DumContext) field("lastContext")).active(), "the failed context stays inactive");
        });
        context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
        context.setScreen(TitleScreen::new);
    }

    private static void join(ClientGameTestContext context, String address) {
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("Cold session test", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, TIMEOUT);
        context.waitTicks(20);
    }

    private static void leave(ClientGameTestContext context) {
        context.runOnClient(Minecraft::disconnectWithProgressScreen);
        context.waitFor(client -> client.level == null && client.gui.overlay() == null, TIMEOUT);
        context.waitTicks(10);
        context.setScreen(TitleScreen::new);
    }

    private static void passive(boolean sdk) {
        check(ServerSession.active() == sdk && !ServerSession.active(ID), "only catalog membership activates a cold mod");
        check(!title().contains(MARK), "the loaded mixin leaves the vanilla title unchanged outside its server");
    }

    private static String title() {
        try {
            var method = Minecraft.class.getDeclaredMethod("createTitle");
            method.setAccessible(true);
            return (String) method.invoke(Minecraft.getInstance());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Object field(String name) {
        try { return Class.forName("ru.dum.sdk.coldtest.ColdTestMod").getField(name).get(null); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void setField(String name, Object value) {
        try { Class.forName("ru.dum.sdk.coldtest.ColdTestMod").getField(name).set(null, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String expectation) {
        if (!condition) throw new AssertionError("Expected: " + expectation);
        DumSdkClient.LOG.info("Cold session test: {}", expectation);
    }
}
