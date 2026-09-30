package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small in-memory Phase 4 cache. It saves Tavily credits and keeps follow-ups useful without pretending data is permanent. */
public final class WebCache {
    public static final long SEARCH_TTL_MS = 5L * 60 * 1000;
    public static final long PAGE_TTL_MS = 15L * 60 * 1000;
    private static final int MAX_SEARCH = 24;
    private static final int MAX_PAGES = 12;

    public static final class SearchValue {
        public final WebApi.SearchResponse response;
        public final long at;
        SearchValue(WebApi.SearchResponse response, long at) { this.response = response; this.at = at; }
    }
    public static final class PageValue {
        public final String url, text;
        public final long at;
        PageValue(String url, String text, long at) { this.url = url; this.text = text; this.at = at; }
    }

    private final Map<String, SearchValue> searches = new LinkedHashMap<String, SearchValue>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, SearchValue> e) { return size() > MAX_SEARCH; }
    };
    private final Map<String, PageValue> pages = new LinkedHashMap<String, PageValue>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, PageValue> e) { return size() > MAX_PAGES; }
    };

    private static String searchKey(WebApi.SearchRequest r) {
        return r.query.trim().toLowerCase(java.util.Locale.ROOT) + "|" + r.maxResults + "|" + r.advanced + "|" + r.includeImages + "|" + r.topic + "|" + r.timeRange;
    }
    private static String pageKey(String url) { return UrlTools.key(UrlTools.clean(url)); }

    public synchronized SearchValue getSearch(WebApi.SearchRequest r, long now) {
        SearchValue v = searches.get(searchKey(r));
        if (v == null || now - v.at >= SEARCH_TTL_MS) { if (v != null) searches.remove(searchKey(r)); return null; }
        return v;
    }
    public synchronized void putSearch(WebApi.SearchRequest r, WebApi.SearchResponse response, long now) { searches.put(searchKey(r), new SearchValue(response, now)); }

    public synchronized PageValue getPage(String url, long now) {
        PageValue v = pages.get(pageKey(url));
        if (v == null || now - v.at >= PAGE_TTL_MS) { if (v != null) pages.remove(pageKey(url)); return null; }
        return v;
    }
    public synchronized void putPage(String url, String text, long now) {
        if (text != null && !text.trim().isEmpty()) pages.put(pageKey(url), new PageValue(url, text, now));
    }
    public synchronized List<PageValue> getPages(List<String> urls, long now) {
        List<PageValue> out = new ArrayList<PageValue>();
        if (urls != null) for (String u : urls) { PageValue v = getPage(u, now); if (v != null) out.add(v); }
        return Collections.unmodifiableList(out);
    }
    public synchronized void clear() { searches.clear(); pages.clear(); }
    public synchronized int searchSize() { return searches.size(); }
    public synchronized int pageSize() { return pages.size(); }
}
