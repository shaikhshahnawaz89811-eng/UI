package com.neonhud.app.android;

import com.neonhud.app.core.skill.PdfWriter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Small offline PDF export skill. Kept separate from chat so a future Lab/export UI can call it directly. */
public final class PdfCreator implements PdfWriter {
    private static final int PAGE_WIDTH = 595;   // A4 at ~72 dpi
    private static final int PAGE_HEIGHT = 842;
    private static final int LEFT = 48;
    private static final int TOP = 58;
    private static final int BOTTOM = 48;
    private static final int BODY_SIZE = 14;
    private static final int TITLE_SIZE = 20;
    private static final int LINE_GAP = 7;

    @Override public File createTextPdf(String title, String text, File output) throws IOException {
        if (output == null) throw new IllegalArgumentException("output file is required");
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("cannot create output directory");
        String safeTitle = title == null || title.trim().isEmpty() ? "MJ Document" : title.trim();
        String safeText = text == null ? "" : text;

        PdfDocument doc = new PdfDocument();
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(TITLE_SIZE);
        titlePaint.setFakeBoldText(true);
        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setTextSize(BODY_SIZE);

        int pageNumber = 1;
        PdfDocument.Page page = null;
        Canvas canvas = null;
        float y = TOP;
        try {
            for (String line : wrapText(safeText, bodyPaint, PAGE_WIDTH - LEFT * 2)) {
                if (page == null || y > PAGE_HEIGHT - BOTTOM) {
                    if (page != null) doc.finishPage(page);
                    page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create());
                    canvas = page.getCanvas();
                    y = TOP;
                    canvas.drawText(safeTitle, LEFT, y, titlePaint);
                    y += TITLE_SIZE + 22;
                }
                canvas.drawText(line, LEFT, y, bodyPaint);
                y += BODY_SIZE + LINE_GAP;
            }
            if (page == null) {
                page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
                canvas = page.getCanvas();
                canvas.drawText(safeTitle, LEFT, TOP, titlePaint);
            }
            doc.finishPage(page);
            page = null;
            FileOutputStream out = new FileOutputStream(output);
            try { doc.writeTo(out); } finally { out.close(); }
            return output;
        } finally {
            if (page != null) {
                try { doc.finishPage(page); } catch (RuntimeException ignored) { }
            }
            doc.close();
        }
    }

    /** Verifies that Android can reopen the generated PDF and that it contains at least one page. */
    @Override public void verify(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0) throw new IOException("PDF output is missing or empty");
        android.os.ParcelFileDescriptor pfd = null;
        android.graphics.pdf.PdfRenderer renderer = null;
        try {
            pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new android.graphics.pdf.PdfRenderer(pfd);
            if (renderer.getPageCount() < 1) throw new IOException("PDF has no pages");
        } catch (RuntimeException e) {
            throw new IOException("PDF read-back verification failed", e);
        } finally {
            if (renderer != null) renderer.close();
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) { }
        }
    }

    private static java.util.List<String> wrapText(String text, Paint paint, float maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        String[] paragraphs = text.replace("\r", "").split("\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ", -1)) {
                String candidate = line.length() == 0 ? word : line.toString() + " " + word;
                if (line.length() > 0 && paint.measureText(candidate) > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
        }
        return lines;
    }
}
