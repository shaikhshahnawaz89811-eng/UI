package com.neonhud.app.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.StateStore;

/** Remembers the Gemma module state across app restarts. */
public final class PrefsStateStore implements StateStore {
    private static final String KEY = "state";
    private final SharedPreferences prefs;

    public PrefsStateStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("gemma_module", Context.MODE_PRIVATE);
    }

    @Override public ModuleState load() {
        String s = prefs.getString(KEY, null);
        if (s == null) return null;
        try { return ModuleState.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    @Override public void save(ModuleState state) {
        prefs.edit().putString(KEY, state.name()).commit();   // commit: must survive an immediate process death
    }
}
