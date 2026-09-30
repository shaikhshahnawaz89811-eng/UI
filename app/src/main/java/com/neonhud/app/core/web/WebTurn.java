package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything the chat needs after the web step of one message. */
public final class WebTurn {

    public enum Problem { NONE, OFF, NO_KEY, ALL_KEYS_LIMIT, ALL_KEYS_INVALID, NO_INTERNET, TIMEOUT, SERVER, EMPTY, BAD_ANSWER, UNREADABLE }

    public final SearchPlan plan;
    public final boolean searched;
    public final Problem problem;
    /** Text for the model (sources + how to answer, or "search failed, say so"); "" = add nothing. */
    public final String context;
    public final List<WebLink> links;
    public final List<WebPic> pics;
    /** A short line for the user when something went wrong and they can fix it; "" = stay silent. */
    public final String notice;
    public final String query;
    public final int keysTried, injectionHits;
    /** Phase 2: pictures / PDF pages the vision model looks at for this message (from pasted links or web pictures). */
    public final List<WebMedia> media;
    /** Phase 2: pasted links that could not be read (empty when all were read). */
    public final List<LinkIssue> issues;

    WebTurn(SearchPlan plan, boolean searched, Problem problem, String context, List<WebLink> links, List<WebPic> pics,
            String notice, String query, int keysTried, int injectionHits) {
        this(plan, searched, problem, context, links, pics, notice, query, keysTried, injectionHits, null, null);
    }

    WebTurn(SearchPlan plan, boolean searched, Problem problem, String context, List<WebLink> links, List<WebPic> pics,
            String notice, String query, int keysTried, int injectionHits, List<WebMedia> media, List<LinkIssue> issues) {
        this.media = media == null ? Collections.<WebMedia>emptyList() : Collections.unmodifiableList(new ArrayList<WebMedia>(media));
        this.issues = issues == null ? Collections.<LinkIssue>emptyList() : Collections.unmodifiableList(new ArrayList<LinkIssue>(issues));
        this.plan = plan; this.searched = searched; this.problem = problem;
        this.context = context == null ? "" : context;
        this.links = links == null ? Collections.<WebLink>emptyList() : Collections.unmodifiableList(new ArrayList<WebLink>(links));
        this.pics = pics == null ? Collections.<WebPic>emptyList() : Collections.unmodifiableList(new ArrayList<WebPic>(pics));
        this.notice = notice == null ? "" : notice;
        this.query = query == null ? "" : query;
        this.keysTried = keysTried; this.injectionHits = injectionHits;
    }

    public static WebTurn none(SearchPlan plan) { return new WebTurn(plan, false, Problem.NONE, "", null, null, "", "", 0, 0); }
    public boolean ok() { return searched && problem == Problem.NONE; }
}
