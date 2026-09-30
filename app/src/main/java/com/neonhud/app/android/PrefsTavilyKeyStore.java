package com.neonhud.app.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.neonhud.app.core.search.TavilyKeyStore;

import java.util.ArrayList;
import java.util.List;

/** Saved Tavily keys, one per line, in the app's private preferences (allowBackup is off, so they stay on the phone). */
public final class PrefsTavilyKeyStore implements TavilyKeyStore {
    private static final String KEY = "keys";
    private final SharedPreferences prefs;

    public PrefsTavilyKeyStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("tavily_keys", Context.MODE_PRIVATE);
    }

    @Override public List<String> load() {
        List<String> out = new ArrayList<String>();
        String raw = prefs.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String k : raw.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    @Override public void save(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) { if (sb.length() > 0) sb.append('\n'); sb.append(k); }
        prefs.edit().putString(KEY, sb.toString()).commit();   // commit: a saved key must survive an immediate process death
    }
}
