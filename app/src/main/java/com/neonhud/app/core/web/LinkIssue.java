package com.neonhud.app.core.web;

/** One link the app could not read, and why. */
public final class LinkIssue {
    public final String url, domain;
    public final ReadIssue issue;
    public LinkIssue(String url, ReadIssue issue) {
        this.url = url == null ? "" : url;
        this.domain = UrlTools.display(this.url);
        this.issue = issue;
    }
}
