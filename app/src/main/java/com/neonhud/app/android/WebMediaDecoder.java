package com.neonhud.app.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.neonhud.app.core.web.MediaDecoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns downloaded bytes into JPEGs the vision model can look at (Phase 2: web pictures and PDFs behind links).
 * Same limits as attached files (see AttachmentReader); nothing here touches the network.
 */
public final class WebMediaDecoder implements MediaDecoder {

    private static final int JPEG_QUALITY = 85;
    private static final int PDF_MAX_EDGE = 1100;
    /** A picture that claims more pixels than this is refused before it is decoded (memory bomb). */
    private static final long MAX_PIXELS = 50_000_000L;

    private final Context app;

    public WebMediaDecoder(Context context) { this.app = context.getApplicationContext(); }

    @Override public byte[] toJpeg(byte[] raw, int maxEdge) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not a readable picture");
        if ((long) bounds.outWidth * bounds.outHeight > MAX_PIXELS) throw new IOException("picture too large");

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (bm == null) throw new IOException("could not decode the picture");

        Bitmap out = bm;
        float scale = Math.min(1f, maxEdge / (float) Math.max(bm.getWidth(), bm.getHeight()));
        if (scale < 1f) {
            out = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * scale)), Math.max(1, Math.round(bm.getHeight() * scale)), true);
            if (out != bm) bm.recycle();
        }
        try {
            return BitmapLoader.toJpeg(out, JPEG_QUALITY);
        } finally {
            out.recycle();
        }
    }

    @Override public PdfPages renderPdf(byte[] pdf, int maxPages) throws Exception {
        // PdfRenderer needs a seekable file: a private temporary copy, removed at the end
        File dir = new File(app.getCacheDir(), "web");
        dir.mkdirs();
        File tmp = File.createTempFile("read", ".pdf", dir);
        List<byte[]> pages = new ArrayList<byte[]>();
        int total;
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try { out.write(pdf); } finally { out.close(); }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                PdfRenderer renderer = new PdfRenderer(pfd);          // throws for corrupt / password-protected files
                try {
                    total = renderer.getPageCount();
                    int n = Math.min(total, maxPages);
                    for (int i = 0; i < n; i++) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        try {
                            float scale = PDF_MAX_EDGE / (float) Math.max(page.getWidth(), page.getHeight());
                            Bitmap bm = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                                    Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                            bm.eraseColor(Color.WHITE);
                            page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            pages.add(BitmapLoader.toJpeg(bm, JPEG_QUALITY));
                            bm.recycle();
                        } finally {
                            page.close();
                        }
                    }
                } finally {
                    renderer.close();
                }
            } finally {
                try { pfd.close(); } catch (IOException ignored) { }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return new PdfPages(pages, total);
    }
}
