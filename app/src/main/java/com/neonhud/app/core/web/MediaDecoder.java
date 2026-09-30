package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What only the phone can do with downloaded bytes (Android: BitmapFactory / PdfRenderer). Plugged in by the app; tests use a fake. */
public interface MediaDecoder {

    final class PdfPages {
        public final List<byte[]> jpegs;
        public final int totalPages;
        public PdfPages(List<byte[]> jpegs, int totalPages) {
            this.jpegs = jpegs == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(new ArrayList<byte[]>(jpegs));
            this.totalPages = totalPages;
        }
    }

    /** A picture in any format -> one JPEG whose longest side is at most {@code maxEdge}. Must throw when the bytes are not a picture. */
    byte[] toJpeg(byte[] raw, int maxEdge) throws Exception;

    /** The first {@code maxPages} pages of a PDF as JPEGs. Must throw for a corrupt or password-protected file. */
    PdfPages renderPdf(byte[] pdf, int maxPages) throws Exception;
}
