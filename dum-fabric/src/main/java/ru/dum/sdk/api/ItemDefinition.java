package ru.dum.sdk.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/** Session-local presentation of a server-owned vanilla carrier item marked with dum-sdk:item. */
public record ItemDefinition(String id, Identifier model, Component name) {

    public ItemDefinition {
        if (id == null || !id.matches("[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid module item path");
        Objects.requireNonNull(model);
        Objects.requireNonNull(name);
    }
}
