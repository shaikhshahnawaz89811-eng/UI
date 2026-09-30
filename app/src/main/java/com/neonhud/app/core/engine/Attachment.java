package com.neonhud.app.core.engine;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A file the user attached to a message. The display fields (kind, name, size, uri) are always set; the payload
 * (text and images the model will see) is filled in later by an {@link AttachmentLoader}, on the worker thread.
 * Pure Java, no Android types, so the chat core and the JVM tests stay Android-free.
 */
public final class Attachment {

    public enum Kind { IMAGE, PDF, ZIP }

    public final Kind kind;
    public final String name;
    public final long sizeBytes;          // -1 when unknown
    public final String uri;              // opaque to the core; the Android loader turns it back into a Uri
    public final String text;             // what the model should read about this file ("" when nothing)
    public final List<byte[]> images;     // JPEG bytes: one for an image, the first pages for a PDF

    public Attachment(Kind kind, String name, long sizeBytes, String uri) {
        this(kind, name, sizeBytes, uri, "", Collections.<byte[]>emptyList());
    }

    private Attachment(Kind kind, String name, long sizeBytes, String uri, String text, List<byte[]> images) {
        this.kind = kind;
        this.name = name == null ? "" : name;
        this.sizeBytes = sizeBytes;
        this.uri = uri == null ? "" : uri;
        this.text = text == null ? "" : text;
        this.images = images == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(images);
    }

    /** Same file with its payload attached. */
    public Attachment loaded(String payloadText, List<byte[]> payloadImages) {
        return new Attachment(kind, name, sizeBytes, uri, payloadText, payloadImages);
    }

    /** "12.4 MB" / "340.0 KB" / "812 B"; empty when the size is unknown. */
    public String sizeLabel() { return formatSize(sizeBytes); }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
