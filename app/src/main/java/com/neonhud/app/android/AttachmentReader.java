package com.neonhud.app.android;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.engine.SelectableAttachmentLoader;
import com.neonhud.app.core.engine.ReadSelection;

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
 *   video -> several sampled video frames across the timeline + basic metadata
 *   audio -> duration / codec / sample rate / channels / tags (no speech-to-text, never invents a transcript)
 *   zip   -> file list + the start of each text/code file, within a fixed size budget
 *   docx/xlsx/pptx -> Office OOXML text/sheet/slide extraction through dedicated pure-Java skills
 * All limits are constants below; they keep the prompt small enough for a 2B on-device model.
 */
public final class AttachmentReader implements SelectableAttachmentLoader {

    static final int IMAGE_MAX_EDGE = 1024;
    static final int PDF_MAX_EDGE = 1100;
    static final int PDF_MAX_PAGES = 4;
    static final int JPEG_QUALITY = 85;

    // Keep enough temporal coverage to let the vision model understand a short video, while staying realistic for a phone.
    static final int VIDEO_MAX_EDGE = 960;
    static final int VIDEO_MAX_FRAMES = 6;

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
    private final OfficeAttachmentReader office;

    public AttachmentReader(Context context) {
        this.app = context.getApplicationContext();
        this.office = new OfficeAttachmentReader(this.app);
    }

    @Override public Attachment load(Attachment a, ReadSelection selection) throws Exception {
        Uri uri = Uri.parse(a.uri);
        ReadSelection sel = selection == null ? ReadSelection.none() : selection;
        switch (a.kind) {
            case IMAGE: return readImage(a, uri);
            case PDF:   return readPdf(a, uri, sel);
            case ZIP:   return readZip(a, uri);
            case VIDEO: return readVideo(a, uri);
            case AUDIO: return readAudio(a, uri);
            case DOCX: case XLSX: case PPTX: return office.load(a, sel);
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

    private Attachment readPdf(Attachment a, Uri uri, ReadSelection selection) throws IOException {
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
                int requestedStart = selection.hasPages() ? selection.pageStart : 1;
                int requestedEnd = selection.hasPages() ? selection.pageEnd : Math.min(total, PDF_MAX_PAGES);
                if (requestedStart < 1 || requestedStart > total) throw new IOException("PDF page " + requestedStart + " does not exist (" + total + " pages)");
                if (requestedEnd < requestedStart) throw new IOException("invalid PDF page range");
                requestedEnd = Math.min(requestedEnd, total);
                int end = Math.min(requestedEnd, requestedStart + PDF_MAX_PAGES - 1);
                List<String> labels = new ArrayList<String>();
                for (int pageNo = requestedStart; pageNo <= end; pageNo++) {
                    PdfRenderer.Page page = renderer.openPage(pageNo - 1);
                    try {
                        float scale = PDF_MAX_EDGE / (float) Math.max(page.getWidth(), page.getHeight());
                        Bitmap bm = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                                Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                        bm.eraseColor(Color.WHITE);
                        page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pages.add(BitmapLoader.toJpeg(bm, JPEG_QUALITY));
                        labels.add("page " + pageNo);
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
        if (selection.hasPages()) {
            note.append(". Requested page").append(selection.pageStart == selection.pageEnd ? " " + selection.pageStart : "s " + selection.pageStart + "-" + selection.pageEnd).append(" shown above");
            if (selection.pageEnd - selection.pageStart + 1 > PDF_MAX_PAGES) note.append("; only the first ").append(PDF_MAX_PAGES).append(" requested pages are sent to the model");
            else if (selection.pageEnd > total) note.append("; the request extended beyond the document and was clipped at page ").append(total);
            note.append('.');
        } else if (pages.isEmpty()) note.append(", but it has no pages to show.");
        else if (total > pages.size()) note.append(". Only the first ").append(pages.size()).append(" pages are shown above, in order.");
        else note.append(". All pages are shown above, in order.");
        return a.loaded(note.toString(), pages, labels);
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


    // ------------------------------------------------------------------ audio

    private Attachment readAudio(Attachment a, Uri uri) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, uri);
            String duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            String bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);

            // MediaMetadataRetriever has no channel-count key (and its numbered keys 38/39 are sample-rate/bits-per-sample,
            // API 31+ only), so sample rate and channels come from the track format on every Android version.
            int sampleRate = 0, channels = 0;
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(app, uri, null);
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String trackMime = f.containsKey(MediaFormat.KEY_MIME) ? f.getString(MediaFormat.KEY_MIME) : null;
                    if (trackMime != null && trackMime.startsWith("audio/")) {
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        break;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // sample rate / channels are a bonus: the rest of the note is still correct without them
            } finally {
                try { ex.release(); } catch (RuntimeException ignored) { }
            }

            StringBuilder note = new StringBuilder("ATTACHED AUDIO: ").append(a.name);
            long ms = -1L;
            try { if (duration != null) ms = Long.parseLong(duration); } catch (NumberFormatException ignored) { }
            if (ms >= 0L) note.append(" - length ").append(clock(ms));
            if (mime != null) note.append(" - ").append(mime);
            if (sampleRate > 0) note.append(" - ").append(sampleRate).append(" Hz");
            if (channels > 0) note.append(" - ").append(channels).append(channels == 1 ? " channel" : " channels");
            if (bitrate != null) note.append(" - ").append(bitrate).append(" bps");
            if (title != null && !title.isEmpty()) note.append(" - title: ").append(title);
            if (artist != null && !artist.isEmpty()) note.append(" - artist: ").append(artist);
            if (album != null && !album.isEmpty()) note.append(" - album: ").append(album);
            note.append(". Audio metadata is readable offline. Speech transcription requires a speech-to-text backend and is not fabricated by this skill.");
            return a.loaded(note.toString(), Collections.<byte[]>emptyList());
        } catch (RuntimeException e) {
            throw new IOException("audio could not be read", e);
        } finally {
            try { r.release(); } catch (RuntimeException ignored) { }
        }
    }

    private static String clock(long ms) {
        long s = ms / 1000L, h = s / 3600L, m = (s % 3600L) / 60L, sec = s % 60L;
        return h > 0L ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ------------------------------------------------------------------ video

    private Attachment readVideo(Attachment a, Uri uri) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, uri);
            String durationRaw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            long durationMs = 0L;
            try { if (durationRaw != null) durationMs = Math.max(0L, Long.parseLong(durationRaw)); } catch (NumberFormatException ignored) { }

            List<byte[]> frames = new ArrayList<byte[]>();
            if (durationMs > 0L) {
                int count = VIDEO_MAX_FRAMES;
                for (int i = 0; i < count; i++) {
                    // Spread samples across the whole video, including both ends.
                    long atMs = count == 1 ? 0L : (durationMs * i) / (count - 1);
                    Bitmap frame = null;
                    try {
                        frame = r.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (frame != null) {
                            Bitmap scaled = BitmapLoader.scale(frame, VIDEO_MAX_EDGE);
                            frames.add(BitmapLoader.toJpeg(scaled, JPEG_QUALITY));
                            if (scaled != frame) scaled.recycle();
                        }
                    } finally {
                        if (frame != null) frame.recycle();
                    }
                }
            }

            String note = "ATTACHED VIDEO: " + a.name;
            if (durationMs > 0L) note += " - " + durationMs + " ms";
            if (width != null && height != null) note += " - " + width + "x" + height;
            if ("yes".equalsIgnoreCase(hasAudio)) note += " - audio track present";
            if (frames.isEmpty()) note += ". No video frames could be decoded.";
            else note += ". " + frames.size() + " sampled frames are shown across the video.";
            if ("yes".equalsIgnoreCase(hasAudio)) note += " Audio is detected but speech transcription requires the Audio/Speech-to-Text backend.";
            else note += " No audio track was reported.";
            return a.loaded(note, frames);
        } catch (RuntimeException e) {
            throw new IOException("video could not be read", e);
        } finally {
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
