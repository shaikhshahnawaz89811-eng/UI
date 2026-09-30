package com.neonhud.app.core.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable picture of the Tavily key list - the Settings card is drawn from this and nothing else. */
public final class TavilySnapshot {

    public enum Tone { NONE, OK, WARN, ERROR }

    public static final class Entry {
        /** The real key: only used to delete it, never shown. */
        public final String key;
        /** What the screen shows, e.g. "tvly-dev-••••a1b2". */
        public final String masked;
        /** Phase 4: current web-pool health, never the raw API error. */
        public final String health;
        public final long restMs;

        Entry(String key, String masked) { this(key, masked, "ready", 0L); }
        Entry(String key, String masked, String health, long restMs) {
            this.key = key;
            this.masked = masked;
            this.health = health == null ? "ready" : health;
            this.restMs = Math.max(0L, restMs);
        }
    }

    public final List<Entry> entries;
    /** A key is being tested right now (Add is locked meanwhile). */
    public final boolean adding;
    public final String message;
    public final Tone tone;

    TavilySnapshot(List<Entry> entries, boolean adding, String message, Tone tone) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
        this.adding = adding;
        this.message = message == null ? "" : message;
        this.tone = tone;
    }

    public boolean canAdd() { return !adding; }

    /** Changes only when the visible rows change - lets the UI skip rebuilding them. */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(e.masked).append('|').append(e.health).append('|').append(e.restMs / 60000).append('|');
        return sb.toString();
    }
}
