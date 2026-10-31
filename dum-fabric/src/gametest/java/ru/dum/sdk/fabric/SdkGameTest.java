package ru.dum.sdk.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.fabric.module.ModuleManager;
import ru.dum.sdk.fabric.module.AppearanceHooks;
import ru.dum.sdk.api.EquipmentRenderLayer;
import ru.dum.sdk.api.ServerSession;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * End to end, against a running test server (DUM-SDK, DUM-Runtime-Test, info and sdktest, no cold mods):
 * join, check the handshake and the modules, break sdktest with its key and check that only it went down,
 * look at /sdk, leave and check that everything is unloaded. Screenshots go to the run folder.
 * Start the server first, then ./gradlew :dum-fabric:runClientGameTest [-PtestServer=host:port].
 * -PplainServer=host:port also checks a transition to an ordinary server without the plugin.
 */
public final class SdkGameTest implements FabricClientGameTest {

    private static final int MINUTE = 20 * 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("dumsdk.hubTest")) {
            HubGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("dumsdk.downloadTest")) {
            DownloadValidationGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("dumsdk.standaloneTest")) {
            StandalonePaperGameTest.run(context);
            return;
        }
        if (Boolean.getBoolean("dumsdk.coldInstallTest")) {
            ColdSessionGameTest.install(context);
            return;
        }
        if (Boolean.getBoolean("dumsdk.coldTest")) {
            ColdSessionGameTest.run(context);
            return;
        }
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

        ItemStack[] probe = {ItemStack.EMPTY};
        ItemStack[] chest = {ItemStack.EMPTY};
        context.runOnClient(client -> {
            check(DumSdkClient.serverVersion() != null, "the server accepted the handshake");
            check(running().containsAll(List.of("info", "sdktest")), "info and sdktest run, got " + running());
            check(DumSdkClient.modules().failures().isEmpty(), "no module failed, got " + DumSdkClient.modules().failures());
            check(ServerSession.active(), "session effects activate only after installation and play join");
            probe[0] = client.player.getInventory().getItem(8).copy();
            check(probe[0].is(Items.PAPER), "Paper fixture supplied the vanilla paper carrier in slot 8");
            check(probe[0].getHoverName().getString().equals("Тестовый предмет DUM"), "the active module supplies the item name");
            Identifier originalModel = probe[0].get(DataComponents.ITEM_MODEL);
            check(Identifier.withDefaultNamespace("diamond").equals(AppearanceHooks.item(probe[0]).get(DataComponents.ITEM_MODEL)),
                    "the active module supplies the item model");
            check(java.util.Objects.equals(originalModel, probe[0].get(DataComponents.ITEM_MODEL)), "item appearance does not mutate the carrier");
            chest[0] = new ItemStack(Items.DIAMOND_CHESTPLATE);
            CompoundTag values = new CompoundTag();
            values.putBoolean("sdktest:wings", true);
            CompoundTag data = new CompoundTag();
            data.put("PublicBukkitValues", values);
            chest[0].set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            check(AppearanceHooks.equipment(EquipmentRenderLayer.WINGS, chest[0]).is(Items.ELYTRA), "a hot module supplies the wings pass");
            check(AppearanceHooks.equipment(EquipmentRenderLayer.ARMOR, chest[0]) == chest[0], "the armor pass retains the actual chestplate");
        });
        context.takeScreenshot("dum-sdk-joined");

        // The test player may come back dead (mobs on the test server at night): no screen may take the key press
        context.runOnClient(client -> {
            if (client.player.isDeadOrDying()) client.player.respawn();
        });
        context.waitFor(client -> client.player != null && !client.player.isDeadOrDying() && !(client.gui.screen() instanceof DeathScreen), MINUTE);
        context.setScreen(() -> null);

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
            passive(probe[0], chest[0]);
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
        context.runOnClient(client -> {
            check(running().isEmpty(), "every module is unloaded after leaving, still " + running());
            check(!ServerSession.active(), "the session deactivates on disconnect");
            passive(probe[0], chest[0]);
        });
        // Dropping the modules' resources reloads them; that loading screen once never closed
        context.waitFor(client -> client.gui.overlay() == null, MINUTE);
        context.runOnClient(client -> check(client.gui.overlay() == null, "the loading screen after leaving closes"));
        context.setScreen(TitleScreen::new);

        // Restore a healthy module session before switching servers, so cleanup is not tested only after a module failure.
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("DUM-SDK reconnect", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, MINUTE);
        context.waitTicks(20);
        context.runOnClient(client -> {
            check(client.level != null && ServerSession.active() && running().contains("sdktest"), "rejoining restores a fresh healthy module session");
            check(probe[0].getHoverName().getString().equals("Тестовый предмет DUM"), "item definitions activate again after rejoining");
            check(AppearanceHooks.equipment(EquipmentRenderLayer.WINGS, chest[0]).is(Items.ELYTRA), "equipment callbacks activate again after rejoining");
        });
        context.runOnClient(Minecraft::disconnectWithProgressScreen);
        context.waitFor(client -> client.level == null && client.gui.overlay() == null, MINUTE);
        context.runOnClient(client -> {
            check(!ServerSession.active() && running().isEmpty(), "leaving a healthy session unloads its modules");
            passive(probe[0], chest[0]);
        });
        context.setScreen(TitleScreen::new);

        String plain = System.getProperty("dumsdk.plainServer");
        if (plain != null) {
            context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(plain),
                    new ServerData("Server without DUM-SDK", plain, ServerData.Type.OTHER), false, null));
            context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, MINUTE);
            context.waitTicks(20);
            context.runOnClient(client -> {
                check(client.level != null && !(client.gui.screen() instanceof DisconnectedScreen), "joined the ordinary server");
                check(DumSdkClient.serverVersion() == null && !ServerSession.active(), "no SDK session without a successful handshake");
                check(running().isEmpty(), "no cached modules start on the ordinary server");
                passive(probe[0], chest[0]);
            });
            context.runOnClient(Minecraft::disconnectWithProgressScreen);
            context.waitFor(client -> client.level == null && client.gui.overlay() == null, MINUTE);
            context.setScreen(TitleScreen::new);
        }
    }

    private static List<String> running() {
        return DumSdkClient.modules().status().stream().map(ModuleManager.Status::id).toList();
    }

    private static void passive(ItemStack probe, ItemStack chest) {
        check(probe.getHoverName().getString().equals(new ItemStack(Items.PAPER).getHoverName().getString()), "inactive definitions use the vanilla item name");
        check(AppearanceHooks.item(probe) == probe, "inactive definitions do not replace the item model");
        check(AppearanceHooks.equipment(EquipmentRenderLayer.WINGS, chest) == chest, "inactive modules do not affect equipment rendering");
    }

    private static void check(boolean condition, String expectation) {
        if (!condition) throw new AssertionError("Expected: " + expectation);
        DumSdkClient.LOG.info("Game test: {}", expectation);
    }
}
