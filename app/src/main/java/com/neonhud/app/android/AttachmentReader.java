package com.neonhud.app.android;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.media.MediaMetadataRetriever;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.AttachmentLoader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads what the user attached, fully offline, with nothing but the Android framework:
 *   image -> one downscaled JPEG the model can look at
 *   pdf   -> its first pages rendered to JPEG (works for scanned PDFs too)
 *   video -> first video frame + basic metadata
 *   zip   -> file list + the start of each text/code file, within a fixed size budget
 * All limits are constants below; they keep the prompt small enough for a 2B on-device model.
 */
public final class AttachmentReader implements AttachmentLoader {

    static final int IMAGE_MAX_EDGE = 1024;
    static final int PDF_MAX_EDGE = 1100;
    static final int PDF_MAX_PAGES = 4;
    static final int JPEG_QUALITY = 85;

    static final int ZIP_MAX_ENTRIES = 2000;     // entries looked at before giving up
    static final int ZIP_LIST_MAX = 80;          // names shown in the file list
    static final int ZIP_FILE_MAX_BYTES = 2500;  // start of each text file that is shown
    static final int ZIP_TEXT_TOTAL = 12000;     // all shown text together (characters)

    private static final Set<String> TEXT_EXT = new HashSet<String>(Arrays.asList(
            "txt", "md", "json", "xml", "gradle", "kts", "java", "kt", "py", "js", "ts", "tsx", "jsx", "html", "css",
            "c", "cpp", "h", "hpp", "cs", "go", "rs", "sh", "yml", "yaml", "toml", "ini", "cfg", "properties", "csv",
            "sql", "php", "rb", "swift", "dart", "pro", "gitignore", "bat"));
    private static final Set<String> TEXT_NAMES = new HashSet<String>(Arrays.asList(
            "readme", "dockerfile", "makefile", "license"));
    private static final String[] SKIP_DIRS = {"node_modules/", ".git/", "build/", ".gradle/", "__macosx/", ".idea/", "/.cxx/"};

    private final Context app;

    public AttachmentReader(Context context) { this.app = context.getApplicationContext(); }

    @Override public Attachment load(Attachment a) throws Exception {
        Uri uri = Uri.parse(a.uri);
        switch (a.kind) {
            case IMAGE: return readImage(a, uri);
            case PDF:   return readPdf(a, uri);
            case ZIP:   return readZip(a, uri);
            case VIDEO: return readVideo(a, uri);
            default:    return a;
        }
    }

    // ------------------------------------------------------------------ image

    private Attachment readImage(Attachment a, Uri uri) throws IOException {
        Bitmap bm = BitmapLoader.decode(app.getContentResolver(), uri, IMAGE_MAX_EDGE);
        byte[] jpeg = BitmapLoader.toJpeg(bm, JPEG_QUALITY);
        bm.recycle();
        return a.loaded("ATTACHED IMAGE: " + a.name + " (the picture is shown above).", Collections.singletonList(jpeg));
    }

    // ------------------------------------------------------------------ pdf

    private Attachment readPdf(Attachment a, Uri uri) throws IOException {
        // PdfRenderer needs a seekable file; some providers are not, so work on a private copy.
        File dir = new File(app.getCacheDir(), "attach");
        dir.mkdirs();
        File tmp = new File(dir, "read.pdf");
        copy(app.getContentResolver(), uri, tmp);
        List<byte[]> pages = new ArrayList<byte[]>();
        int total;
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer renderer = new PdfRenderer(pfd);      // throws for corrupt / password-protected files
            try {
                total = renderer.getPageCount();
                int n = Math.min(total, PDF_MAX_PAGES);
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
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        StringBuilder note = new StringBuilder("ATTACHED PDF: ").append(a.name).append(" - ").append(total).append(total == 1 ? " page" : " pages");
        if (pages.isEmpty()) note.append(", but it has no pages to show.");
        else if (total > pages.size()) note.append(". Only the first ").append(pages.size()).append(" pages are shown above, in order.");
        else note.append(". All pages are shown above, in order.");
        return a.loaded(note.toString(), pages);
    }

    private static void copy(ContentResolver r, Uri from, File to) throws IOException {
        InputStream in = r.openInputStream(from);
        if (in == null) throw new IOException("cannot open the file");
        OutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            out.close();
        }
    }


    // ------------------------------------------------------------------ video

    private Attachment readVideo(Attachment a, Uri uri) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            r.setDataSource(app, uri);
            String duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            String note = "ATTACHED VIDEO: " + a.name;
            if (duration != null) note += " - " + duration + " ms";
            if (width != null && height != null) note += " - " + width + "x" + height;
            if (frame == null) note += ". No preview frame could be decoded.";
            List<byte[]> images = frame == null ? Collections.<byte[]>emptyList()
                    : Collections.singletonList(BitmapLoader.toJpeg(frame, JPEG_QUALITY));
            return a.loaded(note, images);
        } catch (RuntimeException e) {
            throw new IOException("video could not be read", e);
        } finally {
            if (frame != null) frame.recycle();
            try { r.release(); } catch (RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------------------------ zip

    private Attachment readZip(Attachment a, Uri uri) throws IOException {
        InputStream raw = app.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("cannot open the file");
        StringBuilder list = new StringBuilder();
        StringBuilder body = new StringBuilder();
        int files = 0, listed = 0, entries = 0;
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw));
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null && entries++ < ZIP_MAX_ENTRIES) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (skipped(name)) continue;
                files++;
                if (listed < ZIP_LIST_MAX) { list.append(name).append('\n'); listed++; }
                if (body.length() < ZIP_TEXT_TOTAL && isText(name)) {
                    String text = readStart(zin, ZIP_FILE_MAX_BYTES);
                    if (text != null) body.append("=== ").append(name).append(" ===\n").append(text).append("\n\n");
                }
            }
        } finally {
            try { zin.close(); } catch (IOException ignored) { }
        }
        StringBuilder out = new StringBuilder("ATTACHED ZIP: ").append(a.name).append(" - ").append(files).append(files == 1 ? " file" : " files").append(".\nFILE LIST:\n").append(list);
        if (files > listed) out.append("... and ").append(files - listed).append(" more files\n");
        if (body.length() > 0) {
            if (body.length() > ZIP_TEXT_TOTAL) body.setLength(ZIP_TEXT_TOTAL);
            out.append("\nSTART OF TEXT / CODE FILES:\n").append(body);
        } else {
            out.append("\n(no readable text files inside)");
        }
        return a.loaded(out.toString(), Collections.<byte[]>emptyList());
    }

    /** First bytes of the current zip entry as text; null if it looks binary. "(cut)" is added when more follows. */
    private static String readStart(ZipInputStream zin, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while (buf.size() < max && (n = zin.read(chunk, 0, Math.min(chunk.length, max - buf.size()))) > 0) buf.write(chunk, 0, n);
        boolean more = buf.size() >= max && zin.read() != -1;
        byte[] bytes = buf.toByteArray();
        for (byte b : bytes) if (b == 0) return null;            // NUL byte: binary file
        String s = new String(bytes, StandardCharsets.UTF_8);
        return more ? s + "\n...(cut)" : s;
    }

    private static boolean skipped(String name) {
        String n = "/" + name.toLowerCase(Locale.ROOT);
        for (String d : SKIP_DIRS) if (n.contains(d.startsWith("/") ? d : "/" + d)) return true;
        return false;
    }

    private static boolean isText(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        String ext = dot >= 0 ? base.substring(dot + 1) : "";
        String stem = dot >= 0 ? base.substring(0, dot) : base;
        return TEXT_EXT.contains(ext) || (ext.isEmpty() || ext.equals("txt") || ext.equals("md")) && TEXT_NAMES.contains(stem);
    }
}
