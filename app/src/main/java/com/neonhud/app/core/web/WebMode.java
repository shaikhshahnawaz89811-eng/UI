package com.neonhud.app.core.web;

/** The Settings switch for web search. AUTO = the app decides per message; ALWAYS = every real question; OFF = never. */
public enum WebMode {
    AUTO, ALWAYS, OFF;

    public static WebMode parse(String s) {
        if (s == null) return AUTO;
        try { return valueOf(s.trim().toUpperCase(java.util.Locale.US)); } catch (IllegalArgumentException e) { return AUTO; }
    }
}
