package ru.dum.sdk.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.fabric.module.ModuleManager;

import java.util.List;

/**
 * End to end, against a running test server (DUM-Core, DUM-SDK, the info and sdktest modules, no cold-load mods):
 * join, check the handshake and the modules, break sdktest with its key and check that only it went down,
 * look at /sdk, leave and check that everything is unloaded. Screenshots go to the run folder.
 * Start the server first, then ./gradlew :dum-fabric:runClientGameTest [-PtestServer=host:port].
 */
public final class SdkGameTest implements FabricClientGameTest {

    private static final int MINUTE = 20 * 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        String address = System.getProperty("dumsdk.testServer", "localhost");
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("DUM-SDK test", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, MINUTE);
        context.runOnClient(client -> {
            if (client.gui.screen() instanceof DisconnectedScreen) {
                throw new AssertionError("Disconnected instead of joining: " + client.gui.screen().getTitle().getString()
                        + ". Is the test server running, without cold-load mods in its catalog?");
            }
        });
        context.waitTicks(40);

        context.runOnClient(client -> {
            check(DumSdkClient.serverVersion() != null, "the server accepted the handshake");
            check(running().containsAll(List.of("info", "sdktest")), "info and sdktest run, got " + running());
            check(DumSdkClient.modules().failures().isEmpty(), "no module failed, got " + DumSdkClient.modules().failures());
        });
        context.takeScreenshot("dum-sdk-joined");

        // Break sdktest with its key: only it goes down, with a recorded failure
        KeyMapping fail = context.computeOnClient(client -> KeyMapping.get("key.sdktest.fail"));
        check(fail != null, "sdktest registered its keys");
        context.runOnClient(client -> {
            fail.setKey(InputConstants.getKey("key.keyboard.f8"));
            KeyMapping.resetMapping();
        });
        context.getInput().pressKey(fail);
        context.waitTicks(10);
        context.runOnClient(client -> {
            check(!running().contains("sdktest"), "sdktest is off after failing, still running " + running());
            check(running().contains("info"), "info keeps running");
            List<ModuleManager.Failure> failures = DumSdkClient.modules().failures();
            check(failures.size() == 1 && failures.getFirst().module().equals("sdktest")
                    && failures.getFirst().where().contains("key.sdktest.fail"), "the failure names sdktest and its key, got " + failures);
            client.player.connection.sendCommand("sdk");
        });
        context.waitTicks(10);
        context.takeScreenshot("dum-sdk-after-failure");

        context.runOnClient(Minecraft::disconnectWithProgressScreen);
        context.waitFor(client -> client.level == null, MINUTE);
        context.waitTicks(20);
        context.runOnClient(client -> check(running().isEmpty(), "every module is unloaded after leaving, still " + running()));
        context.setScreen(TitleScreen::new);
    }

    private static List<String> running() {
        return DumSdkClient.modules().status().stream().map(ModuleManager.Status::id).toList();
    }

    private static void check(boolean condition, String expectation) {
        if (!condition) throw new AssertionError("Expected: " + expectation);
        DumSdkClient.LOG.info("Game test: {}", expectation);
    }
}
