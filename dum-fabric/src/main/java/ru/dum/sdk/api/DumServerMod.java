package ru.dum.sdk.api;

/**
 * Cooperative Fabric mod entrypoint under "dum-sdk:server" in fabric.mod.json (exactly one per mod).
 * The SDK calls it only for the verified catalog of this connection. Fabric keeps the classes and native
 * registrations loaded across servers; every mixin must check ServerSession.active(modId) before changing behavior.
 * Use the supplied context for subscriptions and cleanup. Never register gameplay effects in startup code.
 * onEnable prepares registrations during configuration; callbacks become active only after play join.
 * onDisable runs on leave/failure. A fresh context is supplied on every enable; Fabric may reuse the instance.
 */
public interface DumServerMod extends DumModule {
}
