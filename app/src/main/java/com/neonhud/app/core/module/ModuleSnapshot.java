package com.neonhud.app.core.module;

/** Immutable view of the module for the UI. Buttons are derived from this, never the other way round. */
public final class ModuleSnapshot {
    public final ModuleState state;
    public final ModuleAction inFlight;      // null when idle
    public final boolean replyActive;
    public final int importPercent;          // -1 when not importing
    public final String message;             // last status / error, may be empty

    public final boolean canImport;
    public final boolean canLoad;
    public final boolean canUnload;
    public final boolean canDelete;

    ModuleSnapshot(ModuleState state, ModuleAction inFlight, boolean replyActive,
                   int importPercent, String message,
                   boolean canImport, boolean canLoad, boolean canUnload, boolean canDelete) {
        this.state = state;
        this.inFlight = inFlight;
        this.replyActive = replyActive;
        this.importPercent = importPercent;
        this.message = message == null ? "" : message;
        this.canImport = canImport;
        this.canLoad = canLoad;
        this.canUnload = canUnload;
        this.canDelete = canDelete;
    }

    public boolean busy() { return inFlight != null || replyActive; }
}
