package com.neonhud.app.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.neonhud.app.core.web.WebMode;
import com.neonhud.app.core.web.WebSearchService;

/** The Settings switch for web search (Auto / Always / Off), kept in the app's private preferences. Default: Auto. */
public final class PrefsWebSettings implements WebSearchService.Settings {
    private static final String KEY = "mode";
    private final SharedPreferences prefs;

    public PrefsWebSettings(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("web_search", Context.MODE_PRIVATE);
    }

    @Override public WebMode mode() { return WebMode.parse(prefs.getString(KEY, WebMode.AUTO.name())); }

    public void set(WebMode mode) {
        prefs.edit().putString(KEY, (mode == null ? WebMode.AUTO : mode).name()).commit();   // commit: survives an immediate process death
    }
}
