package ru.dum.sdk.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
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
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dum.sdk.fabric.cold.ColdMods;
import ru.dum.sdk.fabric.module.ModuleInstaller;
import ru.dum.sdk.fabric.module.ModuleManager;
import ru.dum.sdk.fabric.update.SdkUpdater;
import ru.dum.sdk.network.Message;
import ru.dum.sdk.network.Protocol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Client entry point: the handshake with a DUM-SDK server, its modules and the connection status in the pause menu. */
public final class DumSdkClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("DUM-SDK");

    private static final String VERSION = FabricLoader.getInstance().getModContainer("dum-sdk").orElseThrow()
            .getMetadata().getVersion().getFriendlyString();

    /** Version of the server that accepted our HELLO; null when not connected to a DUM-SDK server. */
    private static volatile @Nullable String serverVersion;

    private static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("dum-sdk");
    private static final ModuleManager MODULES = new ModuleManager(ROOT);
    private static final SdkUpdater UPDATER = new SdkUpdater(ROOT, VERSION);
    private static final ModuleInstaller INSTALLER = new ModuleInstaller(ROOT,
            message -> ClientConfigurationNetworking.send(new SdkPayload(Protocol.encode(message))),
            jars -> CompletableFuture.supplyAsync(() -> MODULES.start(jars), Minecraft.getInstance())
                    .thenCompose(started -> started));

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
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MODULES.stop(false));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> UPDATER.checkInBackground(version -> client.execute(() ->
                SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("DUM-SDK " + version + " загружен"), Component.literal("Установится после перезапуска игры")))));

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> drawStatus(s, graphics));
            }
            // Whatever screen the game shows while joining
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> drawDownload(s, graphics));
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("sdk").executes(context -> {
                    report().forEach(context.getSource()::sendFeedback);
                    return 1;
                })));
        LOG.info("DUM-SDK {} (protocol {})", VERSION, Protocol.VERSION);
    }

    /** For the client game test. */
    static ModuleManager modules() {
        return MODULES;
    }

    /** For the client game test: the version of the server that accepted us, or null. */
    static @Nullable String serverVersion() {
        return serverVersion;
    }

    /** /sdk: what runs, how much time it takes and what went wrong. */
    private static List<Component> report() {
        List<Component> lines = new ArrayList<>();
        String server = serverVersion;
        lines.add(Component.literal("DUM-SDK " + VERSION).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(server != null ? " · сервер " + server : " · не подключено").withStyle(ChatFormatting.GRAY)));
        if (MODULES.safeMode()) {
            lines.add(Component.literal("Безопасный режим: игра закрылась с ошибкой, модули выключены до перезапуска").withStyle(ChatFormatting.YELLOW));
        }
        List<ModuleManager.Status> modules = MODULES.status();
        lines.add(Component.literal(modules.isEmpty() ? "Модули: нет" : "Модули:").withStyle(ChatFormatting.GRAY));
        for (ModuleManager.Status module : modules) {
            lines.add(Component.literal("  " + module.id() + " " + module.version()).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(String.format(" · %.2f мс/с", module.millisPerSecond())).withStyle(ChatFormatting.GRAY)));
        }
        List<String> fabricMods = ColdMods.running();
        if (!fabricMods.isEmpty()) {
            lines.add(Component.literal("Моды с сервера: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.join(", ", fabricMods)).withStyle(ChatFormatting.WHITE)));
        }
        String update = UPDATER.pending();
        if (update != null) {
            lines.add(Component.literal("DUM-SDK " + update + " установится после перезапуска").withStyle(ChatFormatting.YELLOW));
        }
        List<String> pending = INSTALLER.pendingRestart();
        if (!pending.isEmpty()) {
            lines.add(Component.literal("После перезапуска изменятся: " + String.join(", ", pending)).withStyle(ChatFormatting.YELLOW));
        }
        for (ModuleManager.Failure failure : MODULES.failures()) {
            lines.add(Component.literal("  " + failure.at() + " " + failure.module() + " выключен (" + failure.where() + "): ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(failure.error()).withStyle(ChatFormatting.GRAY)));
        }
        return lines;
    }

    /**
     * Leaving a server, however it happens: the Fabric disconnect events do not fire when the player leaves
     * by himself, so {@code MinecraftMixin} also calls this. Stopping twice does nothing.
     */
    public static void disconnected(Minecraft client) {
        serverVersion = null;
        // Right away when possible: the level still exists, so module entities can be removed from it
        if (client.isSameThread()) {
            MODULES.stop(true);
        } else {
            client.execute(() -> MODULES.stop(true));
        }
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
            }
            // Configuration phase, network thread
            case Message.Catalog catalog -> INSTALLER.catalog(catalog.modules());
            case Message.Chunk chunk -> INSTALLER.chunk(chunk);
            // Play-phase payloads already arrive on the render thread
            case Message.Module module -> MODULES.deliver(module.module(), module.data());
            case Message.Hello hello -> LOG.warn("Ignoring unexpected {} from the server", hello.getClass().getSimpleName());
            case Message.Request request -> LOG.warn("Ignoring unexpected {} from the server", request.getClass().getSimpleName());
            case Message.Ready ready -> LOG.warn("Ignoring unexpected {} from the server", ready.getClass().getSimpleName());
            case Message.Restart restart -> LOG.warn("Ignoring unexpected {} from the server", restart.getClass().getSimpleName());
        }
    }

    /** A bar under the middle of the screen while modules download. */
    private static void drawDownload(Screen screen, GuiGraphicsExtractor graphics) {
        float progress = INSTALLER.progress();
        if (progress < 0) return;
        int width = 182;
        int x = (screen.width - width) / 2;
        int y = screen.height / 2 + 40;
        Component text = Component.literal("Загрузка модулей DUM-SDK: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(INSTALLER.progressText()).withStyle(ChatFormatting.WHITE));
        graphics.centeredText(Minecraft.getInstance().font, text, screen.width / 2, y - 12, 0xFFFFFFFF);
        graphics.fill(x - 1, y - 1, x + width + 1, y + 6, 0xFF000000);
        graphics.fill(x, y, x + width, y + 5, 0xFF3A3A3A);
        graphics.fill(x, y, x + Math.round(width * progress), y + 5, 0xFFFFB347);
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
