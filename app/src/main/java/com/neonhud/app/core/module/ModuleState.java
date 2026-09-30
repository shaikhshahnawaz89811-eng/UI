package com.neonhud.app.core.module;

/** Life-cycle state of the single Gemma 4 E2B module. */
public enum ModuleState {
    NOT_IMPORTED,
    IMPORTED,   // imported / ready, never loaded yet
    LOADED,
    UNLOADED    // was loaded, then unloaded
}
