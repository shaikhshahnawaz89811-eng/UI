package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the RIGHT page to hand to the user, not just the first result.
 * A page scores higher when its address / title mention the things the user asked about, when it is the official or a
 * trusted site (gov, edu, the brand's own domain), and when its depth fits the question (a how-to wants a deep page,
 * "official website" wants the home page). Social media, pin boards, Q&A dumps and link-spam score lower.
 */
public final class LinkPicker {
    private LinkPicker() { }

    private static final Set<String> LOW = new HashSet<String>(Arrays.asList(
            "pinterest.com", "facebook.com", "instagram.com", "twitter.com", "x.com", "quora.com", "reddit.com", "tiktok.com", "linkedin.com", "medium.com",
            "scribd.com", "slideshare.net", "youtube.com", "youtu.be", "t.me", "telegram.org", "whatsapp.com", "blogspot.com", "wordpress.com", "tumblr.com", "brainly.in", "brainly.com", "chegg.com"));
    private static final Set<String> TRUST = new HashSet<String>(Arrays.asList(
            "wikipedia.org", "britannica.com", "who.int", "un.org", "nih.gov", "mayoclinic.org", "nasa.gov", "isro.gov.in", "developer.android.com", "developer.mozilla.org",
            "docs.python.org", "python.org", "oracle.com", "kotlinlang.org", "github.com", "stackoverflow.com", "gsmarena.com", "bbc.com", "reuters.com", "thehindu.com", "indianexpress.com", "ndtv.com", "espncricinfo.com", "cricbuzz.com", "timeanddate.com"));
    private static final Set<String> STOP = new HashSet<String>(Arrays.asList(
            "how", "to", "the", "a", "an", "of", "in", "on", "for", "and", "step", "by", "price", "india", "official", "website", "comparison", "vs", "best", "latest", "new", "online"));

    public static final class Scored {
        public final WebApi.Hit hit;
        public final double score;
        Scored(WebApi.Hit hit, double score) { this.hit = hit; this.score = score; }
    }

    /** @param wantHome the user asked for "the official website" (home page is right); otherwise a deep page beats a home page. */
    public static List<WebLink> pick(List<WebApi.Hit> hits, String query, int max, boolean wantHome, boolean deepPreferred) {
        List<Scored> ranked = rank(hits, query, wantHome, deepPreferred);
        List<WebLink> out = new ArrayList<WebLink>();
        Set<String> seenPage = new HashSet<String>();
        Set<String> seenSite = new HashSet<String>();
        for (Scored s : ranked) {
            if (out.size() >= max) break;
            String url = UrlTools.clean(s.hit.url);
            if (!seenPage.add(UrlTools.key(url))) continue;
            String site = UrlTools.registrable(UrlTools.host(url));
            if (max > 1 && seenSite.contains(site) && out.size() < ranked.size() - 1 && hasOtherSite(ranked, seenSite)) continue;   // spread over different sites
            seenSite.add(site);
            out.add(new WebLink(ResultCleaner.title(s.hit.title, UrlTools.display(url)), url));
        }
        return out;
    }

    private static boolean hasOtherSite(List<Scored> ranked, Set<String> seen) {
        for (Scored s : ranked) if (!seen.contains(UrlTools.registrable(UrlTools.host(s.hit.url)))) return true;
        return false;
    }

    public static List<Scored> rank(List<WebApi.Hit> hits, String query, boolean wantHome, boolean deepPreferred) {
        Set<String> q = tokens(query);
        List<Scored> out = new ArrayList<Scored>();
        for (WebApi.Hit h : hits) {
            if (!UrlTools.isSafe(h.url)) continue;
            out.add(new Scored(h, score(h, q, wantHome, deepPreferred)));
        }
        Collections.sort(out, new Comparator<Scored>() {
            @Override public int compare(Scored a, Scored b) { return Double.compare(b.score, a.score); }
        });
        return out;
    }

    static double score(WebApi.Hit h, Set<String> q, boolean wantHome, boolean deepPreferred) {
        String host = UrlTools.host(h.url);
        String site = UrlTools.registrable(host);
        String path = UrlTools.path(h.url).toLowerCase(Locale.ROOT);
        double s = Math.max(0, Math.min(1, h.score)) * 2.0;                      // what the search engine itself thought
        Set<String> inUrl = tokens(host + " " + path.replace('/', ' '));
        Set<String> inTitle = tokens(h.title);
        Set<String> inText = tokens(h.snippet);
        int n = Math.max(1, q.size());
        int u = 0, t = 0, x = 0;
        for (String w : q) { if (inUrl.contains(w)) u++; if (inTitle.contains(w)) t++; if (inText.contains(w)) x++; }
        s += 1.6 * u / n + 1.4 * t / n + 0.6 * x / n;
        // the brand's own site: "sbi", "irctc", "passport" inside the domain label
        String label = site.contains(".") ? site.substring(0, site.indexOf('.')) : site;
        for (String w : q) if (w.length() >= 3 && (label.equals(w) || (label.contains(w) && w.length() >= 4))) { s += 1.2; break; }
        if (site.endsWith("gov.in") || site.endsWith("nic.in") || site.endsWith(".gov") || site.endsWith("gov.uk") || site.endsWith(".edu") || site.endsWith("ac.in")) s += 0.9;
        if (TRUST.contains(site)) s += 0.5;
        if (LOW.contains(site)) s -= 1.4;
        if (host.startsWith("m.") || host.startsWith("amp.")) s -= 0.2;
        boolean home = UrlTools.isHomepage(h.url);
        if (wantHome) { if (home) s += 1.0; else if (path.split("/").length > 3) s -= 0.4; }
        else if (deepPreferred) { if (home) s -= 0.9; else s += 0.3; }
        else if (home) s -= 0.3;
        if (path.matches(".*\\.(pdf)$")) s += deepPreferred ? 0.2 : -0.1;
        if (h.url.toLowerCase(Locale.ROOT).startsWith("http://")) s -= 0.3;
        if (path.contains("/tag/") || path.contains("/category/") || path.contains("/search") || path.contains("/login") && !q.contains("login")) s -= 0.6;
        return s;
    }

    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<String>();
        if (text == null) return out;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u0900-\\u097F]+")) {
            if (w.length() < 2 || STOP.contains(w)) continue;
            out.add(w);
            if (w.length() > 4 && w.endsWith("s")) out.add(w.substring(0, w.length() - 1));
        }
        return out;
    }
}
