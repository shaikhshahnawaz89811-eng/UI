package com.neonhud.app.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Decides what is worth keeping as a long-term memory after an exchange. Rule based, no model needed. */
final class MemoryExtractor {

    static final class Candidate {
        final String content;
        final long topicId;      // 0 = global
        final double importance;
        final String terms;
        Candidate(String content, long topicId, double importance, String terms) {
            this.content = content; this.topicId = topicId; this.importance = importance; this.terms = terms;
        }
    }

    private static final Pattern REMEMBER = Pattern.compile(
        "\\b(yaad rakh\\w*|yad rakh\\w*|yaad kar\\w*|remember( that| this)?|note (kar|this|that|down)|mat bhoolna|"
      + "dont forget|don't forget|bhulna mat)\\b");
    private static final Pattern PERSONAL = Pattern.compile(
        "\\b(mera naam|my name is|i am called|main .{1,30} (rehta|rehti) (hun|hu|hoon)|i live in|i am from|"
      + "main .{1,20} se (hun|hu|hoon)|i work (at|in|as|for)|meri (age|umar)|my age is|mujhe .{1,30} pasand hai|"
      + "i (like|love|prefer|hate)\\b|my (favourite|favorite))");

    private MemoryExtractor() { }

    static boolean isMemoryStatement(String userText) {
        String l = userText.toLowerCase(Locale.ROOT);
        return REMEMBER.matcher(l).find() || PERSONAL.matcher(l).find();
    }

    static String clip(String s, int max) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "\u2026";
    }

    /** First ~2 sentences of a reply. */
    static String gist(String reply, int max) {
        String s = reply.replaceAll("\\s+", " ").trim();
        int cut = 0, sentences = 0;
        for (int i = 0; i < s.length() && sentences < 2; i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\u0964') { sentences++; cut = i + 1; }
        }
        String g = (sentences == 0 || cut < 20) ? s : s.substring(0, cut);
        return clip(g, max);
    }

    static String termsOf(TextTools.Parsed q, TextTools.Parsed reply, int maxReplyTerms) {
        StringBuilder sb = new StringBuilder();
        for (String t : q.contentAll) sb.append(t).append(' ');
        if (reply != null) {
            int n = 0;
            for (String t : reply.contentWords) {
                if (n++ >= maxReplyTerms) break;
                sb.append(t).append(' ');
            }
        }
        return sb.toString().trim();
    }

    static List<Candidate> extract(String userText, String reply, TextTools.Parsed q,
                                   TextTools.Parsed r, long topicId, boolean sameQuestion) {
        List<Candidate> out = new ArrayList<Candidate>();
        String lower = userText.toLowerCase(Locale.ROOT);
        boolean explicit = REMEMBER.matcher(lower).find();
        boolean personal = PERSONAL.matcher(lower).find();
        if (explicit || personal) {
            out.add(new Candidate(clip(userText, 240), 0, explicit ? 0.95 : 0.8, termsOf(q, null, 0)));
        }
        if (!sameQuestion && !q.contentWords.isEmpty() && !q.ack && !q.returnCue && !(explicit || personal)) {
            String content = "Q: " + clip(userText, 160) + " \u2192 A: " + gist(reply, 260);
            out.add(new Candidate(content, topicId, 0.35, termsOf(q, r, 12)));
        }
        return out;
    }
}
