package ru.dum.sdk.fabric.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionSessionTest {

    @Test
    void ordinaryServerAndUnsolicitedCatalogCannotActivateModules() {
        var session = new ConnectionSession();
        Object vanilla = new Object();
        long ticket = session.begin(vanilla);
        assertFalse(session.catalog(vanilla));
        session.installed(ticket);
        session.joined(vanilla);
        assertFalse(session.active());
        assertFalse(session.welcome(vanilla, false));
        assertFalse(session.active());
    }

    @Test
    void activationRequiresWelcomeCatalogInstalledModulesAndPlayJoin() {
        var session = new ConnectionSession();
        Object sdk = new Object();
        long ticket = session.begin(sdk);
        assertTrue(session.welcome(sdk, true));
        assertFalse(session.welcome(sdk, true));
        assertTrue(session.catalog(sdk));
        assertFalse(session.catalog(sdk));
        session.installed(ticket);
        assertFalse(session.active());
        session.joined(sdk);
        assertTrue(session.active());
        session.close();
        assertFalse(session.active());
    }

    @Test
    void previousConnectionCannotFinishInstallationOnNewOrDisconnectedSession() {
        var session = new ConnectionSession();
        Object old = new Object();
        long ticket = session.begin(old);
        assertTrue(session.welcome(old, true));
        assertTrue(session.catalog(old));
        Object next = new Object();
        session.begin(next);
        session.installed(ticket);
        session.joined(old);
        assertFalse(session.current(ticket));
        assertFalse(session.catalog(old));
        assertFalse(session.active());
        session.close();
        assertFalse(session.current(ticket));
    }
}
