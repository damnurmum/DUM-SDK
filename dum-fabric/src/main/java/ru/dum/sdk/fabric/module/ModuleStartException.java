package ru.dum.sdk.fabric.module;

/** Modules cannot start for a reason the player should read; the server shows the message when it refuses the join. */
final class ModuleStartException extends RuntimeException {

    ModuleStartException(String message) {
        super(message, null, false, false);
    }
}
