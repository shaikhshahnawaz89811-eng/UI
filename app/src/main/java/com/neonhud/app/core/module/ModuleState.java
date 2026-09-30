package com.neonhud.app.core.module;

/** Life-cycle state of one offline model module (each module has its own copy of this state). */
public enum ModuleState {
    NOT_IMPORTED,
    IMPORTED,   // imported / ready, never loaded yet
    LOADED,
    UNLOADED    // was loaded, then unloaded
}
