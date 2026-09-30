package com.neonhud.app.core.web;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Safe, dependency-free URL helpers: only http(s), no tracking junk, no local addresses. */
public final class UrlTools {
    private UrlTools() { }

    private static final Set<String> TRACKING = new HashSet<String>(Arrays.asList(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid",
            "igshid", "ref", "ref_src", "ref_url", "source", "spm", "_ga", "yclid", "mkt_tok", "si", "feature", "cmpid"));

    /** Lower-case host without port, "" when the text is not an absolute http(s) URL. */
    public static String host(String url) {
        if (url == null) return "";
        String u = url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        int start;
        if (lower.startsWith("https://")) start = 8; else if (lower.startsWith("http://")) start = 7; else if (lower.startsWith("www.")) start = 0; else return "";
        int end = start;
        while (end < u.length() && "/?#".indexOf(u.charAt(end)) < 0) end++;
        String h = lower.substring(start, end);
        int at = h.lastIndexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        return h;
    }

    public static String path(String url) {
        if (url == null) return "";
        String u = url.trim();
        int s = u.indexOf("://");
        s = s < 0 ? 0 : s + 3;
        int slash = u.indexOf('/', s);
        if (slash < 0) return "";
        int end = u.length();
        for (int k = slash; k < u.length(); k++) if (u.charAt(k) == '?' || u.charAt(k) == '#') { end = k; break; }
        return u.substring(slash, end);
    }

    /** True for a normal public http(s) address: not file:, javascript:, data:, not localhost / private IPs / user:pass@host. */
    public static boolean isSafe(String url) {
        if (url == null) return false;
        String u = url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("https://") || lower.startsWith("http://"))) return false;
        if (u.length() > 2000) return false;
        for (int i = 0; i < u.length(); i++) { char c = u.charAt(i); if (c <= 0x20 || c == '<' || c == '>' || c == '"' || c == '\\') return false; }
        String h = host(u);
        if (h.isEmpty() || h.indexOf('.') < 0) return false;
        if (u.substring(u.indexOf("://") + 3).split("[/?#]", 2)[0].contains("@")) return false;     // user:pass@host tricks
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".localhost")) return false;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] o = h.split("\\.");
            int a = Integer.parseInt(o[0]), b = Integer.parseInt(o[1]);
            return !(a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168));
        }
        return true;
    }

    /** What a link most likely is, judged by its path only (no network): a picture, a PDF, or a normal page (or unknown). */
    public enum Media { IMAGE, PDF, PAGE }

    public static Media mediaKind(String url) {
        String p = path(url).toLowerCase(Locale.ROOT);
        if (p.endsWith(".pdf")) return Media.PDF;
        if (p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".gif") || p.endsWith(".bmp")) return Media.IMAGE;
        return Media.PAGE;
    }

    /** "www.example.com/a" (no scheme, as people paste it) becomes "https://www.example.com/a"; everything else is returned trimmed. */
    public static String normalize(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.regionMatches(true, 0, "www.", 0, 4)) return "https://" + u;
        return u;
    }

    /** Removes tracking parameters and the #fragment; keeps everything else (a page's own ?id=... stays). */
    public static String clean(String url) {
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int q = u.indexOf('?');
        if (q < 0) return stripSlash(u);
        String base = u.substring(0, q), query = u.substring(q + 1);
        StringBuilder keep = new StringBuilder();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            String k = kv.contains("=") ? kv.substring(0, kv.indexOf('=')) : kv;
            if (TRACKING.contains(k.toLowerCase(Locale.ROOT)) || k.toLowerCase(Locale.ROOT).startsWith("utm_")) continue;
            if (keep.length() > 0) keep.append('&');
            keep.append(kv);
        }
        return stripSlash(keep.length() == 0 ? base : base + "?" + keep);
    }

    private static String stripSlash(String u) {
        return u.endsWith("/") && u.indexOf('/', u.indexOf("://") + 3) < u.length() - 1 ? u.substring(0, u.length() - 1) : u;
    }

    /** Same page in different spellings (http/https, www, trailing slash, tracking) gets the same key. */
    public static String key(String url) {
        String c = clean(url).toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        return c.endsWith("/") ? c.substring(0, c.length() - 1) : c;
    }

    /** "docs.python.org" -> "python.org" (good enough for the usual .com / .org / .in; knows co.in, co.uk, gov.in ...). */
    public static String registrable(String host) {
        String h = host.replaceFirst("^www\\.", "");
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        if (Arrays.asList("co.in", "co.uk", "gov.in", "nic.in", "ac.in", "org.in", "net.in", "res.in", "edu.in", "com.au", "co.jp", "com.br").contains(last2) && p.length >= 3)
            return p[p.length - 3] + "." + last2;
        return last2;
    }

    public static boolean isHomepage(String url) {
        String p = path(url);
        return p.isEmpty() || p.equals("/");
    }

    public static String display(String url) {
        return host(url).replaceFirst("^www\\.", "");
    }
}
