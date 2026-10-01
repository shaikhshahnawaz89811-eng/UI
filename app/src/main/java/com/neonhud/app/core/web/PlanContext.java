package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the planner knows besides the message itself. */
public final class PlanContext {
    public final WebMode mode;
    public final boolean hasAttachments;   // the user attached a picture / pdf / zip to this message
    public final String prevQuery;         // the previous search query ("" = none) - lets "uski photo dikhao" find its subject
    public final String prevUserText;      // the previous user message ("" = none)
    public final List<String> prevUrls;    // links from the previous web/page turn, if any
    public final int year;

    /** Backward-compatible constructor for existing callers that have no previous URLs. */
    public PlanContext(WebMode mode, boolean hasAttachments, String prevQuery, String prevUserText, int year) {
        this(mode, hasAttachments, prevQuery, prevUserText, Collections.<String>emptyList(), year);
    }

    /** Phase 4 constructor: also carries the most recently read URL(s) for cached follow-ups. */
    public PlanContext(WebMode mode, boolean hasAttachments, String prevQuery, String prevUserText, List<String> prevUrls, int year) {
        this.mode = mode == null ? WebMode.AUTO : mode;
        this.hasAttachments = hasAttachments;
        this.prevQuery = prevQuery == null ? "" : prevQuery;
        this.prevUserText = prevUserText == null ? "" : prevUserText;
        List<String> safe = new ArrayList<String>();
        if (prevUrls != null) {
            for (String url : prevUrls) {
                if (url != null && !url.trim().isEmpty() && !safe.contains(url)) safe.add(url);
            }
        }
        this.prevUrls = Collections.unmodifiableList(safe);
        this.year = year;
    }

    public static PlanContext auto() { return new PlanContext(WebMode.AUTO, false, "", "", Collections.<String>emptyList(), 2026); }
    public PlanContext withMode(WebMode m) { return new PlanContext(m, hasAttachments, prevQuery, prevUserText, prevUrls, year); }
    public PlanContext withAttachments(boolean a) { return new PlanContext(mode, a, prevQuery, prevUserText, prevUrls, year); }
    public PlanContext withPrev(String q, String text) { return new PlanContext(mode, hasAttachments, q, text, prevUrls, year); }
    public PlanContext withPrev(String q, String text, List<String> urls) { return new PlanContext(mode, hasAttachments, q, text, urls, year); }
}
