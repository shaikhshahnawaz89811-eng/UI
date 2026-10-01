package com.neonhud.app.core.web;

/** What the planner knows besides the message itself. */
public final class PlanContext {
    public final WebMode mode;
    public final boolean hasAttachments;   // the user attached a picture / pdf / zip to this message
    public final String prevQuery;         // the previous search query ("" = none) - lets "uski photo dikhao" find its subject
    public final String prevUserText;      // the previous user message ("" = none)
    public final int year;

    public PlanContext(WebMode mode, boolean hasAttachments, String prevQuery, String prevUserText, int year) {
        this.mode = mode == null ? WebMode.AUTO : mode;
        this.hasAttachments = hasAttachments;
        this.prevQuery = prevQuery == null ? "" : prevQuery;
        this.prevUserText = prevUserText == null ? "" : prevUserText;
        this.year = year;
    }

    public static PlanContext auto() { return new PlanContext(WebMode.AUTO, false, "", "", 2026); }
    public PlanContext withMode(WebMode m) { return new PlanContext(m, hasAttachments, prevQuery, prevUserText, year); }
    public PlanContext withAttachments(boolean a) { return new PlanContext(mode, a, prevQuery, prevUserText, year); }
    public PlanContext withPrev(String q, String text) { return new PlanContext(mode, hasAttachments, q, text, year); }
}
