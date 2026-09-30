package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which key to try next. The key that worked last goes first; a key Tavily rejected rests for a while, a key that hit its
 * limit rests shorter; when every key is resting the pool says so, so the user gets the exact reason instead of silence.
 * All time-based, so a key the user fixed (or re-added) is tried again without restarting the app.
 */
public final class KeyPool {
    public static final long LIMIT_REST_MS = 10L * 60 * 1000;
    public static final long INVALID_REST_MS = 30L * 60 * 1000;

    private final Map<String, Long> limitedUntil = new HashMap<String, Long>();
    private final Map<String, Long> invalidUntil = new HashMap<String, Long>();
    private String lastGood = "";

    public synchronized List<String> order(List<String> keys, long now) {
        List<String> ready = new ArrayList<String>();
        for (String k : keys) if (!resting(k, now)) ready.add(k);
        int at = ready.indexOf(lastGood);
        if (at > 0) { ready.remove(at); ready.add(0, lastGood); }
        return ready;
    }

    private boolean resting(String k, long now) {
        Long a = limitedUntil.get(k), b = invalidUntil.get(k);
        return (a != null && a > now) || (b != null && b > now);
    }

    public synchronized void good(String k) { lastGood = k; limitedUntil.remove(k); invalidUntil.remove(k); }
    public synchronized void limited(String k, long now) { limitedUntil.put(k, now + LIMIT_REST_MS); }
    public synchronized void invalid(String k, long now) { invalidUntil.put(k, now + INVALID_REST_MS); }

    public synchronized String health(String key, long now) {
        Long b = invalidUntil.get(key), a = limitedUntil.get(key);
        if (b != null && b > now) return "rejected";
        if (a != null && a > now) return "limit";
        return key.equals(lastGood) ? "healthy" : "ready";
    }

    public synchronized long restMs(String key, long now) {
        Long b = invalidUntil.get(key), a = limitedUntil.get(key);
        long until = b != null && b > now ? b : a != null && a > now ? a : 0L;
        return Math.max(0L, until - now);
    }

    /** How many of these keys are resting because of a limit / because Tavily rejected them. */
    public synchronized int[] resting(List<String> keys, long now) {
        int lim = 0, inv = 0;
        for (String k : keys) {
            Long a = limitedUntil.get(k), b = invalidUntil.get(k);
            if (b != null && b > now) inv++; else if (a != null && a > now) lim++;
        }
        return new int[]{lim, inv};
    }
}
