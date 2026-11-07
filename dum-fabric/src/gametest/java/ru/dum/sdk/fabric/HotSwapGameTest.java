package ru.dum.sdk.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.fabricmc.loader.api.FabricLoader;
import ru.dum.sdk.api.ServerSession;
import ru.dum.sdk.fabric.module.ModuleManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * -PhotSwap=<the test server's plugins/DUM-SDK/modules>: while the player is in game, stages a new build of sdktest
 * (the same module with one more file, signed with the dev key), publishes it with /dumsdk reload sdktest and checks
 * that the client swapped it without reconnecting. The test player must be an operator on the test server.
 */
final class HotSwapGameTest {

    private static final int MINUTE = 20 * 60;

    static void run(ClientGameTestContext context, String staging) {
        Object connection = context.computeOnClient(client -> client.getConnection());
        // Weakly: a strong local reference would keep the swapped module's classes in memory for the whole test
        var info = new java.lang.ref.WeakReference<>(context.computeOnClient(client -> loader("info")));
        var old = new java.lang.ref.WeakReference<>(context.computeOnClient(client -> loader("sdktest")));
        check(old.get() != null && info.get() != null, "info and sdktest run before the publication");
        restage(Path.of(staging, "sdktest.jar"), bytes -> bytes);
        context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload sdktest"));
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen || running(loader("sdktest"), old), MINUTE);
        // The client answers UPDATED after the swap; give the server a moment to grant the new module
        context.waitTicks(40);
        context.runOnClient(client -> {
            check(!(client.gui.screen() instanceof DisconnectedScreen) && client.getConnection() == connection && ServerSession.active(),
                    "the publication swapped sdktest without disconnecting the player");
            check(loader("sdktest") != old.get(), "sdktest runs from a new class loader");
            check(loader("info") == info.get(), "unchanged info keeps running from its class loader");
            check(DumSdkClient.modules().failures().isEmpty(), "no module failed during the swap, got " + DumSdkClient.modules().failures());
        });
        context.takeScreenshot("dum-sdk-hot-swap");
    }

    /**
     * -PhotSwapScenarios=swap|rollback|cold: the update paths around a swap, one run each, since each ends with the
     * server in a state the next join cannot pass. swap: the ui module swapped under its open screen with a resource
     * reload, a second publication during an update, and an update that times out (the server keeps the short timeout
     * until its next publication). rollback: an update the client refuses. cold: a server-scoped Fabric mod added in
     * game, which needs a restart (-Ddumsdk.hotSwapCold=<its jar>). Afterwards restore the server's staging and
     * config.yml and publish again from its console.
     */
    static void scenarios(ClientGameTestContext context, String staging) {
        join(context);
        switch (System.getProperty("dumsdk.hotSwapScenarios")) {
            case "cold" -> cold(context, staging);
            case "rollback" -> {
                // An update the client refuses: an older version of a module it already ran
                restage(Path.of(staging, "sdktest.jar"), bytes -> bytes.replace("\"version\": \"0.4.0\"", "\"version\": \"0.0.1\""));
                context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload sdktest"));
                disconnected(context, "более старую версию", "a refused update disconnects with its installation error");
            }
            default -> swap(context, staging);
        }
    }

    private static void swap(ClientGameTestContext context, String staging) {
        Path ui = Path.of(staging, "ui.jar");

        // The open screen of a swapped module closes, its resources reload, and the new module opens it again
        context.runOnClient(client -> client.player.connection.sendCommand("profile"));
        context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getClassLoader() == loader("ui"), MINUTE);
        Object connection = context.computeOnClient(client -> client.getConnection());
        var old = new java.lang.ref.WeakReference<>(context.computeOnClient(client -> loader("ui")));
        context.takeScreenshot("dum-sdk-hot-swap-ui-before");
        restage(ui, bytes -> bytes);
        context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload ui"));
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen || running(loader("ui"), old), MINUTE);
        context.waitFor(client -> client.gui.overlay() == null, MINUTE);
        context.waitTicks(20);
        context.runOnClient(client -> {
            check(client.getConnection() == connection && ServerSession.active(), "the ui swap kept the player in game");
            check(client.gui.screen() == null || client.gui.screen().getClass().getClassLoader() != old.get(), "the screen of the swapped ui module closed");
            check(DumSdkClient.modules().failures().isEmpty(), "no module failed during the ui swap");
            client.player.connection.sendCommand("profile");
        });
        context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getClassLoader() == loader("ui"), MINUTE);
        context.waitTicks(20);
        context.runOnClient(client -> check(loader("ui") != old.get(), "the new ui module opens the profile after the swap"));
        context.takeScreenshot("dum-sdk-hot-swap-ui-after");
        context.setScreen(() -> null);

        // A second publication while the client is still swapping: one update at a time, the player rejoins
        restage(ui, bytes -> bytes);
        context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload ui"));
        context.waitTicks(10);
        restage(ui, bytes -> bytes);
        context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload ui"));
        disconnected(context, "обновлены", "a second publication during an update asks the player to rejoin");
        join(context);

        // An update that does not finish in install-timeout-seconds
        Path config = Path.of(staging).resolveSibling("config.yml");
        String original = read(config);
        // Wait for the class loader checks of the earlier swaps (dev clients log whether each one was collected)
        context.waitTicks(20 * 65);
        // Older configs have no such line: the default applies until one is added under handshake
        write(config, original.contains("install-timeout-seconds:")
                ? original.replaceFirst("install-timeout-seconds: *\\d+", "install-timeout-seconds: 1")
                : original.replace("handshake:\n", "handshake:\n  install-timeout-seconds: 1\n"));
        try {
            restage(ui, bytes -> bytes);
            context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload ui"));
            disconnected(context, "слишком долго", "an update past install-timeout-seconds disconnects with the timeout message");
        } finally {
            write(config, original);
        }
    }

    private static void cold(ClientGameTestContext context, String staging) {
        Path jar = Path.of(System.getProperty("dumsdk.hotSwapCold"));
        try {
            Files.copy(jar, Path.of(staging, "publishcold.jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(jar.resolveSibling(jar.getFileName() + ".sig"), Path.of(staging, "publishcold.jar.sig"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        context.runOnClient(client -> client.player.connection.sendCommand("dumsdk reload publishcold"));
        disconnected(context, "Перезапусти игру", "a Fabric mod added in game asks for a game restart");
        Path plan = FabricLoader.getInstance().getGameDir().resolve("dum-sdk/cold-plan.txt");
        String steps = read(plan);
        check(steps.contains("dum-cold-publishcold.jar") && steps.contains("copy\t"), "the update staged the Fabric mod for the helper");
        // Keep the test client's mods/ as it was: the helper applies only a plan that is still there on exit
        try {
            Files.delete(plan);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void disconnected(ClientGameTestContext context, String text, String expectation) {
        context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, MINUTE);
        context.runOnClient(client -> {
            String message = client.gui.screen().getNarrationMessage().getString();
            check(!ServerSession.active() && message.contains(text), expectation + ", got: " + message);
        });
        context.waitFor(client -> client.gui.overlay() == null, MINUTE);
        context.setScreen(TitleScreen::new);
    }

    private static void join(ClientGameTestContext context) {
        String address = System.getProperty("dumsdk.testServer", "localhost");
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("DUM-SDK hot swap", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.level != null && client.player != null || client.gui.screen() instanceof DisconnectedScreen, MINUTE);
        context.waitTicks(40);
        context.runOnClient(client -> check(client.level != null && ServerSession.active(), "joined the test server"));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean running(ClassLoader now, java.lang.ref.WeakReference<ClassLoader> old) {
        return now != null && now != old.get();
    }

    /** The class loader of a running module, through the manager's private list: tests only. */
    private static ClassLoader loader(String id) {
        try {
            var field = ModuleManager.class.getDeclaredField("running");
            field.setAccessible(true);
            for (Object module : (List<?>) field.get(DumSdkClient.modules())) {
                var manifest = module.getClass().getDeclaredField("manifest");
                manifest.setAccessible(true);
                Object value = manifest.get(module);
                var idMethod = value.getClass().getDeclaredMethod("id");
                idMethod.setAccessible(true);
                if (!id.equals(idMethod.invoke(value))) continue;
                var loader = module.getClass().getDeclaredField("loader");
                loader.setAccessible(true);
                return (ClassLoader) loader.get(module);
            }
            return null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * The same jar with one more entry, so its hash changes, signed again with the dev key.
     *
     * @param manifest changes dum-module.json
     */
    private static void restage(Path jar, UnaryOperator<String> manifest) {
        try {
            Path temporary = jar.resolveSibling(jar.getFileName() + ".tmp");
            try (var in = new ZipInputStream(Files.newInputStream(jar)); var out = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                    if (entry.getName().equals("dum-sdk-hot-swap.txt")) continue;
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    if (entry.getName().equals("dum-module.json")) {
                        out.write(manifest.apply(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
                out.putNextEntry(new ZipEntry("dum-sdk-hot-swap.txt"));
                out.write(String.valueOf(System.nanoTime()).getBytes());
                out.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(temporary);
            Path keyFile = Path.of(System.getProperty("dumsdk.hotSwapKey", System.getProperty("user.home") + "/.dum/dev-signing.key"));
            var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).trim())));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(bytes);
            Files.write(jar.resolveSibling(jar.getFileName() + ".sig"), signature.sign());
            Files.move(temporary, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            check(true, "staged a new build of " + jar.getFileName());
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new AssertionError("Cannot stage the new sdktest", e);
        }
    }

    private static void check(boolean condition, String expectation) {
        if (!condition) throw new AssertionError("Expected: " + expectation);
        DumSdkClient.LOG.info("Hot swap test: {}", expectation);
    }
}
