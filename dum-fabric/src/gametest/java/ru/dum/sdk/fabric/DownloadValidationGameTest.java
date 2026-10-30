package ru.dum.sdk.fabric;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import ru.dum.sdk.api.ServerSession;
import ru.dum.sdk.network.ErrorCode;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Injects bad server bytes at the real Fabric receiver; the real installer reports through the codec to Paper. */
final class DownloadValidationGameTest {
    private static final int TIMEOUT = 20 * 60;

    @SuppressWarnings("unchecked")
    static void run(ClientGameTestContext context) {
        String address = System.getProperty("dumsdk.testServer", "localhost");
        var original = (ClientConfigurationNetworking.ConfigurationPayloadHandler<SdkPayload>)
                ClientConfigurationNetworking.unregisterGlobalReceiver(SdkPayload.TYPE);
        check(original != null, "SDK receiver exists");
        try {
            for (ErrorCode code : List.of(ErrorCode.INVALID_CATALOG, ErrorCode.INVALID_CHUNK,
                    ErrorCode.HASH_MISMATCH, ErrorCode.SIGNATURE_MISMATCH,
                    ErrorCode.UNEXPECTED_CHUNK, ErrorCode.UNEXPECTED_REQUEST)) {
                var injected = new AtomicBoolean();
                clearCache();
                ClientConfigurationNetworking.registerGlobalReceiver(SdkPayload.TYPE, (payload, packetContext) -> {
                    byte[] bytes = payload.data();
                    Message message = Protocol.decode(bytes);
                    byte[] duplicateRequest = null;
                    if (!injected.get()) {
                        if (message instanceof Message.Catalog catalog && code == ErrorCode.INVALID_CATALOG) {
                            check(!catalog.modules().isEmpty(), "fault test server supplies module jars");
                            byte[] one = Protocol.encode(new Message.Catalog(List.of(catalog.modules().getFirst())));
                            var output = new ByteArrayOutputStream();
                            output.writeBytes(one);
                            output.write(one, 5, one.length - 5);
                            bytes = output.toByteArray();
                            ByteBuffer.wrap(bytes).putInt(1, 2); // Two identical entries, bypassing encoder validation.
                            injected.set(true);
                        } else if (message instanceof Message.Catalog catalog && code == ErrorCode.SIGNATURE_MISMATCH) {
                            var entry = catalog.modules().getFirst();
                            entry.signature()[0] ^= 1;
                            bytes = Protocol.encode(catalog);
                            injected.set(true);
                        } else if (message instanceof Message.Catalog catalog && code == ErrorCode.UNEXPECTED_REQUEST) {
                            duplicateRequest = catalog.modules().getFirst().sha256();
                            injected.set(true);
                        } else if (message instanceof Message.Chunk) {
                            bytes = bytes.clone();
                            if (code == ErrorCode.INVALID_CHUNK) {
                                ByteBuffer.wrap(bytes).putInt(33, 1); // First offset must be zero.
                                injected.set(true);
                            } else if (code == ErrorCode.UNEXPECTED_CHUNK) {
                                bytes[1] ^= 1; // A hash outside the requested catalog.
                                injected.set(true);
                            } else if (code == ErrorCode.HASH_MISMATCH) {
                                bytes[bytes.length - 1] ^= 1;
                                injected.set(true);
                            }
                        }
                    }
                    original.receive(new SdkPayload(bytes), packetContext);
                    if (duplicateRequest != null) {
                        packetContext.packetListener().send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                                new SdkPayload(Protocol.encode(new Message.Request(duplicateRequest)))));
                    }
                });
                context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                        new ServerData("Download rejection test", address, ServerData.Type.OTHER), false, null));
                context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen || client.level != null, TIMEOUT);
                context.runOnClient(client -> {
                    check(injected.get(), "injected " + code);
                    check(client.level == null && client.gui.screen() instanceof DisconnectedScreen, "invalid transfer never enters play");
                    String text = client.gui.screen().getNarrationMessage().getString();
                    check(text.contains("Код: " + code.name()), "Paper decoded and displayed " + code);
                    check(text.contains("Не удалось установить модули"), "Paper selected the Russian installation error screen");
                    check(!ServerSession.active() && DumSdkClient.modules().status().isEmpty(), "failed installation activates no modules");
                });
                context.waitFor(client -> client.gui.overlay() == null, TIMEOUT);
                context.setScreen(TitleScreen::new);
                ClientConfigurationNetworking.unregisterGlobalReceiver(SdkPayload.TYPE);
            }
        } finally {
            ClientConfigurationNetworking.unregisterGlobalReceiver(SdkPayload.TYPE);
            ClientConfigurationNetworking.registerGlobalReceiver(SdkPayload.TYPE, original);
        }
    }

    private static void clearCache() {
        var cache = FabricLoader.getInstance().getGameDir().resolve("dum-sdk/cache");
        try {
            if (!Files.exists(cache)) return;
            try (var files = Files.list(cache)) {
                for (var file : files.toList()) Files.delete(file);
            }
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        DumSdkClient.LOG.info("Download validation test: {}", message);
    }
}
