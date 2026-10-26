package ru.dum.sdk.paper;

import org.bukkit.plugin.java.JavaPlugin;
import ru.dum.core.api.module.ModuleHost;
import ru.dum.core.api.module.ModuleManager;
import ru.dum.core.config.Messages;

import java.util.List;

/** DUM-SDK server side: talks to the DUM-SDK client mod. Every feature is a module with its own config section. */
public final class DumSdk extends JavaPlugin implements ModuleHost {

    private Messages messages;
    private ModuleManager<DumSdk> modules;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();

        modules = new ModuleManager<>(this, List.of(new HandshakeModule(this)));
        modules.enableAll();
    }

    @Override
    public void onDisable() {
        if (modules != null) modules.disableAll();
    }

    @Override
    public Messages messages() {
        return messages;
    }
}
