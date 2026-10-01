package com.neonhud.app.core.search;

import com.neonhud.app.core.web.KeyPool;

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
        /** Current pool health: healthy, limit, or rejected. */
        public final String health;

        Entry(String key, String masked) { this(key, masked, "healthy"); }
        Entry(String key, String masked, String health) {
            this.key = key;
            this.masked = masked;
            this.health = health == null || health.isEmpty() ? "healthy" : health;
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
        for (Entry e : entries) sb.append(e.masked).append('|');
        return sb.toString();
    }
}
