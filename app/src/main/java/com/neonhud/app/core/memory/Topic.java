package com.neonhud.app.core.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/** A conversation topic plus a small keyword profile used to recognise it again later. */
public final class Topic {
    public long id;
    public String name;
    public long createdAt;
    public long lastUsedAt;
    /** stem -> how often it appeared in user messages of this topic. */
    public final Map<String, Integer> terms = new LinkedHashMap<String, Integer>();
    /** domain label -> count (see Lexicon). */
    public final Map<String, Integer> domains = new LinkedHashMap<String, Integer>();

    public Topic(long id, String name, long createdAt, long lastUsedAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    // ---- compact text form used by the database ("stem:count stem:count")
    public static String encode(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    public static void decode(String s, Map<String, Integer> into) {
        into.clear();
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(" ")) {
            int i = part.lastIndexOf(':');
            if (i <= 0) continue;
            try { into.put(part.substring(0, i), Integer.parseInt(part.substring(i + 1))); }
            catch (NumberFormatException ignored) { }
        }
    }

    public void bump(String stem, int by) {
        Integer c = terms.get(stem);
        terms.put(stem, (c == null ? 0 : c) + by);
    }

    public void bumpDomain(String domain) {
        Integer c = domains.get(domain);
        domains.put(domain, (c == null ? 0 : c) + 1);
    }

    /** Keeps only the {@code max} most frequent terms so the profile stays small. */
    public void trimTerms(int max) {
        if (terms.size() <= max) return;
        java.util.List<Map.Entry<String, Integer>> l =
                new java.util.ArrayList<Map.Entry<String, Integer>>(terms.entrySet());
        java.util.Collections.sort(l, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        terms.clear();
        for (int i = 0; i < max; i++) terms.put(l.get(i).getKey(), l.get(i).getValue());
    }
}
