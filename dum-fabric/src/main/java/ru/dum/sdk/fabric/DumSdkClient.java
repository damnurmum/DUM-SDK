package ru.dum.sdk.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.fabric.module.ModuleManager;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.util.List;

/** Client entry point: the handshake with a DUM-SDK server, its modules and the connection status in the pause menu. */
public final class DumSdkClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");

    private static final String VERSION = FabricLoader.getInstance().getModContainer("dum-sdk").orElseThrow()
            .getMetadata().getVersion().getFriendlyString();

    /** Version of the server that accepted our HELLO; null when not connected to a DUM-SDK server. */
    private static volatile @Nullable String serverVersion;

    private static final ModuleManager MODULES = new ModuleManager();

    @Override
    public void onInitializeClient() {
        MODULES.init();
        PayloadTypeRegistry.serverboundConfiguration().register(SdkPayload.TYPE, SdkPayload.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(SdkPayload.TYPE, SdkPayload.CODEC);
        ClientConfigurationNetworking.registerGlobalReceiver(SdkPayload.TYPE, (payload, context) -> receive(payload));
        PayloadTypeRegistry.serverboundPlay().register(SdkPayload.TYPE, SdkPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SdkPayload.TYPE, SdkPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SdkPayload.TYPE, (payload, context) -> receive(payload));

        ClientConfigurationConnectionEvents.START.register((listener, client) -> {
            serverVersion = null;
            // Paper does not announce its channels during configuration, so Fabric never announces ours
            // and Paper would drop every message sent to us. Announce dum:sdk ourselves, before HELLO.
            ClientConfigurationNetworking.send(new RegistrationPayload(RegistrationPayload.REGISTER, List.of(SdkPayload.TYPE.id())));
            ClientConfigurationNetworking.send(new SdkPayload(Protocol.encode(new Message.Hello(Protocol.VERSION, VERSION))));
        });
        ClientConfigurationConnectionEvents.DISCONNECT.register((listener, client) -> disconnected(client));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> disconnected(client));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MODULES.stop());

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> drawStatus(s, graphics));
            }
        });
        LOG.info("DUM-SDK {} (protocol {})", VERSION, Protocol.VERSION);
    }

    private static void disconnected(Minecraft client) {
        serverVersion = null;
        client.execute(MODULES::stop);
    }

    private static void receive(SdkPayload payload) {
        Message message;
        try {
            message = Protocol.decode(payload.data());
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring a malformed message from the server", e);
            return;
        }
        switch (message) {
            case Message.Welcome welcome -> {
                serverVersion = welcome.serverVersion();
                LOG.info("Connected to DUM-SDK server {} (protocol {})", welcome.serverVersion(), welcome.protocol());
                Minecraft.getInstance().execute(MODULES::start);
            }
            // Play-phase payloads already arrive on the render thread
            case Message.Module module -> MODULES.deliver(module.module(), module.data());
            case Message.Hello hello -> LOG.warn("Ignoring unexpected {} from the server", hello);
        }
    }

    private static void drawStatus(Screen screen, GuiGraphicsExtractor graphics) {
        Component status = MODULES.safeMode()
                ? Component.literal("безопасный режим, модули выключены").withStyle(ChatFormatting.YELLOW)
                : serverVersion != null
                ? Component.literal("подключено").withStyle(ChatFormatting.GREEN)
                : Component.literal("не подключено").withStyle(ChatFormatting.GRAY);
        Component line = Component.literal("DUM-SDK " + VERSION + ": ").withStyle(ChatFormatting.GRAY).append(status);
        graphics.text(Minecraft.getInstance().font, line, 4, screen.height - 12, 0xFFFFFFFF, true);
    }
}
