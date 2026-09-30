package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A picture or a PDF from the internet that the vision model will LOOK at (never shown as a chat attachment).
 * The JPEG bytes were made by the phone (downscaled picture / the first PDF pages); {@link #note} is the only text that goes with them
 * and is written by the app, never copied from the web.
 */
public final class WebMedia {
    public final boolean pdf;
    public final String label, url, note;
    public final List<byte[]> jpegs;
    public final int totalPages;        // PDF only (0 for a picture)

    public WebMedia(boolean pdf, String label, String url, String note, List<byte[]> jpegs, int totalPages) {
        this.pdf = pdf; this.label = label == null ? "" : label; this.url = url == null ? "" : url;
        this.note = note == null ? "" : note;
        this.jpegs = jpegs == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(new ArrayList<byte[]>(jpegs));
        this.totalPages = totalPages;
    }
}
