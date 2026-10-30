package ru.dum.sdk.fabric.module;

/** One connection's negotiation. Old downloads and callbacks cannot activate a later connection. */
public final class ConnectionSession {

    private Object owner;
    private long generation;
    private boolean welcomed;
    private boolean catalog;
    private boolean installed;
    private boolean joined;
    private volatile boolean active;

    public synchronized long begin(Object owner) {
        close();
        this.owner = owner;
        return generation;
    }

    public synchronized boolean welcome(Object owner, boolean compatible) {
        if (this.owner != owner || welcomed || !compatible) return false;
        welcomed = true;
        return true;
    }

    public synchronized boolean catalog(Object owner) {
        if (this.owner != owner || !welcomed || catalog) return false;
        catalog = true;
        return true;
    }

    public synchronized boolean downloading(Object owner) {
        return this.owner == owner && catalog && !installed;
    }

    public synchronized boolean current(long generation) {
        return owner != null && this.generation == generation;
    }

    public synchronized void installed(long generation) {
        if (current(generation) && catalog) {
            installed = true;
            active = joined;
        }
    }

    public synchronized void joined(Object owner) {
        if (this.owner == owner) {
            joined = true;
            active = welcomed && catalog && installed;
        }
    }

    public boolean active() {
        return active;
    }

    public synchronized boolean owns(Object owner) {
        return this.owner == owner;
    }

    public synchronized void close() {
        active = false;
        generation++;
        owner = null;
        welcomed = false;
        catalog = false;
        installed = false;
        joined = false;
    }
}
