package com.neonhud.app.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.neonhud.app.core.module.ModuleState;
import com.neonhud.app.core.module.StateStore;

/** Remembers one module's state (Not Imported / Imported / Loaded / Unloaded) across app restarts. */
public final class PrefsStateStore implements StateStore {
    private static final String KEY = "state";
    private final SharedPreferences prefs;

    public PrefsStateStore(Context ctx) {
        this(ctx, "gemma_module");
    }

    /** One preferences file per module, so each module remembers its own state. */
    public PrefsStateStore(Context ctx, String prefsName) {
        prefs = ctx.getApplicationContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE);
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
