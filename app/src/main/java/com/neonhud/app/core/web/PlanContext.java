package com.neonhud.app.core.web;

/** What the planner knows besides the message itself. */
public final class PlanContext {
    public final WebMode mode;
    public final boolean hasAttachments;   // the user attached a picture / pdf / zip to this message
    public final String prevQuery;         // the previous search query ("" = none) - lets "uski photo dikhao" find its subject
    public final String prevUserText;      // the previous user message ("" = none)
    public final java.util.List<String> prevUrls; // pages read in the previous web turn
    public final int year;

    public PlanContext(WebMode mode, boolean hasAttachments, String prevQuery, String prevUserText, int year) {
        this(mode, hasAttachments, prevQuery, prevUserText, java.util.Collections.<String>emptyList(), year);
    }

    public PlanContext(WebMode mode, boolean hasAttachments, String prevQuery, String prevUserText, java.util.List<String> prevUrls, int year) {
        this.mode = mode == null ? WebMode.AUTO : mode;
        this.hasAttachments = hasAttachments;
        this.prevQuery = prevQuery == null ? "" : prevQuery;
        this.prevUserText = prevUserText == null ? "" : prevUserText;
        this.prevUrls = prevUrls == null ? java.util.Collections.<String>emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(prevUrls));
        this.year = year;
    }

    public static PlanContext auto() { return new PlanContext(WebMode.AUTO, false, "", "", 2026); }
    public PlanContext withMode(WebMode m) { return new PlanContext(m, hasAttachments, prevQuery, prevUserText, prevUrls, year); }
    public PlanContext withAttachments(boolean a) { return new PlanContext(mode, a, prevQuery, prevUserText, prevUrls, year); }
    public PlanContext withPrev(String q, String text) { return new PlanContext(mode, hasAttachments, q, text, prevUrls, year); }
    public PlanContext withPrevUrls(java.util.List<String> urls) { return new PlanContext(mode, hasAttachments, prevQuery, prevUserText, urls, year); }
}
