package com.neonhud.app.core.web;

/** A picture the app shows under a reply (downloaded by the UI, never by the model). */
public final class WebPic {
    public final String url, caption;
    public WebPic(String url, String caption) { this.url = url; this.caption = caption == null ? "" : caption; }
}
