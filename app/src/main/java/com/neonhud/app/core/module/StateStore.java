package com.neonhud.app.core.module;

/** Persists the module state across app restarts (SharedPreferences in the app). */
public interface StateStore {
    ModuleState load();
    void save(ModuleState state);
}
