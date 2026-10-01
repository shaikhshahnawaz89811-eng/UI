package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The planner's decision for one user message: search or not, what to ask, and what the user may see afterwards. */
public final class SearchPlan {

    /** Why a search is (not) needed. CHAT = answer offline. */
    public enum Kind { CHAT, EXPLICIT, FRESH, LINK, IMAGE, PROCEDURE, COMPARE, FACT, URL, ALWAYS }

    public enum Links { NONE, ONE, MANY }

    public final boolean search;
    public final Kind kind;
    public final String query;
    public final boolean showImages;     // pictures are attached under the reply
    public final boolean readImages;     // pictures are only read (phase 2 feeds them to the model), never shown
    public final Links links;
    public final boolean steps, compare, readOnly, explicit;
    public final String topic;           // "general" / "news"
    public final String timeRange;       // "" / "day" / "week"
    public final List<String> urls;      // links the user pasted (phase 2 reads them)
    public final String reason;
    /** The user's own words without the pasted links - what a page has to answer ("" = just a link, so summarise it). */
    public final String question;

    SearchPlan(Builder b) {
        this.question = b.question;
        this.search = b.search; this.kind = b.kind; this.query = b.query;
        this.showImages = b.showImages; this.readImages = b.readImages; this.links = b.links;
        this.steps = b.steps; this.compare = b.compare; this.readOnly = b.readOnly; this.explicit = b.explicit;
        this.topic = b.topic; this.timeRange = b.timeRange;
        this.urls = Collections.unmodifiableList(new ArrayList<String>(b.urls));
        this.reason = b.reason;
    }

    public static SearchPlan none(String reason) {
        Builder b = new Builder();
        b.reason = reason;
        return new SearchPlan(b);
    }

    public boolean wantsImages() { return showImages || readImages; }

    /** True when this message is about links the user pasted: the pages are read, not searched for. */
    public boolean readsLinks() { return search && kind == Kind.URL && !urls.isEmpty(); }

    public WebApi.SearchRequest request(boolean advanced) {
        int n = compare || links == Links.MANY ? 6 : 5;
        return new WebApi.SearchRequest(query, n, advanced, wantsImages(), topic, timeRange);
    }

    @Override public String toString() {
        return (search ? "SEARCH" : "CHAT") + "{" + kind + ", q=\"" + query + "\", showImg=" + showImages + ", readImg=" + readImages
                + ", links=" + links + ", steps=" + steps + ", compare=" + compare + ", readOnly=" + readOnly + "}";
    }

    static final class Builder {
        boolean search, showImages, readImages, steps, compare, readOnly, explicit;
        Kind kind = Kind.CHAT;
        String query = "", topic = "general", timeRange = "", reason = "", question = "";
        Links links = Links.NONE;
        final List<String> urls = new ArrayList<String>();
        SearchPlan build() { return new SearchPlan(this); }
    }
}
