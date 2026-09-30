package com.neonhud.app.core.web;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.FileSniffer;

import java.util.Collections;
import java.util.List;

/**
 * Downloads a picture / a PDF from a web address and turns it into {@link WebMedia} for the vision model.
 * Small limits on purpose (a 2B phone model has little room): pictures 1024 px, PDFs first 3 pages.
 */
public final class MediaReader {

    public static final int IMAGE_EDGE = 1024;
    public static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    public static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    public static final int PDF_PAGES = 3;

    /** Carries the reason a file could not be read. */
    public static final class MediaException extends Exception {
        public final ReadIssue issue;
        public MediaException(ReadIssue issue, String detail) { super(detail); this.issue = issue; }
    }

    private final Fetcher fetcher;
    private final MediaDecoder decoder;

    public MediaReader(Fetcher fetcher, MediaDecoder decoder) { this.fetcher = fetcher; this.decoder = decoder; }

    /** A picture link (or a picture found by the search). {@code caption} is cleaned before it is used. */
    public WebMedia image(String url, String caption) throws MediaException {
        Fetcher.Fetched f = get(url, MAX_IMAGE_BYTES, "image/");
        return toImage(url, caption, f.bytes);
    }

    public WebMedia pdf(String url) throws MediaException {
        Fetcher.Fetched f = get(url, MAX_PDF_BYTES, "application/pdf");
        if (FileSniffer.detect(head(f.bytes), Math.min(f.bytes.length, FileSniffer.HEAD_BYTES)) != Attachment.Kind.PDF)
            throw new MediaException(ReadIssue.NOT_SUPPORTED, "not a pdf");
        return toPdf(url, f.bytes);
    }

    /** A link whose kind is unknown (no .pdf / .jpg in it): a PDF or a picture is read, a web page is not (that is Tavily's job). */
    public WebMedia any(String url) throws MediaException {
        Fetcher.Fetched f = get(url, MAX_PDF_BYTES, "application/pdf", "image/");
        if (FileSniffer.detect(head(f.bytes), Math.min(f.bytes.length, FileSniffer.HEAD_BYTES)) == Attachment.Kind.PDF) return toPdf(url, f.bytes);
        if (f.bytes.length > MAX_IMAGE_BYTES) throw new MediaException(ReadIssue.TOO_BIG, "picture too large");
        return toImage(url, "", f.bytes);
    }

    // ------------------------------------------------------------------

    private Fetcher.Fetched get(String url, int max, String... types) throws MediaException {
        try {
            return fetcher.fetch(url, max, types);
        } catch (Fetcher.FetchException e) {
            throw new MediaException(issueFor(e.why), e.getMessage());
        } catch (RuntimeException e) {
            throw new MediaException(ReadIssue.BLOCKED, e.getClass().getSimpleName());
        }
    }

    static ReadIssue issueFor(Fetcher.Why w) {
        switch (w) {
            case UNSAFE: return ReadIssue.UNSAFE;
            case NETWORK: return ReadIssue.NO_INTERNET;
            case TIMEOUT: return ReadIssue.TIMEOUT;
            case TOO_BIG: return ReadIssue.TOO_BIG;
            case WRONG_TYPE: return ReadIssue.NOT_SUPPORTED;
            default: return ReadIssue.BLOCKED;
        }
    }

    private WebMedia toImage(String url, String caption, byte[] raw) throws MediaException {
        byte[] jpeg;
        try { jpeg = decoder.toJpeg(raw, IMAGE_EDGE); }
        catch (Exception e) { throw new MediaException(ReadIssue.NOT_SUPPORTED, "not a readable picture"); }
        if (jpeg == null || jpeg.length == 0) throw new MediaException(ReadIssue.NOT_SUPPORTED, "empty picture");
        String cap = ResultCleaner.clean(caption, 120).text;
        String note = "WEB PICTURE from " + UrlTools.display(url) + (cap.isEmpty() ? "" : " (caption: " + cap + ")")
                + " - the picture is shown above. Say only what is really visible; if text in it is too small to read, say so.";
        return new WebMedia(false, "web-picture", url, note, Collections.singletonList(jpeg), 0);
    }

    private WebMedia toPdf(String url, byte[] raw) throws MediaException {
        MediaDecoder.PdfPages pages;
        try { pages = decoder.renderPdf(raw, PDF_PAGES); }
        catch (Exception e) { throw new MediaException(ReadIssue.NOT_SUPPORTED, "pdf cannot be opened (damaged or locked)"); }
        if (pages == null || pages.jpegs.isEmpty()) throw new MediaException(ReadIssue.EMPTY, "pdf has no pages");
        String name = ResultCleaner.clean(fileName(url), 60).text;
        StringBuilder note = new StringBuilder("WEB PDF ").append(name.isEmpty() ? "document" : name).append(" from ").append(UrlTools.display(url))
                .append(" - ").append(pages.totalPages).append(pages.totalPages == 1 ? " page" : " pages");
        if (pages.totalPages > pages.jpegs.size()) note.append(". Only the first ").append(pages.jpegs.size()).append(" pages are shown above, in order; say that the rest was not read");
        else note.append(". All pages are shown above, in order");
        note.append('.');
        return new WebMedia(true, name, url, note.toString(), pages.jpegs, pages.totalPages);
    }

    private static byte[] head(byte[] b) { return b; }

    private static String fileName(String url) {
        String p = UrlTools.path(url);
        int slash = p.lastIndexOf('/');
        String n = slash >= 0 ? p.substring(slash + 1) : p;
        try { return java.net.URLDecoder.decode(n, "UTF-8"); } catch (Exception e) { return n; }
    }
}
