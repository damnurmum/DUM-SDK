package ru.dum.sdk.fabric;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.fabric.update.UpdateStatus;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * -PversionTest=<the test server's DUM-SDK version>: the test server runs a plugin of another version than this
 * client. Joining shows {@link VersionScreen} with both versions; Cancel downloads nothing; Update fetches exactly the
 * server's version from a local stand-in for GitHub Releases (-Ddumsdk.updateTagsUrl, dev-signed jar) and stages it.
 * The test then removes the staged update, so this client's mods/ stays as it was.
 */
final class VersionGameTest {

    private static final int MINUTE = 20 * 60;
    static final int PORT = 47199;

    static void run(ClientGameTestContext context) throws Exception {
        String server = System.getProperty("dumsdk.versionTest");
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer github = github(server, requests);
        try {
            join(context);
            context.runOnClient(client -> {
                check(client.gui.screen() instanceof VersionScreen, "another server version shows the version window, got " + client.gui.screen());
                String text = String.join("\n", lines((VersionScreen) client.gui.screen()));
                check(text.contains("У тебя: " + DumSdkClient.version()) && text.contains("На сервере: " + server), "the window shows both versions: " + text);
            });
            context.takeScreenshot("dum-sdk-version-ask");
            context.runOnClient(client -> client.gui.screen().onClose());
            context.waitTicks(10);
            context.runOnClient(client -> {
                check(!(client.gui.screen() instanceof VersionScreen), "Cancel closes the window");
                check(requests.isEmpty() && DumSdkClient.updater().status().stage() == UpdateStatus.Stage.IDLE, "Cancel downloads nothing");
            });

            join(context);
            context.runOnClient(client -> {
                check(client.gui.screen() instanceof VersionScreen, "joining again asks again");
                invoke(client.gui.screen(), "update");
            });
            context.waitFor(client -> DumSdkClient.updater().status().stage() == UpdateStatus.Stage.READY
                    || DumSdkClient.updater().status().stage() == UpdateStatus.Stage.FAILED, MINUTE);
            context.waitTicks(5);
            context.runOnClient(client -> {
                UpdateStatus status = DumSdkClient.updater().status();
                check(status.stage() == UpdateStatus.Stage.READY && server.equals(DumSdkClient.updater().pending()),
                        "Update installs exactly the server's version, got " + status + " " + status.message());
                check(requests.equals(List.of("/tags/v" + server, "/jar", "/sig")), "it asked GitHub for that release only: " + requests);
                check(String.join("\n", lines((VersionScreen) client.gui.screen())).contains("закроешь игру"), "the window says the mod changes when the game closes");
            });
            context.takeScreenshot("dum-sdk-version-ready");
        } finally {
            github.stop(0);
            // The helper applies only a plan that is still there when the game exits
            Path root = FabricLoader.getInstance().getGameDir().resolve("dum-sdk");
            Files.deleteIfExists(root.resolve("sdk-update-plan.txt"));
            Files.deleteIfExists(root.resolve("update").resolve("DUM-SDK-Fabric-" + server + ".jar"));
        }
        context.setScreen(TitleScreen::new);
    }

    /** A release of the server's version with a dev-signed jar that says it is that version of dum-sdk. */
    private static HttpServer github(String version, List<String> requests) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"dum-sdk\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] jar = bytes.toByteArray();
        Path keyFile = Path.of(System.getProperty("user.home"), ".dum", "dev-signing.key");
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).trim()))));
        signature.update(jar);
        byte[] sig = signature.sign();
        String base = "http://127.0.0.1:" + PORT;
        byte[] release = ("{\"tag_name\":\"v" + version + "\",\"assets\":["
                + "{\"name\":\"DUM-SDK-Fabric-" + version + ".jar\",\"browser_download_url\":\"" + base + "/jar\"},"
                + "{\"name\":\"DUM-SDK-Fabric-" + version + ".jar.sig\",\"browser_download_url\":\"" + base + "/sig\"}]}").getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            byte[] body = path.equals("/tags/v" + version) ? release : path.equals("/jar") ? jar : path.equals("/sig") ? sig : null;
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) try (var out = exchange.getResponseBody()) { out.write(body); }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void join(ClientGameTestContext context) {
        String address = System.getProperty("dumsdk.testServer", "localhost");
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("DUM-SDK version", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.gui.screen() instanceof VersionScreen || client.level != null, MINUTE);
        context.waitTicks(10);
    }

    private static List<String> lines(VersionScreen screen) {
        try {
            var method = VersionScreen.class.getDeclaredMethod("lines");
            method.setAccessible(true);
            return ((List<?>) method.invoke(screen)).stream().map(line -> ((net.minecraft.network.chat.Component) line).getString()).toList();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(Object screen, String name) {
        try {
            var method = screen.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(screen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String expectation) {
        if (!condition) throw new AssertionError("Expected: " + expectation);
        DumSdkClient.LOG.info("Version test: {}", expectation);
    }
}
