package com.neonhud.app.core.web;

import java.util.regex.Pattern;

/**
 * Web text is DATA, never instructions. Everything a page says goes through here before the model sees it:
 * markup and URLs removed, hidden characters dropped, "ignore your instructions"-style sentences blanked, length capped.
 */
public final class ResultCleaner {
    private ResultCleaner() { }

    private static final Pattern TAGS = Pattern.compile("<[^>]{0,200}>");
    private static final Pattern MD_IMG = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]{0,120})\\]\\((https?://[^)]*)\\)");
    private static final Pattern URLS = Pattern.compile("(https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]");
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore|disregard|forget|override|bypass)\\s+(all\\s+|any\\s+|the\\s+|your\\s+|my\\s+|these\\s+|those\\s+)*(previous|prior|above|earlier|system|safety|instructions?|rules?|prompts?|guidelines?)[^.\\n]*"
          + "|(?i)\\b(you\\s+are\\s+now|from\\s+now\\s+on\\s+you|act\\s+as\\s+(an?\\s+)?\\w+|pretend\\s+(to\\s+be|you)|new\\s+instructions?|system\\s+prompt|developer\\s+mode|jailbreak)\\b[^.\\n]*"
          + "|(?i)</?\\s*(system|assistant|user|instruction)s?\\s*>|\\[/?INST\\]|<\\|[a-z_]+\\|>"
          + "|(?i)\\b(reveal|print|show|repeat)\\s+(your|the)\\s+(system\\s+)?(prompt|instructions)\\b[^.\\n]*"
          + "|(?i)\\b(send|post|upload|email)\\s+(the\\s+|this\\s+|all\\s+)?(conversation|chat|history|memory|memories|messages?|api\\s*key|keys?)\\s+to\\b[^.\\n]*");

    /** Result of cleaning one text. */
    public static final class Cleaned {
        public final String text;
        public final int injectionHits;
        Cleaned(String text, int hits) { this.text = text; this.injectionHits = hits; }
    }

    public static Cleaned clean(String raw, int maxChars) {
        if (raw == null) return new Cleaned("", 0);
        String s = raw;
        s = HIDDEN.matcher(s).replaceAll("");
        s = MD_IMG.matcher(s).replaceAll(" ");
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = TAGS.matcher(s).replaceAll(" ");
        s = URLS.matcher(s).replaceAll(" ");
        int hits = 0;
        java.util.regex.Matcher m = INJECTION.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) { hits++; m.appendReplacement(sb, "[removed]"); }
        m.appendTail(sb);
        s = sb.toString().replaceAll("[ \\t\\u00A0]+", " ").replaceAll("\\s*\\n\\s*", " ").trim();
        if (s.length() > maxChars) {
            int cut = s.lastIndexOf(' ', maxChars);
            s = s.substring(0, cut > maxChars / 2 ? cut : maxChars).trim() + "...";
        }
        return new Cleaned(s, hits);
    }

    /** A title for a chip: one line, no markup, short. */
    public static String title(String raw, String fallbackDomain) {
        String t = clean(raw, 90).text;
        if (t.isEmpty()) return fallbackDomain;
        return t;
    }
}
