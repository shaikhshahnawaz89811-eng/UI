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

    public enum Kind { IMAGE, PDF, ZIP, AUDIO, VIDEO, DOCX, XLSX, PPTX }

    public final Kind kind;
    public final String name;
    public final long sizeBytes;          // -1 when unknown
    public final String uri;              // opaque to the core; the Android loader turns it back into a Uri
    public final String text;             // what the model should read about this file ("" when nothing)
    public final List<byte[]> images;     // JPEG bytes: one for an image, selected pages for a PDF, sampled frames for video
    public final List<String> imageLabels; // labels such as "page 3" / "frame 2" for vision-model context

    public Attachment(Kind kind, String name, long sizeBytes, String uri) {
        this(kind, name, sizeBytes, uri, "", Collections.<byte[]>emptyList(), Collections.<String>emptyList());
    }

    private Attachment(Kind kind, String name, long sizeBytes, String uri, String text, List<byte[]> images, List<String> imageLabels) {
        this.kind = kind;
        this.name = name == null ? "" : name;
        this.sizeBytes = sizeBytes;
        this.uri = uri == null ? "" : uri;
        this.text = text == null ? "" : text;
        this.images = images == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(images);
        List<String> labels = imageLabels == null ? new java.util.ArrayList<String>() : new java.util.ArrayList<String>(imageLabels);
        if (labels.size() < this.images.size()) {
            for (int i = labels.size(); i < this.images.size(); i++) labels.add(defaultImageLabel(kind, i + 1));
        } else if (labels.size() > this.images.size()) {
            labels = new java.util.ArrayList<String>(labels.subList(0, this.images.size()));
        }
        this.imageLabels = Collections.unmodifiableList(labels);
    }

    /** Same file with its payload attached. */
    public Attachment loaded(String payloadText, List<byte[]> payloadImages) {
        return new Attachment(kind, name, sizeBytes, uri, payloadText, payloadImages, null);
    }

    public Attachment loaded(String payloadText, List<byte[]> payloadImages, List<String> labels) {
        return new Attachment(kind, name, sizeBytes, uri, payloadText, payloadImages, labels);
    }

    private static String defaultImageLabel(Kind kind, int n) {
        if (kind == Kind.PDF) return "page " + n;
        if (kind == Kind.VIDEO) return "frame " + n;
        return "image " + n;
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
