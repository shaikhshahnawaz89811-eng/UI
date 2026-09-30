package com.neonhud.app.core.web;

/** A page the app shows under a reply. Built only from real search results - the model never writes URLs itself. */
public final class WebLink {
    public final String title, url, domain;
    public WebLink(String title, String url) {
        this.title = title == null ? "" : title;
        this.url = url;
        this.domain = UrlTools.display(url);
    }
}
