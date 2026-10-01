package com.neonhud.app.core.search;

import java.util.ArrayList;
import java.util.List;
import com.neonhud.app.core.web.KeyPool;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Add / Delete for Tavily API keys.
 *
 * Add never trusts the text blindly: the key must look like a Tavily key, must not already exist, and is then
 * TESTED against the real Tavily API on a worker thread. Only a key Tavily accepted is saved. One test at a time
 * (real guard here, not just a greyed-out button). Delete removes the key from the list and from storage.
 */
public final class TavilyKeyManager implements com.neonhud.app.core.web.KeySource {

    public interface Listener { void onKeysChanged(TavilySnapshot snapshot); }

    public static final class Result {
        public final boolean accepted;
        public final String message;
        Result(boolean accepted, String message) { this.accepted = accepted; this.message = message; }
    }

    public static final String PREFIX = "tvly-";
    private static final int MIN_LEN = 12, MAX_LEN = 200;

    private final TavilyClient client;
    private final TavilyKeyStore store;
    private final Object lock = new Object();
    private final List<String> keys = new ArrayList<String>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private boolean adding;
    private String message = "";
    private TavilySnapshot.Tone tone = TavilySnapshot.Tone.NONE;

    public TavilyKeyManager(TavilyClient client, TavilyKeyStore store) {
        this.client = client;
        this.store = store;
        List<String> saved = null;
        try { saved = store.load(); } catch (RuntimeException ignored) { }
        if (saved != null) {
            for (String k : saved) if (k != null && !k.isEmpty() && !keys.contains(k)) keys.add(k);
        }
    }

    public void addListener(Listener l) { if (l != null) listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** The saved keys in order (a copy) - the web search service tries them one by one. */
    @Override public List<String> keys() {
        synchronized (lock) { return new ArrayList<String>(keys); }
    }

    public TavilySnapshot snapshot() {
        synchronized (lock) { return build(null, 0L); }
    }

    /** Phase 4: include the current retry-pool health without exposing the pool itself to the UI. */
    public TavilySnapshot snapshot(KeyPool pool, long now) {
        synchronized (lock) { return build(pool, now); }
    }

    // ------------------------------------------------------------------ helpers

    /** Removes every space / line break (pasted keys often carry them). */
    public static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", "");
    }

    /** Null when the text looks like a Tavily key, otherwise why not. */
    public static String formatProblem(String key) {
        if (key.isEmpty()) return "Enter a Tavily API key first.";
        if (!key.startsWith(PREFIX)) return "A Tavily key starts with \"tvly-\".";
        if (key.length() < MIN_LEN) return "This key is too short.";
        if (key.length() > MAX_LEN) return "This key is too long.";
        if (!key.matches("[A-Za-z0-9_\\-]+")) return "The key has characters that are not allowed.";
        return null;
    }

    /** "tvly-dev-abcdefgh1234" -> "tvly-dev-\u2022\u2022\u2022\u20221234": the real key is never displayed. */
    public static String mask(String key) {
        if (key == null) return "";
        int lastDash = key.lastIndexOf('-');
        String head = lastDash >= 0 && lastDash < key.length() - 5 ? key.substring(0, lastDash + 1) : PREFIX;
        String tail = key.length() > 4 ? key.substring(key.length() - 4) : "";
        return head + "\u2022\u2022\u2022\u2022" + tail;
    }

    private TavilySnapshot build(KeyPool pool, long now) {
        List<TavilySnapshot.Entry> list = new ArrayList<TavilySnapshot.Entry>();
        for (String k : keys) {
            String health = pool == null ? "healthy" : pool.health(k, now);
            list.add(new TavilySnapshot.Entry(k, mask(k), health));
        }
        return new TavilySnapshot(list, adding, message, tone);
    }

    private void notifyChanged() {
        TavilySnapshot s = snapshot();
        for (Listener l : listeners) {
            try { l.onKeysChanged(s); } catch (RuntimeException ignored) { }
        }
    }

    private void persist() {
        try { store.save(new ArrayList<String>(keys)); } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ Add

    /** Checks the text, then tests the key with Tavily in the background. Returns at once. */
    public Result requestAdd(String raw) {
        final String key = normalize(raw);
        synchronized (lock) {
            if (adding) return rejectLocked("Wait - another key is being tested.");
            String bad = formatProblem(key);
            if (bad != null) return rejectLocked(bad);
            if (keys.contains(key)) return rejectLocked("This key is already added.");
            adding = true;
            message = "Testing the key with Tavily\u2026";
            tone = TavilySnapshot.Tone.NONE;
        }
        notifyChanged();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { testAndStore(key); }
        }, "tavily-add");
        t.setDaemon(true);
        t.start();
        return new Result(true, "");
    }

    /** Records a refusal (caller holds the lock) and tells the listeners; they only read the immutable snapshot. */
    private Result rejectLocked(String why) {
        message = why;
        tone = TavilySnapshot.Tone.ERROR;
        final TavilySnapshot s = build(null, 0L);
        for (Listener l : listeners) {
            try { l.onKeysChanged(s); } catch (RuntimeException ignored) { }
        }
        return new Result(false, why);
    }

    private void testAndStore(String key) {
        TavilyClient.Outcome o;
        try {
            o = client.check(key);
        } catch (RuntimeException e) {
            o = new TavilyClient.Outcome(TavilyClient.Kind.ERROR, String.valueOf(e.getMessage()));
        }
        synchronized (lock) {
            adding = false;
            switch (o.kind) {
                case VALID:
                    addKeyLocked(key);
                    message = "Key tested OK and added.";
                    tone = TavilySnapshot.Tone.OK;
                    break;
                case LIMIT:
                    addKeyLocked(key);
                    message = "Key is valid and added, but Tavily says its credit limit is used up.";
                    tone = TavilySnapshot.Tone.WARN;
                    break;
                case INVALID:
                    message = "Tavily rejected this key - it was not added.";
                    tone = TavilySnapshot.Tone.ERROR;
                    break;
                case NETWORK:
                    message = "No internet - the key could not be tested, so it was not added.";
                    tone = TavilySnapshot.Tone.ERROR;
                    break;
                default:
                    message = "Test failed" + (o.detail.isEmpty() ? "" : " (" + o.detail + ")") + " - the key was not added.";
                    tone = TavilySnapshot.Tone.ERROR;
                    break;
            }
        }
        notifyChanged();
    }

    private void addKeyLocked(String key) {
        if (!keys.contains(key)) keys.add(key);
        persist();
    }

    // ------------------------------------------------------------------ Delete

    public Result requestDelete(String key) {
        boolean removed;
        synchronized (lock) {
            removed = keys.remove(key);
            if (removed) {
                persist();
                message = "Key deleted.";
                tone = TavilySnapshot.Tone.OK;
            }
        }
        if (removed) notifyChanged();
        return new Result(removed, removed ? "" : "Key not found.");
    }
}
