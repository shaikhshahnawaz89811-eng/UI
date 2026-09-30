package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shrinks the text of a web page to what a phone model can read: cleaned (markup, urls, hidden characters, injection
 * sentences), cut into chunks, and only the start of the page plus the chunks that best match the user's question are kept,
 * in their original order. Pure Java, deterministic.
 */
public final class PageReader {
    private PageReader() { }

    /** Everything taken from one page is capped first: a few MB of text is not a page, it is a dump. */
    static final int RAW_CAP = 150000;
    static final int CHUNK = 520;
    /** Part of the budget that always goes to the top of the page (what it is about). */
    static final double INTRO_SHARE = 0.30;

    public static final class Digest {
        public final String url, domain, text;
        public final boolean cut;             // parts of the page were left out
        public final int injectionHits;
        Digest(String url, String text, boolean cut, int hits) {
            this.url = url; this.domain = UrlTools.display(url); this.text = text; this.cut = cut; this.injectionHits = hits;
        }
        public boolean empty() { return text.isEmpty(); }
    }

    public static Digest digest(String url, String raw, String question, int budget) {
        if (raw == null || raw.trim().isEmpty()) return new Digest(url, "", false, 0);
        String src = raw.length() > RAW_CAP ? raw.substring(0, RAW_CAP) : raw;
        boolean cutRaw = raw.length() > RAW_CAP;

        // 1) chunks: one per line / paragraph, long ones split at sentence ends; each cleaned on its own so nothing spans a chunk
        List<String> chunks = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        int hits = 0;
        for (String line : src.split("\\r?\\n+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            for (String part : splitLong(t)) {
                ResultCleaner.Cleaned c = ResultCleaner.clean(part, CHUNK + 80);
                hits += c.injectionHits;
                String s = c.text;
                if (s.isEmpty()) continue;
                if (s.length() < 15 && !hasDigit(s)) continue;                       // menu words, "Home", "Login"
                if (!seen.add(s.toLowerCase(java.util.Locale.ROOT))) continue;       // repeated blocks
                chunks.add(s);
            }
        }
        if (chunks.isEmpty()) return new Digest(url, "", false, hits);

        // 2) what to keep
        boolean[] keep = new boolean[chunks.size()];
        int used = 0;
        int intro = (int) (budget * INTRO_SHARE);
        int i = 0;
        for (; i < chunks.size(); i++) {
            int len = chunks.get(i).length() + 1;
            if (used + len > intro && used > 0) break;
            keep[i] = true; used += len;
        }
        Set<String> q = LinkPicker.tokens(question);
        List<int[]> ranked = new ArrayList<int[]>();           // {chunk index, score}
        if (!q.isEmpty()) {
            for (int k = i; k < chunks.size(); k++) {
                Set<String> w = LinkPicker.tokens(chunks.get(k));
                int score = 0;
                for (String t : q) if (w.contains(t)) score++;
                if (score > 0) ranked.add(new int[]{k, score});
            }
            // higher score first; for equal scores the earlier chunk
            java.util.Collections.sort(ranked, new java.util.Comparator<int[]>() {
                @Override public int compare(int[] a, int[] b) { return a[1] != b[1] ? b[1] - a[1] : a[0] - b[0]; }
            });
        }
        for (int[] r : ranked) {
            int len = chunks.get(r[0]).length() + 1;
            if (used + len > budget) continue;
            keep[r[0]] = true; used += len;
        }
        if (ranked.isEmpty()) {                                  // nothing in particular asked: simply read on from the top
            for (int k = i; k < chunks.size(); k++) {
                int len = chunks.get(k).length() + 1;
                if (used + len > budget) break;
                keep[k] = true; used += len;
            }
        }

        // 3) in page order; a gap is marked so the model knows text was skipped
        StringBuilder sb = new StringBuilder();
        boolean cut = cutRaw, gap = false;
        for (int k = 0; k < chunks.size(); k++) {
            if (!keep[k]) { gap = true; cut = true; continue; }
            if (sb.length() > 0) sb.append(gap ? " [...] " : " ");
            sb.append(chunks.get(k));
            gap = false;
        }
        if (gap) cut = true;
        return new Digest(url, sb.toString(), cut, hits);
    }

    private static List<String> splitLong(String t) {
        List<String> out = new ArrayList<String>();
        if (t.length() <= CHUNK) { out.add(t); return out; }
        StringBuilder cur = new StringBuilder();
        for (String s : t.split("(?<=[.!?\\u0964])\\s+")) {
            if (cur.length() + s.length() + 1 > CHUNK && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            while (s.length() > CHUNK) { out.add(s.substring(0, CHUNK)); s = s.substring(CHUNK); }       // a sentence without any break
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }
}
