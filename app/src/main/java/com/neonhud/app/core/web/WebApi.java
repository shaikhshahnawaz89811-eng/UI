package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the app needs from the internet. One implementation talks to Tavily over HTTPS (core/web/HttpWebApi);
 * tests plug in a fake, so every failure (bad key, limit, no internet, garbage answer) can be replayed on a JVM.
 * Blocking: always call from a worker thread.
 */
public interface WebApi {

    /** A search request. Built by {@link SearchPlan}, sent by the implementation. */
    final class SearchRequest {
        public final String query;
        public final int maxResults;
        public final boolean advanced;          // "advanced" costs 2 credits - only used as a second try
        public final boolean includeImages;
        public final String topic;              // "general" or "news"
        public final String timeRange;          // "", "day", "week", "month", "year"

        public SearchRequest(String query, int maxResults, boolean advanced, boolean includeImages,
                             String topic, String timeRange) {
            this.query = query == null ? "" : query;
            this.maxResults = maxResults;
            this.advanced = advanced;
            this.includeImages = includeImages;
            this.topic = topic == null || topic.isEmpty() ? "general" : topic;
            this.timeRange = timeRange == null ? "" : timeRange;
        }

        public SearchRequest plain() { return new SearchRequest(query, maxResults, advanced, includeImages, "general", ""); }
        public SearchRequest withQuery(String q) { return new SearchRequest(q, maxResults, advanced, includeImages, topic, timeRange); }
        public SearchRequest withAdvanced() { return new SearchRequest(query, maxResults, true, includeImages, topic, timeRange); }
    }

    final class Hit {
        public final String title, url, snippet;
        public final double score;
        public Hit(String title, String url, String snippet, double score) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.snippet = snippet == null ? "" : snippet;
            this.score = score;
        }
    }

    final class Image {
        public final String url, description;
        public Image(String url, String description) {
            this.url = url == null ? "" : url;
            this.description = description == null ? "" : description;
        }
    }

    final class SearchResponse {
        public final String answer;
        public final List<Hit> hits;
        public final List<Image> images;
        public SearchResponse(String answer, List<Hit> hits, List<Image> images) {
            this.answer = answer == null ? "" : answer;
            this.hits = hits == null ? Collections.<Hit>emptyList() : Collections.unmodifiableList(new ArrayList<Hit>(hits));
            this.images = images == null ? Collections.<Image>emptyList() : Collections.unmodifiableList(new ArrayList<Image>(images));
        }
    }

    /** Phase 2: read the full text of pages / PDFs the user pasted (Tavily Extract). */
    final class ExtractRequest {
        public final List<String> urls;
        public final boolean advanced;          // "advanced" (2 credits per 5 urls) only as a second try: slow sites, PDFs, scripts
        public ExtractRequest(List<String> urls, boolean advanced) {
            this.urls = urls == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(urls));
            this.advanced = advanced;
        }
        public ExtractRequest withAdvanced() { return new ExtractRequest(urls, true); }
    }

    final class Page {
        public final String url, content;
        public Page(String url, String content) { this.url = url == null ? "" : url; this.content = content == null ? "" : content; }
    }

    /** A url Tavily could not read, with the reason it gave (shown only as a short hint, never trusted). */
    final class FailedUrl {
        public final String url, reason;
        public FailedUrl(String url, String reason) { this.url = url == null ? "" : url; this.reason = reason == null ? "" : reason; }
    }

    final class ExtractResponse {
        public final List<Page> pages;
        public final List<FailedUrl> failed;
        public ExtractResponse(List<Page> pages, List<FailedUrl> failed) {
            this.pages = pages == null ? Collections.<Page>emptyList() : Collections.unmodifiableList(new ArrayList<Page>(pages));
            this.failed = failed == null ? Collections.<FailedUrl>emptyList() : Collections.unmodifiableList(new ArrayList<FailedUrl>(failed));
        }
    }

    /** Why a call failed - decides what the service does next (try the next key / stop / retry simpler). */
    enum Fail { INVALID_KEY, LIMIT, NETWORK, TIMEOUT, BAD_REQUEST, SERVER, PARSE }

    final class WebApiException extends Exception {
        public final Fail fail;
        public WebApiException(Fail fail, String detail) { super(detail); this.fail = fail; }
    }

    SearchResponse search(String apiKey, SearchRequest request) throws WebApiException;

    /** Reads the text of the given pages (Tavily Extract). Same failure kinds as {@link #search}. */
    ExtractResponse extract(String apiKey, ExtractRequest request) throws WebApiException;
}
